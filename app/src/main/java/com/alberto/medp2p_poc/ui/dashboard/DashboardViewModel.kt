package com.alberto.medp2p_poc.ui.dashboard

import android.app.Application
import android.content.Context
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
import java.io.File

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
    val lastLoginAt: Long? = null,
    val displayName: String = "",
    val photoUri: String = "",
    val role: UserRole = UserRole.PROFESSIONAL,
    // ownerPeerId: el peerId del usuario activo.
    // Todas las queries a DB lo usan como filtro (Row-Level Security).
    val ownerPeerId: String = ""
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

    // ownerPeerId del usuario activo — acceso directo para los ViewModels hijos
    val currentOwnerPeerId: String get() = _dashboard.value.ownerPeerId

    companion object {
        private const val TAG = "P2P_NETWORK"
        private const val RELAY_BASE =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWEBiChhAXXnZRPoM37aoawZbYQKp7WxqtC7LrfZFab4TV"
    }

    fun initialize(session: UserSession, privateKey: PrivKey? = null) {
        storedPrivateKey = privateKey
        _dashboard.value = _dashboard.value.copy(
            displayName  = session.displayName,
            role         = session.role,
            photoUri     = session.photoUri,
            ownerPeerId  = session.peerId,
            // FIX FALLO 1: mostrar lastLoginAt inmediatamente al entrar,
            // sin esperar a que el nodo P2P conecte al relay.
            lastLoginAt  = session.lastLoginAt
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
                val node = messagingService.start(privKey, _dashboard.value.ownerPeerId)
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
                val privKey = storedPrivateKey ?: generateKeyPair(KeyType.ED25519).first
                activeHost?.stop()
                activeHost = null
                val node = messagingService.start(privKey)
                activeHost = node
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus  = ConnectionStatus.Connected,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Retry fallido: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error("No se pudo reconectar.")
                )
            }
        }
    }

    suspend fun sendRecord(destinationCircuitAddr: String, record: MedicalRecord) {
        val node = activeHost ?: run {
            Log.e(TAG, "[SEND] Sin nodo activo.")
            return
        }
        messagingService.sendMedicalRecord(node, destinationCircuitAddr, record)
    }

    // ══════════════════════════════════════════════════════════════
    // loadDashboardCounters() — filtra por ownerPeerId (FIX FALLO 5)
    // ══════════════════════════════════════════════════════════════
    private fun loadDashboardCounters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val owner    = _dashboard.value.ownerPeerId
                val patients = dbHelper.obtenerPacientesClinico(owner)
                _dashboard.value = _dashboard.value.copy(patientCount = patients.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando contadores: ${e.message}")
            }
        }
    }

    fun refreshCounters() { loadDashboardCounters() }

    // ══════════════════════════════════════════════════════════════
    // linkPatient() — FIX FALLO 5 + FIX FALLO 3
    //
    // FIX FALLO 5: pasa ownerPeerId al INSERT para Row-Level Security.
    // FIX FALLO 3: tras el INSERT local, envía mensaje P2P LINK_DOCTOR
    //   al paciente para que aparezca en su sección "Mis Médicos".
    // ══════════════════════════════════════════════════════════════
    fun linkPatient(
        fullName: String,
        peerId: String,
        allergies: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val owner = _dashboard.value.ownerPeerId
            val myName = _dashboard.value.displayName
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName         = fullName.trim(),
                    peerId           = peerId.trim(),
                    allergies        = allergies.trim(),
                    avatarColorIndex = colorIndex,
                    lastSyncAt       = System.currentTimeMillis()
                )
                // INSERT con ownerPeerId del médico activo
                dbHelper.insertarPacienteClinico(patient, owner)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadDashboardCounters()

                // ── FIX FALLO 3: Envío P2P bidireccional LINK_DOCTOR ──
                // El médico notifica al paciente para que guarde al médico
                // en su tabla medico_vinculado. Si el paciente no está
                // online, quedará en sync_log como PENDING para reintento.
                val node = activeHost ?: return@launch
                val destAddr = "$RELAY_BASE/p2p-circuit/p2p/${peerId.trim()}"
                messagingService.sendLinkDoctorMessage(
                    host                  = node,
                    destinationCircuitAddr = destAddr,
                    doctorPeerId          = owner,
                    doctorName            = myName
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // updateProfile() — FIX FALLO 2
    //
    // Guarda nombre + foto en DB y actualiza el estado reactivo.
    // La foto se copia al directorio privado de la app antes de
    // guardar la ruta, evitando el problema de URIs efímeras de
    // PickVisualMedia que caducan al reiniciar la app.
    // ══════════════════════════════════════════════════════════════
    fun updateProfile(newName: String, newPhotoUri: String) {
        if (newName.isBlank()) return
        val owner = _dashboard.value.ownerPeerId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.actualizarPerfil(owner, newName.trim(), newPhotoUri)
                _dashboard.value = _dashboard.value.copy(
                    displayName = newName.trim(),
                    photoUri    = newPhotoUri
                )
                Log.i(TAG, "Perfil actualizado: $newName | foto=$newPhotoUri")
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando perfil: ${e.message}")
            }
        }
    }

    // Copia la imagen seleccionada al directorio privado y devuelve la ruta
    fun copyPhotoToPrivateDir(context: Context, sourceUriString: String): String {
        return try {
            val sourceUri = android.net.Uri.parse(sourceUriString)
            val destFile  = File(context.filesDir, "profile_${_dashboard.value.ownerPeerId}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copiando foto: ${e.message}")
            ""
        }
    }

    override fun onCleared() {
        super.onCleared()
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
    }
}