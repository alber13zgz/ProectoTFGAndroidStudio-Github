package com.alberto.medp2p_poc.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import com.alberto.medp2p_poc.ui.dashboard.PantallaDashboardClinico
import com.alberto.medp2p_poc.ui.patients.PantallaPacientes
import com.alberto.medp2p_poc.ui.patients.PatientsViewModel
import com.alberto.medp2p_poc.ui.patients.dashboard.PatientDashboardScreen
import com.alberto.medp2p_poc.ui.patients.detail.PantallaDetallePaciente
import com.alberto.medp2p_poc.ui.patients.detail.PatientDetailViewModel
import com.alberto.medp2p_poc.ui.profile.ProfileScreen

private object NavColors {
    val PrimaryBlue     = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val TextSecondary   = Color(0xFF6B7280)
    val SurfaceWhite    = Color(0xFFF8FAFE)
}

sealed class ProfessionalRoute(
    val route: String, val title: String, val icon: ImageVector
) {
    object Home     : ProfessionalRoute("home",     "Dashboard", Icons.Outlined.Home)
    object Patients : ProfessionalRoute("patients", "Pacientes", Icons.Outlined.People)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicalAppNavigation(
    userRole: UserRole,
    userName: String,
    dashboardViewModel: DashboardViewModel,
    onCerrarSesion: () -> Unit
) {
    val navController = rememberNavController()
    val initials = userName.split(" ").take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Hola, $userName",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavColors.PrimaryBlue)
                },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp).size(38.dp)
                            .clip(CircleShape).background(NavColors.PrimaryBlue)
                            .clickable {
                                navController.navigate("profile_edit") { launchSingleTop = true }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = initials.ifBlank { "?" }, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onCerrarSesion) {
                        Icon(imageVector = Icons.Outlined.ExitToApp,
                            contentDescription = "Cerrar Sesion",
                            tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavColors.SurfaceWhite)
            )
        },
        containerColor = NavColors.SurfaceWhite
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = if (userRole == UserRole.PROFESSIONAL) "home" else "patient_home",
            modifier         = Modifier.padding(innerPadding)
        ) {
            // ══════════════════════════════════════════
            // ══ RUTAS PROFESIONAL ════════════════════
            // ══════════════════════════════════════════
            composable("home") {
                LaunchedEffect(Unit) { dashboardViewModel.refreshCounters() }
                PantallaDashboardClinico(
                    viewModel            = dashboardViewModel,
                    onNavigateToPatients = {
                        navController.navigate("patients") { launchSingleTop = true }
                    },
                    onNavigateToProfile  = {
                        navController.navigate("profile_edit") { launchSingleTop = true }
                    }
                )
            }

            composable("patients") {
                val patientsViewModel: PatientsViewModel = viewModel()
                PantallaPacientes(
                    viewModel              = patientsViewModel,
                    onPacienteSeleccionado = { peerId ->
                        navController.navigate("patient_detail/$peerId")
                    }
                )
            }

            composable(
                route     = "patient_detail/{peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType })
            ) { backStackEntry ->
                val peerId        = backStackEntry.arguments?.getString("peerId") ?: ""
                val detailViewModel: PatientDetailViewModel = viewModel()

                // Activar recepción P2P en tiempo real para este paciente.
                // LaunchedEffect(peerId) garantiza una sola corutina por paciente.
                LaunchedEffect(peerId) {
                    detailViewModel.observeIncomingMessages(dashboardViewModel, peerId)
                }

                PantallaDetallePaciente(
                    peerId             = peerId,
                    viewModel          = detailViewModel,
                    dashboardViewModel = dashboardViewModel,  // ← para envío P2P
                    onBack             = { navController.popBackStack() }
                )
            }

            // ══════════════════════════════════════════
            // ══ RUTA PACIENTE ════════════════════════
            // ══════════════════════════════════════════
            composable("patient_home") {
                PatientDashboardScreen(
                    dashboardViewModel  = dashboardViewModel,
                    onCerrarSesion      = onCerrarSesion,
                    onNavigateToProfile = {
                        navController.navigate("profile_edit") { launchSingleTop = true }
                    }
                )
            }

            // ══════════════════════════════════════════
            // ══ PERFIL (AMBOS ROLES) ═════════════════
            // ══════════════════════════════════════════
            composable("profile_edit") {
                ProfileScreen(
                    dashboardViewModel = dashboardViewModel,
                    onBack             = { navController.popBackStack() }
                )
            }
        }
    }
}