package com.alberto.medp2p_poc.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import com.alberto.medp2p_poc.ui.dashboard.PantallaDashboardClinico
import com.alberto.medp2p_poc.ui.patients.PantallaPacientes
import com.alberto.medp2p_poc.ui.patients.PatientsViewModel
import com.alberto.medp2p_poc.ui.patients.detail.PantallaDetallePaciente
import com.alberto.medp2p_poc.ui.patients.detail.PatientDetailViewModel
import com.alberto.medp2p_poc.ui.patients.dashboard.PatientDashboardScreen

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACION ARQUITECTONICA:
// La navegacion ahora bifurca segun UserRole:
//
//   PROFESSIONAL → BottomBar con 2 tabs (Dashboard + Pacientes)
//                   + ruta de detalle sin bottom bar
//
//   PATIENT      → Sin BottomBar. Pantalla unica PatientDashboardScreen
//                   con tarjetas: Mi Perfil, Mis Medicos, Mi Medicacion
// ──────────────────────────────────────────────────────────────────────

private object NavColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val TextSecondary = Color(0xFF6B7280)
    val SurfaceWhite = Color(0xFFF8FAFE)
}

sealed class ProfessionalRoute(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : ProfessionalRoute("home", "Dashboard", Icons.Outlined.Home)
    object Patients : ProfessionalRoute("patients", "Pacientes", Icons.Outlined.People)
}

private const val ROUTE_PATIENT_DETAIL = "patient_detail/{peerId}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicalAppNavigation(
    userRole: UserRole,
    userName: String,
    dashboardViewModel: DashboardViewModel,
    onEnviarMensaje: (String, String) -> Unit,
    onCerrarSesion: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Hola, $userName", style = MaterialTheme.typography.titleMedium, color = NavColors.PrimaryBlue)
                },
                actions = {
                    IconButton(onClick = onCerrarSesion) {
                        Icon(
                            imageVector = Icons.Outlined.ExitToApp,
                            contentDescription = "Cerrar Sesión / Cambiar Cuenta",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NavColors.SurfaceWhite
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (userRole) {
                UserRole.PROFESSIONAL -> ProfessionalNavigation(
                    dashboardViewModel = dashboardViewModel,
                    onEnviarMensaje = onEnviarMensaje
                )
                UserRole.PATIENT -> PatientDashboardScreen(
                    dashboardViewModel = dashboardViewModel
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ NAVEGACION PROFESIONAL (2 TABS + DETALLE) ═══════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun ProfessionalNavigation(
    dashboardViewModel: DashboardViewModel,
    onEnviarMensaje: (String, String) -> Unit
) {
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf(
        ProfessionalRoute.Home.route,
        ProfessionalRoute.Patients.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                ProfessionalBottomBar(navController)
            }
        },
        containerColor = NavColors.SurfaceWhite
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = ProfessionalRoute.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(ProfessionalRoute.Home.route) {
                LaunchedEffect(Unit) { dashboardViewModel.refreshCounters() }
                PantallaDashboardClinico(viewModel = dashboardViewModel)
            }

            composable(ProfessionalRoute.Patients.route) {
                val patientsViewModel: PatientsViewModel = viewModel()
                PantallaPacientes(
                    viewModel = patientsViewModel,
                    onPacienteSeleccionado = { peerId ->
                        navController.navigate("patient_detail/$peerId")
                    }
                )
            }

            composable(
                route = ROUTE_PATIENT_DETAIL,
                arguments = listOf(
                    navArgument("peerId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
                val detailViewModel: PatientDetailViewModel = viewModel()
                PantallaDetallePaciente(
                    peerId = peerId,
                    viewModel = detailViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ BOTTOM BAR — SOLO 2 ITEMS ═══════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun ProfessionalBottomBar(navController: NavHostController) {
    val items = listOf(ProfessionalRoute.Home, ProfessionalRoute.Patients)

    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEach { screen ->
            val isSelected = currentRoute == screen.route

            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title
                    )
                },
                label = {
                    Text(
                        text = screen.title,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                selected = isSelected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NavColors.PrimaryBlue,
                    selectedTextColor = NavColors.PrimaryBlue,
                    unselectedIconColor = NavColors.TextSecondary,
                    unselectedTextColor = NavColors.TextSecondary,
                    indicatorColor = NavColors.PrimaryBlue.copy(alpha = 0.08f)
                )
            )
        }
    }
}