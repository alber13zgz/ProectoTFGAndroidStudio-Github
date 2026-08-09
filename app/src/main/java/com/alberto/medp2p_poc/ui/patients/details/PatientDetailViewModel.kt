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

data class ActiveMedication(
    val medication: Medicamento,
    val prescription: PautaMedica,
    val nextDoseLabel: String = ""
)

sealed class SendResult {
    object Idle      : SendResult()
    object Sending   : SendResult()
    object Delivered : SendResult()
    object SavedOnly : SendResult()
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

    // ── ownerPeerId: se establece en loadPatientDetail() y se reutiliza
    // en addClinicalNote() para todas las queries a la DB.
    // Se guarda en la instancia porque loadPatientDetail puede llamarse
    // varias veces (refresh) y siempre debe usar el mismo owner.
    private var ownerPeerId: String = ""

    companion object {
        private const val TAG = "P2P_DETAIL"
    }

    // ══════════════════════════════════════════════════════════════
    // loadPatientDetail()
    //
    // Recibe ownerPeerId del DashboardViewModel para filtrar las
    // queries por el usuario activo (Row-Level Security).
    // Se llama desde AppNavigation con LaunchedEffect(peerId).
    // ══════════════════════════════════════════════════════════════
    fun loadPatientDetail(peerId: String, ownerPeerId: String) {
        this.ownerPeerId = ownerPeerId
        viewModelScope.launch(Dispatchers.IO) {
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
                val history     = dbHelper.obtenerHistorial(peerId, ownerPeerId)

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
    // observeIncomingMessages()
    //
    // Colecta el SharedFlow del DashboardViewModel. Cuando llega
    // un MedicalRecord para este paciente, se añade al estado
    // directamente sin recargar desde SQLite (O(1), sin parpadeo).
    // ══════════════════════════════════════════════════════════════
    fun observeIncomingMessages(dashboardViewModel: DashboardViewModel, peerId: String) {
        viewModelScope.launch {
            dashboardViewModel.incomingMessages?.collect { record ->
                if (record.patientId == peerId) {
                    _state.value = _state.value.copy(
                        medicalHistory = _state.value.medicalHistory + record
                    )
                    Log.i(TAG, "[TIEMPO REAL] Registro P2P recibido: id=${record.id}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // addClinicalNote()
    //
    // Flujo del botón "Guardar nota":
    //   CON P2P: sendRecord() guarda + encola + envía al paciente.
    //   SIN P2P: guarda localmente con ownerPeerId del profesional.
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
                    // CON P2P: sendRecord guarda + encola + envía
                    dashboardViewModel.sendRecord(destinationCircuitAddr, record)
                    _state.value = _state.value.copy(
                        medicalHistory = _state.value.medicalHistory + record,
                        lastSendResult = SendResult.Delivered
                    )
                    Log.i(TAG, "[P2P] Nota enviada a $peerId")
                } else {
                    // SOLO LOCAL: guardar con ownerPeerId del profesional activo
                    dbHelper.guardarRegistroMedico(record, ownerPeerId)
                    val updatedHistory = dbHelper.obtenerHistorial(peerId, ownerPeerId)
                    _state.value = _state.value.copy(
                        medicalHistory = updatedHistory,
                        lastSendResult = SendResult.SavedOnly
                    )
                    Log.d(TAG, "[LOCAL] Nota guardada para $peerId (owner=$ownerPeerId)")
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
                val updated = dbHelper.obtenerMedicacionesActivas(peerId)
                _state.value = _state.value.copy(activeMedications = updated)
                Log.d(TAG, "Pauta añadida: med=$medicamentoId cada ${intervaloHoras}h")
            } catch (e: Exception) {
                Log.e(TAG, "Error creando pauta: ${e.message}")
            }
        }
    }
}