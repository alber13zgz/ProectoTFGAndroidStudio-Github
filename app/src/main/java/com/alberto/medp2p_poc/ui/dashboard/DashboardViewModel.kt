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

// ══════════════════════════════════════════════════════════════════════
// JUSTIFICACIÓN ARQUITECTÓNICA: MVVM + SERVICE LAYER
//
// DashboardViewModel ya NO construye el nodo libp2p directamente.
// Toda esa responsabilidad se delega a P2PMessagingService.
//
// Separación de responsabilidades (Single Responsibility Principle):
//   • DashboardViewModel  → gestiona el ESTADO de la UI (ConnectionStatus,
//                           contadores, rol del usuario).
//   • P2PMessagingService → gestiona los PROTOCOLOS DE RED (construcción
//                           del nodo, receptor de mensajes, envío P2P).
//
// Esta separación permite:
//   1. Testear el ViewModel mockeando el servicio sin red real.
//   2. Reutilizar el servicio desde otros ViewModels en el futuro.
//   3. Mantener el ViewModel sin imports de Netty ni de jvm-libp2p DSL.
// ══════════════════════════════════════════════════════════════════════

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

    // ── Estado de UI ──────────────────────────────────────────────────
    private val _dashboard = MutableStateFlow(DashboardData())
    val dashboard: StateFlow<DashboardData> = _dashboard.asStateFlow()

    // ── Infraestructura P2P ───────────────────────────────────────────
    // activeHost es el nodo libp2p activo. El ViewModel es su propietario
    // del ciclo de vida: lo recibe de P2PMessagingService.start() y lo
    // detiene en onCleared(). El servicio NO lo detiene para evitar que
    // un shutdown() del servicio deje el ViewModel con una referencia
    // a un nodo detenido sin saberlo.
    var activeHost: Host? = null
        private set

    private var storedPrivateKey: PrivKey? = null
    private val dbHelper = AppDatabaseHelper(application)

    // ── Servicio de mensajería P2P ────────────────────────────────────
    // Se instancia en initialize() porque necesita el dbHelper ya listo.
    // lateinit es seguro aquí: initialize() siempre se llama antes de
    // cualquier operación que use messagingService (garantizado por el
    // flujo Auth → Dashboard de MainActivity).
    private lateinit var messagingService: P2PMessagingService

    // ── Bus de eventos de mensajes entrantes (expuesto a la UI) ──────
    // El ViewModel actúa como mediador: expone el SharedFlow del servicio
    // sin que la UI sepa que existe P2PMessagingService. Esto es el
    // patrón ViewModel-as-mediator del Clean Architecture de Android.
    // La propiedad es nullable hasta que initialize() se ejecute.
    val incomingMessages: SharedFlow<MedicalRecord>?
        get() = if (::messagingService.isInitialized) messagingService.incomingMessages else null

    companion object {
        private const val TAG = "P2P_NETWORK"
    }

    // ══════════════════════════════════════════════════════════════════
    // initialize(): punto de entrada único tras el login exitoso.
    // Llamado desde MainActivity después de que AuthViewModel confirma
    // la autenticación y recupera la clave privada del KeyVaultManager.
    // ══════════════════════════════════════════════════════════════════
    fun initialize(session: UserSession, privateKey: PrivKey? = null) {
        storedPrivateKey = privateKey
        _dashboard.value = _dashboard.value.copy(
            displayName = session.displayName,
            role        = session.role
        )

        // Instanciar el servicio ANTES de arrancar el nodo.
        messagingService = P2PMessagingService(getApplication(), dbHelper)

        loadDashboardCounters()
        startP2PNode(privateKey)
    }

    // ══════════════════════════════════════════════════════════════════
    // startP2PNode(): arranca el nodo delegando en el servicio.
    //
    // Antes: este método construía el host{} DSL directamente (30 líneas
    // de código de infraestructura mezcladas con lógica de estado de UI).
    // Ahora: una sola llamada a messagingService.start() — el ViewModel
    // solo gestiona el resultado (Connected / Error) para la UI.
    // ══════════════════════════════════════════════════════════════════
    private fun startP2PNode(privateKey: PrivKey?) {
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )
            try {
                val privKey: PrivKey = privateKey
                    ?: generateKeyPair(KeyType.ED25519).first

                // El servicio construye el nodo (con CircuitStopProtocol
                // + buildReceiverBinding() registrados), lo arranca y
                // conecta al relay. Devuelve el Host activo.
                val node = messagingService.start(privKey)
                activeHost = node

                Log.i(TAG, "✅ Nodo P2P activo. PeerId=${node.peerId}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus   = ConnectionStatus.Connected,
                    lastSyncTimestamp  = System.currentTimeMillis()
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

    // ══════════════════════════════════════════════════════════════════
    // retryConnection(): reintento manual desde la UI.
    //
    // Si el nodo ya existe (activeHost != null), solo reconecta al relay.
    // Si el nodo no existe (error en el arranque inicial), reconstruye
    // todo desde cero reutilizando la clave privada almacenada.
    // ══════════════════════════════════════════════════════════════════
    fun retryConnection() {
        if (_dashboard.value.connectionStatus is ConnectionStatus.Connecting) return

        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )
            try {
                val privKey: PrivKey = storedPrivateKey
                    ?: generateKeyPair(KeyType.ED25519).first

                // Detener el nodo anterior si existe antes de reconstruir
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
                    connectionStatus = ConnectionStatus.Error(
                        "No se pudo reconectar. Comprueba tu conexión WiFi."
                    )
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // sendRecord(): envía un MedicalRecord a un peer destino.
    //
    // Función pública que la UI (vía MainActivity o un futuro ViewModel
    // de chat) puede llamar. Delega completamente en el servicio, que
    // aplica el patrón Write-ahead + Outbox (sync_log).
    //
    // destinationCircuitAddr: dirección completa de Circuit Relay:
    //   /ip4/<relay>/tcp/4001/p2p/<relayId>/p2p-circuit/p2p/<destPeerId>
    // ══════════════════════════════════════════════════════════════════
    suspend fun sendRecord(destinationCircuitAddr: String, record: MedicalRecord) {
        val node = activeHost ?: run {
            Log.e(TAG, "[SEND] Intento de envío sin nodo activo. Ignorando.")
            return
        }
        messagingService.sendMedicalRecord(node, destinationCircuitAddr, record)
    }

    // ── Contadores del Dashboard ──────────────────────────────────────
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

    fun refreshCounters() {
        loadDashboardCounters()
    }

    fun linkPatient(fullName: String, peerId: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName         = fullName.trim(),
                    peerId           = peerId.trim(),
                    allergies        = allergies.trim(),
                    avatarColorIndex = colorIndex
                )
                dbHelper.insertarPacienteClinico(patient)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadDashboardCounters()
            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Ciclo de vida: onCleared() y shutdown()
    //
    // Orden de limpieza deliberado:
    //   1. messagingService.shutdown() → cancela el serviceScope
    //      (detiene corutinas de envío y recepción en curso).
    //   2. activeHost?.stop()          → cierra el socket TCP y libera
    //      los recursos del nodo libp2p.
    //
    // El servicio se apaga ANTES que el nodo para que las corutinas
    // del servicio no intenten escribir en un canal ya cerrado.
    // ══════════════════════════════════════════════════════════════════
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