package com.alberto.medp2p_poc.ui.patients

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PatientsViewModel gestiona la lista completa de pacientes, el filtro
// de búsqueda y el filtro de favoritos. La lista en memoria (_allPatients)
// se combina con _searchQuery y _filterFavorites para producir un
// StateFlow<List<Patient>> filtrado que la UI observa directamente.
//
// Toda operación de DB corre en Dispatchers.IO. La UI nunca toca
// SQLite ni sabe cómo se persisten los datos. MVVM limpio.
// ──────────────────────────────────────────────────────────────────────

/** Estado visual de la pantalla de pacientes. */
data class PatientsUiState(
    val patients: List<Patient> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filterFavorites: Boolean = false,
    val totalCount: Int = 0
)

class PatientsViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = AppDatabaseHelper(application)

    private val _uiState = MutableStateFlow(PatientsUiState())
    val uiState: StateFlow<PatientsUiState> = _uiState.asStateFlow()

    // ── Datos internos para filtrado reactivo ──
    private var allPatients: List<Patient> = emptyList()

    companion object {
        private const val TAG = "P2P_PATIENTS"
    }

    init {
        loadPatients()
    }

    // ══════════════════════════════════════════════════════════════
    // ══ CARGA DE DATOS ══════════════════════════════════════════
    // ════════════════════��═════════════════════════════════════════

    fun loadPatients() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val patients = dbHelper.obtenerPacientesClinico()
                allPatients = patients
                applyFilters()
                Log.d(TAG, "Cargados ${patients.size} pacientes.")
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error cargando pacientes: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ BÚSQUEDA Y FILTROS ══════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilters()
    }

    fun toggleFavoriteFilter() {
        _uiState.value = _uiState.value.copy(
            filterFavorites = !_uiState.value.filterFavorites
        )
        applyFilters()
    }

    private fun applyFilters() {
        val query = _uiState.value.searchQuery.trim().lowercase()
        val onlyFavs = _uiState.value.filterFavorites

        val filtered = allPatients.filter { patient ->
            val matchesQuery = query.isEmpty() ||
                    patient.fullName.lowercase().contains(query) ||
                    patient.allergies.lowercase().contains(query)
            val matchesFav = !onlyFavs || patient.isFavorite
            matchesQuery && matchesFav
        }

        _uiState.value = _uiState.value.copy(
            patients = filtered,
            totalCount = allPatients.size,
            isLoading = false
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ══ OPERACIONES CRUD ════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Vincula un nuevo paciente al sistema.
     * @param fullName Nombre completo del paciente.
     * @param peerId Identificador de red (escaneado del QR o introducido).
     * @param allergies Alergias conocidas (opcional).
     */
    fun linkPatient(fullName: String, peerId: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName = fullName.trim(),
                    peerId = peerId.trim(),
                    allergies = allergies.trim(),
                    avatarColorIndex = colorIndex
                )
                dbHelper.insertarPacienteClinico(patient)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadPatients() // Refresca la lista completa
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error vinculando paciente: ${e.message}")
            }
        }
    }

    /** Marca o desmarca un paciente como favorito. */
    fun toggleFavorite(patientId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.toggleFavoritoPaciente(patientId)
                loadPatients()
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error toggle favorito: ${e.message}")
            }
        }
    }

    /** Elimina un paciente vinculado. */
    fun removePatient(patientId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.eliminarPacienteClinico(patientId)
                Log.d(TAG, "Paciente eliminado: $patientId")
                loadPatients()
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error eliminando paciente: ${e.message}")
            }
        }
    }
}