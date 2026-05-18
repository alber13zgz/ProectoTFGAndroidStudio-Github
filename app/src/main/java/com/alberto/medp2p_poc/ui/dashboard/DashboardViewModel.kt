package com.alberto.medp2p_poc.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.data.model.UserSession
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import com.alberto.medp2p_poc.data.model.Patient
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.libp2p.protocol.circuit.CircuitStopProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ────────────────────────────────────────────────────────────────────
// JUSTIFICACION ARQUITECTONICA:
// DashboardViewModel gestiona el ciclo de vida del nodo libp2p de forma
// completamente invisible para la UI. Expone ConnectionStatus como
// sealed class — la UI nunca ve conceptos P2P.
//
// CORRECCIONES aplicadas:
//   1. SecIoSecureChannel → NoiseXXSecureChannel (compatible con relay JS libp2p v1.x)
//   2. muxers { add(StreamMuxerProtocol.getYamux()) } añadido explícitamente
//      para garantizar compatibilidad Yamux con el relay Node.js
//   3. retryConnection: secureChannels y muxers añadidos al nodo reconstruido
//   4. connectToRelay: logging detallado con stack trace completo para debug
//   5. RELAY_ADDRESS actualizado con PeerID real del relay universitario
// ────────────────────────────────────────────────────────────────────

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
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

    companion object {
        private const val TAG = "P2P_NETWORK"
        private const val RELAY_ADDRESS =
            "/ip4/155.210.71.101/tcp/4001/p2p/12D3KooWM4DFBfu7g8Dir5kspvsef862pEnemaCphC6TREVg3BBn"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
    }

    fun initialize(session: UserSession, privateKey: PrivKey? = null) {
        storedPrivateKey = privateKey
        _dashboard.value = _dashboard.value.copy(
            displayName = session.displayName,
            role = session.role
        )
        loadDashboardCounters()
        startP2PNode(privateKey)
    }

    // ══════════════════════════════════════════════════════════════
    // ══ ARRANQUE DEL NODO ═══════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    private fun startP2PNode(privateKey: PrivKey?) {
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )

            try {
                val privKey: PrivKey = privateKey
                    ?: generateKeyPair(KeyType.ED25519).first

                Log.d(TAG, "Construyendo nodo libp2p...")
                // ARQUITECTURA: Noise es el canal seguro estándar moderno de libp2p.
                // getYamux() fuerza el muxer explícitamente para garantizar compatibilidad
                // con el relay JS libp2p v1.x que también usa Yamux.
                val node = host {
                    identity {
                        factory = { privKey }
                    }
                    transports {
                        add(::TcpTransport)
                    }
                    secureChannels {
                        add(::NoiseXXSecureChannel)
                    }
                    muxers {
                        add(StreamMuxerProtocol.getYamux())
                    }
                    network {
                        listen("/ip4/0.0.0.0/tcp/0")
                    }
                    protocols {
                        add(CircuitStopProtocol.Binding(CircuitStopProtocol()))
                    }
                }

                node.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                activeHost = node
                Log.d(TAG, "Nodo arrancado. PeerId=${node.peerId}")

                connectToRelay(node)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error arrancando nodo: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error(
                        "No se pudo iniciar la conexion segura. " +
                                "La app funciona en modo local."
                    )
                )
            }
        }
    }

    /**
     * Intenta conectar al relay universitario.
     * Si falla, la app sigue funcionando en modo local.
     * El usuario ve "Sincronizacion no disponible", nunca un crash.
     */
    private fun connectToRelay(node: Host) {
        try {
            Log.d(TAG, "Conectando al relay: $RELAY_ADDRESS")
            val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
            val relayPeerId = PeerId.fromBase58(
                RELAY_ADDRESS.substringAfterLast("/")
            )

            node.network.connect(relayPeerId, relayMultiaddr)
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            Log.d(TAG, "✅ Conectado al relay universitario.")
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connected,
                lastSyncTimestamp = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            // Log detallado para debug: causa raíz + stack trace completo
            Log.e(TAG, "❌ Relay inalcanzable")
            Log.e(TAG, "  Tipo: ${e.javaClass.simpleName}")
            Log.e(TAG, "  Causa: ${e.cause?.javaClass?.simpleName} → ${e.cause?.message}")
            Log.e(TAG, "  Stack: ${e.stackTraceToString()}")
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Error(
                    "La sincronizacion remota no esta disponible. " +
                            "Tus datos locales siguen seguros."
                )
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ RETRY CON FEEDBACK VISUAL ═══════════════════════════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Reintento de conexion al relay.
     * 1. Pone Connecting inmediatamente (spinner visible)
     * 2. Si ya hay nodo activo, solo reintenta el relay
     * 3. Si no hay nodo, reconstruye todo desde cero con configuracion correcta
     * 4. Captura excepciones y muestra Error con mensaje claro
     */
    fun retryConnection() {
        if (_dashboard.value.connectionStatus is ConnectionStatus.Connecting) return

        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )

            val node = activeHost
            if (node != null) {
                Log.d(TAG, "Retry: nodo activo, reconectando al relay...")
                connectToRelay(node)
            } else {
                Log.d(TAG, "Retry: sin nodo, reconstruyendo...")
                try {
                    val privKey: PrivKey = storedPrivateKey
                        ?: generateKeyPair(KeyType.ED25519).first

                    // CORRECCIÓN: misma configuración que startP2PNode —
                    // Noise + Yamux explícito, sin SecIo
                    val newNode = host {
                        identity { factory = { privKey } }
                        transports { add(::TcpTransport) }
                        secureChannels { add(::NoiseXXSecureChannel) }
                        muxers { add(StreamMuxerProtocol.getYamux()) }
                        network { listen("/ip4/0.0.0.0/tcp/0") }
                        protocols { add(CircuitStopProtocol.Binding(CircuitStopProtocol())) }
                    }

                    newNode.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    activeHost = newNode
                    Log.d(TAG, "Nodo reconstruido. PeerId=${newNode.peerId}")

                    connectToRelay(newNode)

                } catch (e: Exception) {
                    Log.e("P2P_ERROR", "Retry fallido: ${e.stackTraceToString()}")
                    _dashboard.value = _dashboard.value.copy(
                        connectionStatus = ConnectionStatus.Error(
                            "No se pudo reconectar. Comprueba tu conexion WiFi."
                        )
                    )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ CONTADORES ══════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    private fun loadDashboardCounters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val patients = dbHelper.obtenerPacientesClinico()
                _dashboard.value = _dashboard.value.copy(
                    patientCount = patients.size
                )
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error cargando contadores: ${e.message}")
            }
        }
    }

    fun refreshCounters() {
        loadDashboardCounters()
    }

    /**
     * Vincula un nuevo paciente desde el Dashboard.
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
                Log.d(TAG, "✅ Paciente vinculado desde Dashboard: ${patient.fullName}")
                loadDashboardCounters()
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error vinculando paciente desde Dashboard: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "DashboardViewModel cleared. Deteniendo nodo.")
        activeHost?.stop()
        activeHost = null
    }

    /**
     * Detiene el nodo y resetea el estado del dashboard.
     * Se llama cuando el usuario cambia de cuenta desde AuthScreen.
     */
    fun shutdown() {
        activeHost?.stop()
        activeHost = null
        storedPrivateKey = null
        _dashboard.value = DashboardData()
        Log.d(TAG, "Dashboard shutdown completo.")
    }
}
