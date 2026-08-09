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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.ui.auth.AuthScreen
import com.alberto.medp2p_poc.ui.auth.AuthUiState
import com.alberto.medp2p_poc.ui.auth.AuthViewModel
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import com.alberto.medp2p_poc.ui.navigation.ClinicalAppNavigation

// ══════════════════════════════════════════════════════════════════════
// JUSTIFICACIÓN ARQUITECTÓNICA: ORQUESTADOR PURO
//
// Tras la refactorización, MainActivity tiene UNA sola responsabilidad:
// orquestar el ciclo de vida de la autenticación y la navegación.
//
// Todo el código de red (construcción del nodo libp2p, apertura de
// streams, pipeline de Netty, serialización JSON) ha sido extraído a
// P2PMessagingService. El envío de mensajes lo gestiona DashboardViewModel
// a través de sendRecord(). MainActivity no sabe nada de P2P.
//
// Comparativa antes/después:
//   Antes: 245 líneas, imports de Netty, libp2p, CompletableFuture, Json
//   Ahora: ~80 líneas, cero imports de red — solo UI y ciclo de vida
//
// Flujo de las dos fases:
//   FASE 1 (Auth):  AuthViewModel → AuthScreen
//   FASE 2 (App):   DashboardViewModel → ClinicalAppNavigation
// ══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val dashboardViewModel: DashboardViewModel by viewModels()

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var dbHelper: AppDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("P2P_NETWORK", "=== INICIANDO APLICACION MEDP2P ===")

        dbHelper = AppDatabaseHelper(this)

        // MulticastLock: necesario para que el socket P2P pueda recibir
        // paquetes multicast en redes WiFi con filtrado activado.
        // Sin este lock, Android descarta silenciosamente los paquetes
        // multicast entrantes en algunos routers corporativos y domésticos.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("medp2p-multicast-lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        setContent {
            MaterialTheme {
                val authState by authViewModel.uiState.collectAsStateWithLifecycle()
                var dashboardInitialized by remember { mutableStateOf(false) }

                // ── Gestionar transiciones de autenticación ────────────
                // LaunchedEffect(authState) se re-ejecuta cada vez que
                // authState cambia. dashboardInitialized evita inicializar
                // el dashboard más de una vez por sesión, y se resetea a
                // false cuando el usuario cierra sesión para que el
                // LaunchedEffect se re-ejecute en el siguiente login.
                LaunchedEffect(authState) {
                    when (authState) {
                        is AuthUiState.Authenticated -> {
                            if (!dashboardInitialized) {
                                val session = (authState as AuthUiState.Authenticated).session
                                val privateKey = withContext(Dispatchers.IO) {
                                    authViewModel.getKeyVault().retrievePrivateKey()
                                }
                                dashboardViewModel.initialize(session, privateKey)
                                dashboardInitialized = true
                                Log.i("P2P_NETWORK",
                                    "Dashboard inicializado: ${session.displayName} (${session.role})")
                            }
                        }
                        is AuthUiState.ShowRegistration,
                        is AuthUiState.ShowLogin -> {
                            if (dashboardInitialized) {
                                Log.i("P2P_NETWORK", "Sesion cerrada. Deteniendo nodo anterior.")
                                dashboardViewModel.shutdown()
                                dashboardInitialized = false
                            }
                        }
                        else -> { /* Processing, Error, Checking — sin acción */ }
                    }
                }

                // ── Transición animada Auth → App ──────────────────────
                AnimatedContent(
                    targetState = authState is AuthUiState.Authenticated && dashboardInitialized,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(600)) +
                                slideInVertically(
                                    animationSpec = tween(600),
                                    initialOffsetY = { it / 6 }
                                )).togetherWith(fadeOut(animationSpec = tween(300)))
                    },
                    label = "MainTransition"
                ) { isAuthenticated ->
                    if (!isAuthenticated) {
                        // ── FASE 1: Autenticación ──
                        AuthScreen(viewModel = authViewModel)
                    } else {
                        // ── FASE 2: App clínica ──
                        val session = (authState as AuthUiState.Authenticated).session
                        ClinicalAppNavigation(
                            userRole           = session.role,
                            userName           = session.displayName,
                            dashboardViewModel = dashboardViewModel,
                            onCerrarSesion     = { authViewModel.logout() }
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
        // El nodo libp2p ya se detiene en DashboardViewModel.onCleared().
        // Aquí solo cerramos la conexión a SQLite.
        dbHelper.close()
    }
}