package com.alberto.medp2p_poc.ui.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.circuit.CircuitStopProtocol
import io.libp2p.transport.tcp.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// Toda la lógica criptográfica y de red vive en el ViewModel, nunca en
// la Vista (Activity/Composable). La UI solo observa un StateFlow<UiState>
// inmutable. Esto cumple MVVM limpio + reglas de threading del TFG_RULES.
// ──────────────────────────────────────────────────────────────────────

/** Estados posibles de la pantalla de Onboarding. Sealed class = exhaustive when. */
sealed class OnboardingUiState {
    /** Estado inicial: esperando que el usuario pulse el botón. */
    object Idle : OnboardingUiState()

    /** Generando par de claves Ed25519 y arrancando el nodo libp2p. */
    object GeneratingIdentity : OnboardingUiState()

    /** Identidad generada con éxito. Contiene el PeerId resultante. */
    data class IdentityReady(val peerId: String, val host: Host) : OnboardingUiState()

    /** Algo falló durante la generación. Mensaje legible para el usuario. */
    data class Error(val userMessage: String, val technicalDetail: String) : OnboardingUiState()
}

class OnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Referencia al nodo libp2p arrancado, accesible para MainActivity. */
    var activeHost: Host? = null
        private set

    companion object {
        private const val TAG = "P2P_NETWORK"
        private const val RELAY_ADDRESS =
            "/ip4/155.210.71.101/tcp/4001/p2p/12D3KooWG1zfvMX5xqqhAurArDN3gPfTCiELtFRUffYfMW88KoxZ"
    }

    // ──────────────────────────────────────────────────────────────────
    // REGLA DE THREADING: viewModelScope.launch(Dispatchers.IO) para
    // toda operación criptográfica/red. withContext(Dispatchers.Main)
    // SOLO se necesitaría para UI, pero aquí actualizamos StateFlow
    // (thread-safe) así que no hace falta cambiar de dispatcher.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Genera la identidad criptográfica P2P del dispositivo.
     * Arranca un nodo libp2p con transporte TCP y protocolo Circuit Relay.
     *
     * @param alias El nombre introducido por el usuario (para logging).
     */
    fun generateP2PIdentity(alias: String) {
        // Evitar doble ejecución si ya está en marcha
        if (_uiState.value is OnboardingUiState.GeneratingIdentity) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = OnboardingUiState.GeneratingIdentity
            Log.d(TAG, "Iniciando generación de identidad para alias='$alias'...")

            try {
                // ── PASO 1: Construir y arrancar el nodo libp2p ──
                val node = host {
                    network {
                        listen("/ip4/0.0.0.0/tcp/0")
                        transports { add(::TcpTransport) }
                    }
                    protocols {
                        add(CircuitStopProtocol.Binding(CircuitStopProtocol()))
                    }
                }

                // .get() bloquea esta corrutina (en IO), nunca el hilo principal
                node.start().get()
                activeHost = node

                val peerId = node.peerId.toString()
                Log.d(TAG, "✅ Identidad generada. PeerId=$peerId")

                // ── PASO 2: Conexión al Relay de la Universidad ──
                try {
                    val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
                    val relayPeerId = PeerId.fromBase58(
                        RELAY_ADDRESS.substringAfterLast("/")
                    )
                    node.network.connect(relayPeerId, relayMultiaddr).get()
                    Log.d(TAG, "✅ Conectado al relay universitario.")
                } catch (relayError: Exception) {
                    // El relay puede estar caído, pero la identidad ya existe.
                    // No bloqueamos el onboarding por esto; lo logueamos.
                    Log.e(TAG, "⚠️ Relay inalcanzable (no crítico): ${relayError.message}")
                }

                // ── PASO 3: Emitir estado de éxito ──
                _uiState.value = OnboardingUiState.IdentityReady(peerId, node)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "❌ Fallo generando identidad: ${e.stackTraceToString()}")
                activeHost?.stop()
                activeHost = null

                _uiState.value = OnboardingUiState.Error(
                    userMessage = "No se pudo generar tu identidad P2P. Comprueba tu conexión.",
                    technicalDetail = e.message ?: "Error desconocido"
                )
            }
        }
    }

    /** Permite reintentar tras un error. */
    fun resetState() {
        _uiState.value = OnboardingUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        // El host se gestiona ahora desde MainActivity para su ciclo de vida completo
        Log.d(TAG, "OnboardingViewModel cleared.")
    }
}