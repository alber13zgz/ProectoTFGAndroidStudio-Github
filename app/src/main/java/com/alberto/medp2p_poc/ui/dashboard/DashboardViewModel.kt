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
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACION ARQUITECTONICA:
// DashboardViewModel gestiona el ciclo de vida del nodo libp2p de forma
// completamente invisible para la UI. Expone ConnectionStatus como
// sealed class — la UI nunca ve conceptos P2P.
//
// PROBLEMA ANTERIOR: retryConnection() paraba el nodo y relanzaba
// sin actualizar el estado a Connecting, y sin timeout en .get().
// El usuario no veia feedback visual y la UI quedaba congelada.
//
// SOLUCION: Ahora retryConnection() pone estado Connecting ANTES
// de tocar el nodo, usa .get(timeout) para no bloquear infinito,
// y captura excepciones con mensajes claros para el usuario.
// ──────────────────────────────────────────────────────────────────────

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
            "/ip4/155.210.71.101/tcp/4001/p2p/12D3KooWG1zfvMX5xqqhAurArDN3gPfTCiELtFRUffYfMW88KoxZ"
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
            // 1. Actualizar estado INMEDIATAMENTE para que la UI muestre spinner
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )

            try {
                // 2. Generar o reutilizar clave
                val privKey: PrivKey = privateKey
                    ?: generateKeyPair(KeyType.ED25519).first

                // 3. Construir Host
                Log.d(TAG, "Construyendo nodo libp2p...")
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
                    network {
                        listen("/ip4/0.0.0.0/tcp/0")
                    }
                }

                // 4. Arrancar con timeout
                node.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                activeHost = node
                Log.d(TAG, "Nodo arrancado. PeerId=${node.peerId}")

                // 5. Conectar al Relay con timeout
                connectToRelay(node)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error arrancando nodo: ${e.message}")
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
            Log.d(TAG, "Conectando al relay...")
            val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
            val relayPeerId = PeerId.fromBase58(
                RELAY_ADDRESS.substringAfterLast("/")
            )

            // Timeout para no bloquear infinito si el relay no responde
            node.network.connect(relayPeerId, relayMultiaddr)
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            Log.d(TAG, "Conectado al relay universitario.")
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connected,
                lastSyncTimestamp = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Relay inalcanzable: ${e.message}")
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
     *
     * ANTES: Fallaba en silencio, no actualizaba el estado.
     * AHORA:
     *   1. Pone Connecting inmediatamente (spinner visible)
     *   2. Si ya hay nodo activo, solo reintenta el relay
     *   3. Si no hay nodo, reconstruye todo desde cero
     *   4. Captura excepciones y muestra Error con mensaje claro
     */
    fun retryConnection() {
        // Evitar multiples retrys simultaneos
        if (_dashboard.value.connectionStatus is ConnectionStatus.Connecting) return

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Feedback INMEDIATO para la UI
            _dashboard.value = _dashboard.value.copy(
                connectionStatus = ConnectionStatus.Connecting
            )

            val node = activeHost
            if (node != null) {
                // Caso A: Nodo activo, solo reconectar al relay
                Log.d(TAG, "Retry: nodo activo, reconectando al relay...")
                connectToRelay(node)
            } else {
                // Caso B: No hay nodo, reconstruir todo
                Log.d(TAG, "Retry: sin nodo, reconstruyendo...")
                try {
                    val privKey: PrivKey = storedPrivateKey
                        ?: generateKeyPair(KeyType.ED25519).first

                    val newNode = host {
                        identity { factory = { privKey } }
                        transports { add(::TcpTransport) }
                        secureChannels { add(::NoiseXXSecureChannel) }
                        network { listen("/ip4/0.0.0.0/tcp/0") }
                    }

                    newNode.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    activeHost = newNode
                    Log.d(TAG, "Nodo reconstruido. PeerId=${newNode.peerId}")

                    connectToRelay(newNode)

                } catch (e: Exception) {
                    Log.e("P2P_ERROR", "Retry fallido: ${e.message}")
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