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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PatientDetailViewModel centraliza toda la información clínica de UN
// paciente concreto. Carga en paralelo (desde Dispatchers.IO) los datos
// personales, las medicaciones activas y el historial médico. La UI
// observa un único StateFlow<PatientDetailState> inmutable y renderiza
// 3 pestañas sin tocar la base de datos directamente. MVVM limpio.
// ──────────────────────────────────────────────────────────────────────

/** Modelo de una medicación activa con su pauta asociada. */
data class ActiveMedication(
    val medication: Medicamento,
    val prescription: PautaMedica,
    val nextDoseLabel: String = ""
)

/** Estado completo de la pantalla de detalle. */
data class PatientDetailState(
    val patient: Patient? = null,
    val activeMedications: List<ActiveMedication> = emptyList(),
    val medicalHistory: List<MedicalRecord> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class PatientDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = AppDatabaseHelper(application)

    private val _state = MutableStateFlow(PatientDetailState())
    val state: StateFlow<PatientDetailState> = _state.asStateFlow()

    companion object {
        private const val TAG = "P2P_DETAIL"
    }

    /**
     * Carga toda la informacion clinica de un paciente por su peerId.
     * Se llama una vez al entrar en la pantalla de detalle.
     */
    fun loadPatientDetail(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)

            try {
                // PASO 1: Datos del paciente
                val patient = dbHelper.obtenerPacienteClinicoPorPeerId(peerId)
                if (patient == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = "No se encontro el paciente."
                    )
                    return@launch
                }

                // PASO 2: Medicaciones activas (pautas + medicamentos)
                val medications = dbHelper.obtenerMedicacionesActivas(peerId)

                // PASO 3: Historial medico (notas clinicas)
                val history = dbHelper.obtenerHistorial(peerId)

                _state.value = PatientDetailState(
                    patient = patient,
                    activeMedications = medications,
                    medicalHistory = history,
                    isLoading = false
                )

                Log.d(TAG, "Detalle cargado: ${patient.fullName} | " +
                        "${medications.size} meds | ${history.size} notas")

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error cargando detalle: ${e.message}")
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = "Error al cargar los datos clinicos."
                )
            }
        }
    }

    /** Anade una nota clinica al historial del paciente. */
    fun addClinicalNote(peerId: String, noteText: String) {
        if (noteText.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val record = MedicalRecord(
                    patientId = peerId,
                    text = noteText.trim(),
                    isMine = true,
                    senderAlias = "Profesional"
                )
                dbHelper.guardarRegistroMedico(record)
                Log.d(TAG, "Nota clinica anadida para $peerId")

                // Recargar historial
                val updatedHistory = dbHelper.obtenerHistorial(peerId)
                _state.value = _state.value.copy(medicalHistory = updatedHistory)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error guardando nota: ${e.message}")
            }
        }
    }

    /** Anade una medicacion activa (pauta + medicamento existente). */
    fun addPrescription(peerId: String, medicamentoId: String, intervaloHoras: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pauta = PautaMedica(
                    pacienteId = peerId,
                    medicamentoId = medicamentoId,
                    intervaloHoras = intervaloHoras
                )
                dbHelper.insertarPautaMedica(pauta)
                Log.d(TAG, "Pauta anadida: med=$medicamentoId cada ${intervaloHoras}h")

                // Recargar medicaciones
                val updated = dbHelper.obtenerMedicacionesActivas(peerId)
                _state.value = _state.value.copy(activeMedications = updated)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error creando pauta: ${e.message}")
            }
        }
    }
}