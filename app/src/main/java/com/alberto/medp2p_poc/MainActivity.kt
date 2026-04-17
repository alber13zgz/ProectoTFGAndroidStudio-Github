package com.alberto.medp2p_poc

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.ui.auth.AuthScreen
import com.alberto.medp2p_poc.ui.auth.AuthUiState
import com.alberto.medp2p_poc.ui.auth.AuthViewModel
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import com.alberto.medp2p_poc.ui.navigation.ClinicalAppNavigation
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.util.CharsetUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CompletableFuture

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACION ARQUITECTONICA:
// MainActivity es un orquestador ultra-ligero con DOS fases:
//
//   FASE 1 (Auth):  AuthViewModel -> AuthScreen
//   FASE 2 (App):   DashboardViewModel -> ClinicalAppNavigation
//
// La Activity pasa el UserRole a ClinicalAppNavigation, que decide
// internamente si mostrar la vista de Profesional (2 tabs) o la
// vista de Paciente (pantalla unica PatientDashboardScreen).
//
// dashboardInitialized se resetea a false cuando el authState vuelve
// a un estado no-autenticado (cambio de cuenta), garantizando que
// el LaunchedEffect se re-ejecute para la nueva sesion.
// ──────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var dbHelper: AppDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("P2P_NETWORK", "=== INICIANDO APLICACION MEDP2P ===")

        dbHelper = AppDatabaseHelper(this)

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("medp2p-multicast-lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        setContent {
            MaterialTheme {
                val authState by authViewModel.uiState.collectAsStateWithLifecycle()
                var dashboardInitialized by remember { mutableStateOf(false) }

                // ── Gestionar transiciones de autenticacion ──
                LaunchedEffect(authState) {
                    when (authState) {
                        is AuthUiState.Authenticated -> {
                            if (!dashboardInitialized) {
                                val session =
                                    (authState as AuthUiState.Authenticated).session

                                val privateKey = withContext(Dispatchers.IO) {
                                    authViewModel.getKeyVault().retrievePrivateKey()
                                }

                                dashboardViewModel.initialize(session, privateKey)
                                dashboardInitialized = true

                                Log.i("P2P_NETWORK",
                                    "Dashboard inicializado: " +
                                            "${session.displayName} (${session.role})")
                            }
                        }

                        is AuthUiState.ShowRegistration,
                        is AuthUiState.ShowLogin -> {
                            if (dashboardInitialized) {
                                Log.i("P2P_NETWORK",
                                    "Sesion cerrada. Deteniendo nodo anterior.")
                                dashboardViewModel.shutdown()
                                dashboardInitialized = false
                            }
                        }

                        else -> { /* Processing, Error, Checking — no hacer nada */ }
                    }
                }

                // ── Transicion animada Auth -> App ──
                AnimatedContent(
                    targetState = authState is AuthUiState.Authenticated
                            && dashboardInitialized,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(600)) +
                                slideInVertically(
                                    animationSpec = tween(600),
                                    initialOffsetY = { it / 6 }
                                ))
                            .togetherWith(fadeOut(animationSpec = tween(300)))
                    },
                    label = "MainTransition"
                ) { isAuthenticated ->
                    if (!isAuthenticated) {
                        // ── FASE 1: Autenticacion ──
                        AuthScreen(viewModel = authViewModel)
                    } else {
                        // ── FASE 2: App clinica ──
                        val session =
                            (authState as AuthUiState.Authenticated).session

                        ClinicalAppNavigation(
                            userRole = session.role,
                            userName = session.displayName,
                            dashboardViewModel = dashboardViewModel,
                            onEnviarMensaje = { destino, mensaje ->
                                enviarMensajeP2PJSON(destino, mensaje)
                            },
                            onCerrarSesion = {
                                authViewModel.logout()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("P2P_NETWORK", "=== CERRANDO APLICACION MEDP2P ===")
        multicastLock?.release()
        dashboardViewModel.activeHost?.stop()
        dbHelper.close()
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MOTOR DE ENVIO P2P ══════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    private fun enviarMensajeP2PJSON(destino: String, mensajeTexto: String) {
        val nodo = dashboardViewModel.activeHost ?: run {
            Log.e("P2P_ERROR", "Intento de envio sin nodo activo.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val peerIdDestino = destino.substringAfterLast("/")

                val nuevoRegistro = MedicalRecord(
                    id = UUID.randomUUID().toString(),
                    patientId = peerIdDestino,
                    text = mensajeTexto,
                    timestamp = System.currentTimeMillis(),
                    isMine = true,
                    senderAlias = nodo.peerId.toString().take(8)
                )

                dbHelper.guardarRegistroMedico(nuevoRegistro)

                val jsonString = Json.encodeToString(nuevoRegistro)
                Log.i("P2P_NETWORK", "[EMISOR] >>> JSON: $jsonString")

                val multiaddrDestino = Multiaddr(destino)
                val peerIdObj = PeerId.fromBase58(peerIdDestino)

                val protocoloSalida = object : ProtocolBinding<Unit> {
                    override val protocolDescriptor =
                        ProtocolDescriptor("/medp2p/saludo/1.0.0")

                    override fun initChannel(
                        ch: P2PChannel,
                        selectedProtocol: String
                    ): CompletableFuture<Unit> {
                        ch.pushHandler(LineBasedFrameDecoder(4096))
                        ch.pushHandler(StringDecoder(CharsetUtil.UTF_8))
                        ch.pushHandler(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(
                                ctx: ChannelHandlerContext, msg: Any
                            ) {
                                Log.i("P2P_NETWORK",
                                    "[EMISOR] ACK: '${msg as String}'")
                                ctx.close()
                            }
                            override fun exceptionCaught(
                                ctx: ChannelHandlerContext, cause: Throwable
                            ) {
                                Log.e("P2P_ERROR",
                                    "[EMISOR] Error: ${cause.message}")
                                ctx.close()
                            }
                        })
                        return CompletableFuture.completedFuture(Unit)
                    }
                }

                val stream = protocoloSalida.dial(
                    nodo, peerIdObj, multiaddrDestino
                ).stream.get()

                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun handlerAdded(ctx: ChannelHandlerContext) {
                        ctx.writeAndFlush(
                            Unpooled.copiedBuffer(
                                "$jsonString\n", CharsetUtil.UTF_8
                            )
                        )
                        Log.i("P2P_NETWORK", "[EMISOR] Datos enviados.")
                    }
                })
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "[EMISOR] Error: ${e.message}")
            }
        }
    }
}