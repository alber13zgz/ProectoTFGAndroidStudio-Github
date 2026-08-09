package com.alberto.medp2p_poc.ui.patients.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Medicamento
import com.alberto.medp2p_poc.data.model.Patient
import com.alberto.medp2p_poc.data.model.PautaMedica
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PatientDetailViewModel centraliza toda la información clínica de UN
// paciente concreto. Carga en paralelo (Dispatchers.IO) los datos
// personales, las medicaciones activas y el historial médico.
//
// NUEVO: recibe referencia a DashboardViewModel para:
//   1. Enviar notas clínicas por P2P al paciente (sendRecord).
//   2. Observar el SharedFlow de mensajes P2P entrantes y actualizar
//      la UI en tiempo real sin recargar desde SQLite.
// ══════════════════════════════════════════════════════════════════════

data class ActiveMedication(
    val medication: Medicamento,
    val prescription: PautaMedica,
    val nextDoseLabel: String = ""
)

// ── Estado del último envío P2P para feedback visual en la UI ─────────
sealed class SendResult {
    object Idle      : SendResult()  // Sin operación en curso
    object Sending   : SendResult()  // Enviando por P2P...
    object Delivered : SendResult()  // ACK recibido — entregado
    object SavedOnly : SendResult()  // Sin red — guardado local, pendiente
}

data class PatientDetailState(
    val patient: Patient? = null,
    val activeMedications: List<ActiveMedication> = emptyList(),
    val medicalHistory: List<MedicalRecord> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val lastSendResult: SendResult = SendResult.Idle
)

class PatientDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = AppDatabaseHelper(application)

    private val _state = MutableStateFlow(PatientDetailState())
    val state: StateFlow<PatientDetailState> = _state.asStateFlow()

    companion object {
        private const val TAG = "P2P_DETAIL"
    }

    // ══════════════════════════════════════════════════════════════
    // CARGA INICIAL
    // Se llama una vez al entrar en pantalla via LaunchedEffect(peerId)
    // ══════════════════════════════════════════════════════════════
    fun loadPatientDetail(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val patient = dbHelper.obtenerPacienteClinicoPorPeerId(peerId, ownerPeerId)
            val history = dbHelper.obtenerHistorial(peerId, ownerPeerId)
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            try {
                val patient = dbHelper.obtenerPacienteClinicoPorPeerId(peerId, ownerPeerId)

                if (patient == null) {
                    _state.value = _state.value.copy(
                        isLoading    = false,
                        errorMessage = "No se encontro el paciente."
                    )
                    return@launch
                }
                val medications = dbHelper.obtenerMedicacionesActivas(peerId)
                val history = dbHelper.obtenerHistorial(peerId, ownerPeerId)
                _state.value = PatientDetailState(
                    patient           = patient,
                    activeMedications = medications,
                    medicalHistory    = history,
                    isLoading         = false
                )
                Log.d(TAG, "Detalle cargado: ${patient.fullName} | " +
                        "${medications.size} meds | ${history.size} notas")

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando detalle: ${e.message}")
                _state.value = _state.value.copy(
                    isLoading    = false,
                    errorMessage = "Error al cargar los datos clinicos."
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // OBSERVAR MENSAJES P2P ENTRANTES EN TIEMPO REAL
    //
    // Colecta el SharedFlow de DashboardViewModel. Cada MedicalRecord
    // que llega por P2P y pertenece a este paciente se añade al estado
    // directamente sin recargar desde SQLite (O(1), sin parpadeo).
    //
    // Se activa desde AppNavigation con LaunchedEffect(peerId) para
    // garantizar una sola corutina por entrada en pantalla.
    // ══════════════════════════════════════════════════════════════
    fun observeIncomingMessages(dashboardViewModel: DashboardViewModel, peerId: String) {
        viewModelScope.launch {
            dashboardViewModel.incomingMessages?.collect { record ->
                if (record.patientId == peerId) {
                    _state.value = _state.value.copy(
                        medicalHistory = _state.value.medicalHistory + record
                    )
                    Log.i(TAG, "[TIEMPO REAL] Registro P2P recibido en UI: id=${record.id}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // AÑADIR NOTA CLÍNICA Y ENVIAR POR P2P
    //
    // Flujo completo del botón "Guardar nota":
    //   1. Crear MedicalRecord con los datos del formulario.
    //   2. Si hay dashboardViewModel + destinationCircuitAddr:
    //      → sendRecord() guarda en SQLite, encola en sync_log,
    //        envía por P2P y espera ACK.
    //   3. Si no hay destino P2P conocido:
    //      → Solo guarda local (quedará PENDING para reintento futuro).
    //   4. Actualización optimista de la UI: el registro aparece
    //      inmediatamente sin esperar confirmación de red.
    //
    // destinationCircuitAddr formato Circuit Relay:
    //   /ip4/<relay>/tcp/4001/p2p/<relayId>/p2p-circuit/p2p/<patientPeerId>
    // ══════════════════════════════════════════════════════════════
    fun addClinicalNote(
        peerId: String,
        noteText: String,
        dashboardViewModel: DashboardViewModel? = null,
        destinationCircuitAddr: String? = null
    ) {
        if (noteText.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(lastSendResult = SendResult.Sending)
            try {
                val record = MedicalRecord(
                    patientId   = peerId,
                    text        = noteText.trim(),
                    isMine      = true,
                    senderAlias = "Profesional"
                )

                if (dashboardViewModel != null && destinationCircuitAddr != null) {
                    // ── CON P2P: sendRecord guarda + encola + envía ───
                    dashboardViewModel.sendRecord(destinationCircuitAddr, record)
                    _state.value = _state.value.copy(
                        medicalHistory = _state.value.medicalHistory + record,
                        lastSendResult = SendResult.Delivered
                    )
                    Log.i(TAG, "[P2P] Nota enviada a $peerId")
                } else {
                    // ── SOLO LOCAL: sin dirección P2P conocida ────────
                    dbHelper.guardarRegistroMedico(record, ownerPeerId)
                    val updatedHistory = dbHelper.obtenerHistorial(peerId, ownerPeerId)
                    _state.value = _state.value.copy(
                        medicalHistory = updatedHistory,
                        lastSendResult = SendResult.SavedOnly
                    )
                    Log.d(TAG, "[LOCAL] Nota guardada localmente para $peerId")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error guardando nota: ${e.message}")
                _state.value = _state.value.copy(lastSendResult = SendResult.Idle)
            }
        }
    }

    fun addPrescription(peerId: String, medicamentoId: String, intervaloHoras: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pauta = PautaMedica(
                    pacienteId     = peerId,
                    medicamentoId  = medicamentoId,
                    intervaloHoras = intervaloHoras
                )
                dbHelper.insertarPautaMedica(pauta)
                Log.d(TAG, "Pauta anadida: med=$medicamentoId cada ${intervaloHoras}h")
                val updated = dbHelper.obtenerMedicacionesActivas(peerId)
                _state.value = _state.value.copy(activeMedications = updated)
            } catch (e: Exception) {
                Log.e(TAG, "Error creando pauta: ${e.message}")
            }
        }
    }
}