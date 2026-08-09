package com.alberto.medp2p_poc.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Patient
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.data.model.UserSession
import com.alberto.medp2p_poc.data.p2p.P2PMessagingService
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    data class Error(val hint: String) : ConnectionStatus()
}

data class DashboardData(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val patientCount: Int = 0,
    val pendingNotifications: Int = 0,
    val lastSyncTimestamp: Long? = null,
    val displayName: String = "",
    val role: UserRole = UserRole.PROFESSIONAL
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _dashboard = MutableStateFlow(DashboardData())
    val dashboard: StateFlow<DashboardData> = _dashboard.asStateFlow()

    var activeHost: Host? = null
        private set

    private var storedPrivateKey: PrivKey? = null
    private val dbHelper = AppDatabaseHelper(application)

    private lateinit var messagingService: P2PMessagingService

    val incomingMessages: SharedFlow<MedicalRecord>?
        get() = if (::messagingService.isInitialized) messagingService.incomingMessages else null

    companion object {
        private const val TAG = "P2P_NETWORK"
    }

    fun initialize(session: UserSession, privateKey: PrivKey? = null) {
        storedPrivateKey = privateKey
        _dashboard.value = _dashboard.value.copy(
            displayName = session.displayName,
            role        = session.role
        )
        messagingService = P2PMessagingService(getApplication(), dbHelper)
        loadDashboardCounters()
        startP2PNode(privateKey)
    }

    private fun startP2PNode(privateKey: PrivKey?) {
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Connecting)
            try {
                val privKey: PrivKey = privateKey ?: generateKeyPair(KeyType.ED25519).first
                val node = messagingService.start(privKey)
                activeHost = node
                Log.i(TAG, "✅ Nodo P2P activo. PeerId=${node.peerId}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus  = ConnectionStatus.Connected,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error arrancando nodo: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error(
                        "No se pudo iniciar la conexión segura. La app funciona en modo local."
                    )
                )
            }
        }
    }

    fun retryConnection() {
        if (_dashboard.value.connectionStatus is ConnectionStatus.Connecting) return
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Connecting)
            try {
                val privKey: PrivKey = storedPrivateKey ?: generateKeyPair(KeyType.ED25519).first
                activeHost?.stop()
                activeHost = null
                val node = messagingService.start(privKey)
                activeHost = node
                Log.i(TAG, "✅ Reconexión exitosa. PeerId=${node.peerId}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus  = ConnectionStatus.Connected,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Retry fallido: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error("No se pudo reconectar. Comprueba tu conexión WiFi.")
                )
            }
        }
    }

    suspend fun sendRecord(destinationCircuitAddr: String, record: MedicalRecord) {
        val node = activeHost ?: run {
            Log.e(TAG, "[SEND] Intento de envío sin nodo activo. Ignorando.")
            return
        }
        messagingService.sendMedicalRecord(node, destinationCircuitAddr, record)
    }

    private fun loadDashboardCounters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val patients = dbHelper.obtenerPacientesClinico()
                _dashboard.value = _dashboard.value.copy(patientCount = patients.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando contadores: ${e.message}")
            }
        }
    }

    fun refreshCounters() { loadDashboardCounters() }

    // ══════════════════════════════════════════════════════════════
    // linkPatient(): vincula un paciente al directorio clínico.
    //
    // FIX: se añade lastSyncAt = System.currentTimeMillis() para que
    // la pantalla de detalle muestre la fecha real de vinculación
    // en lugar de "Pendiente de primera sincronización".
    // ══════════════════════════════════════════════════════════════
    fun linkPatient(fullName: String, peerId: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName         = fullName.trim(),
                    peerId           = peerId.trim(),
                    allergies        = allergies.trim(),
                    avatarColorIndex = colorIndex,
                    lastSyncAt       = System.currentTimeMillis()  // ← FIX
                )
                dbHelper.insertarPacienteClinico(patient)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadDashboardCounters()
            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // updateDisplayName(): guarda el nuevo nombre en auth_profile
    // y actualiza el estado en memoria para que el avatar del
    // TopAppBar se refresque inmediatamente sin reiniciar sesión.
    // ══════════════════════════════════════════════════════════════
    fun updateDisplayName(newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.actualizarNombreDisplay(newName.trim())
                _dashboard.value = _dashboard.value.copy(displayName = newName.trim())
                Log.i(TAG, "Nombre actualizado: ${newName.trim()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando nombre: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "DashboardViewModel cleared. Deteniendo nodo.")
        if (::messagingService.isInitialized) messagingService.shutdown()
        activeHost?.stop()
        activeHost = null
    }

    fun shutdown() {
        if (::messagingService.isInitialized) messagingService.shutdown()
        activeHost?.stop()
        activeHost = null
        storedPrivateKey = null
        _dashboard.value = DashboardData()
        Log.d(TAG, "Dashboard shutdown completo.")
    }
}