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
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PatientsViewModel gestiona la lista de pacientes del usuario activo.
//
// FIX Row-Level Security: ownerPeerId se establece una sola vez en
// init() y se usa en todas las queries para filtrar exclusivamente
// los pacientes que pertenecen al usuario logueado. Sin esto, un
// médico vería los pacientes de otro médico en el mismo dispositivo.
//
// init() se llama desde AppNavigation justo después de viewModel(),
// pasando dashboardViewModel.currentOwnerPeerId.
// ══════════════════════════════════════════════════════════════════════

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

    private var allPatients: List<Patient> = emptyList()

    // ownerPeerId del usuario activo — se establece en init()
    // antes de cualquier llamada a la DB.
    private var ownerPeerId: String = ""

    companion object {
        private const val TAG = "P2P_PATIENTS"
    }

    // ══════════════════════════════════════════════════════════════
    // init(): debe llamarse desde AppNavigation pasando el ownerPeerId
    // del usuario logueado antes de que la pantalla empiece a mostrar
    // datos. Si ownerPeerId ya está establecido, no recarga.
    // ══════════════════════════════════════════════════════════════
    fun init(ownerPeerId: String) {
        if (this.ownerPeerId == ownerPeerId) return  // ya inicializado, evitar recarga innecesaria
        this.ownerPeerId = ownerPeerId
        loadPatients()
    }

    fun loadPatients() {
        if (ownerPeerId.isBlank()) return  // no cargar si no hay usuario activo
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val patients = dbHelper.obtenerPacientesClinico(ownerPeerId)
                allPatients  = patients
                applyFilters()
                Log.d(TAG, "Cargados ${patients.size} pacientes para owner=$ownerPeerId")
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando pacientes: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilters()
    }

    fun toggleFavoriteFilter() {
        _uiState.value = _uiState.value.copy(filterFavorites = !_uiState.value.filterFavorites)
        applyFilters()
    }

    private fun applyFilters() {
        val query    = _uiState.value.searchQuery.trim().lowercase()
        val onlyFavs = _uiState.value.filterFavorites

        val filtered = allPatients.filter { patient ->
            val matchesQuery = query.isEmpty() ||
                    patient.fullName.lowercase().contains(query) ||
                    patient.allergies.lowercase().contains(query)
            val matchesFav = !onlyFavs || patient.isFavorite
            matchesQuery && matchesFav
        }

        _uiState.value = _uiState.value.copy(
            patients   = filtered,
            totalCount = allPatients.size,
            isLoading  = false
        )
    }

    fun linkPatient(fullName: String, peerId: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName         = fullName.trim(),
                    peerId           = peerId.trim(),
                    allergies        = allergies.trim(),
                    avatarColorIndex = colorIndex,
                    lastSyncAt       = System.currentTimeMillis()
                )
                dbHelper.insertarPacienteClinico(patient, ownerPeerId)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName} (owner=$ownerPeerId)")
                loadPatients()
            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
            }
        }
    }

    fun toggleFavorite(patientId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.toggleFavoritoPaciente(patientId)
                loadPatients()
            } catch (e: Exception) {
                Log.e(TAG, "Error toggle favorito: ${e.message}")
            }
        }
    }

    fun removePatient(patientId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.eliminarPacienteClinico(patientId)
                Log.d(TAG, "Paciente eliminado: $patientId")
                loadPatients()
            } catch (e: Exception) {
                Log.e(TAG, "Error eliminando paciente: ${e.message}")
            }
        }
    }
}