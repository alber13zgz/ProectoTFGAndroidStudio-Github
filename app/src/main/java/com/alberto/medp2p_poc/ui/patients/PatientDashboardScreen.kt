package com.alberto.medp2p_poc.ui.patients.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.ui.dashboard.ConnectionStatus
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACION ARQUITECTONICA:
// PatientDashboardScreen es la pantalla UNICA del rol PATIENT.
// Diseno pensado para personas mayores: tipografia grande, tarjetas
// espaciadas, iconos claros, colores de alto contraste. Sin barra
// de navegacion inferior — todo visible en scroll vertical.
//
// 3 secciones:
//   1. Mi Perfil: nombre, alergias, boton editar
//   2. Mis Medicos: lista de profesionales vinculados
//   3. Mi Medicacion: pautas activas con proximas tomas
//
// Los datos se obtienen del DashboardViewModel (ya inyectado) y
// en futuro del PatientsViewModel. Ahora muestra placeholders
// mientras se construye la capa de datos inversa (paciente→medico).
// ──────────────────────────────────────────────────────────────────────

private object PatDashColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val AccentMint = Color(0xFF00C9A7)
    val AccentMintBg = Color(0xFFE6FAF5)
    val SurfaceWhite = Color(0xFFF8FAFE)
    val CardWhite = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1C2B)
    val TextSecondary = Color(0xFF6B7280)
    val DividerLight = Color(0xFFE8EDF2)
    val ErrorRed = Color(0xFFDC3545)
    val WarningAmber = Color(0xFFF59E0B)
    val WarningAmberBg = Color(0xFFFFF8E1)
    val Purple = Color(0xFF7C3AED)
    val PurpleBg = Color(0xFFF3EEFE)
    val BlueBg = Color(0xFFEBF3FE)
}

@Composable
fun PatientDashboardScreen(dashboardViewModel: DashboardViewModel) {
    val data by dashboardViewModel.dashboard.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PatDashColors.SurfaceWhite)
            .verticalScroll(rememberScrollState())
    ) {
        // ══════════════════════════════════════
        // ══ CABECERA ═════════════════════════
        // ══════════════════════════════════════
        PatientHeader(
            displayName = data.displayName,
            connectionStatus = data.connectionStatus,
            onRetry = { dashboardViewModel.retryConnection() }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .offset(y = (-20).dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ══════════════════════════════════════
            // ══ SECCION 1: MI PERFIL ═════════════
            // ══════════════════════════════════════
            SectionCard(
                icon = Icons.Outlined.Person,
                title = "Mi Perfil",
                iconBg = PatDashColors.BlueBg,
                iconTint = PatDashColors.PrimaryBlue
            ) {
                ProfileContent(displayName = data.displayName)
            }

            // ══════════════════════════════════════
            // ══ SECCION 2: MIS MEDICOS ═══════════
            // ══════════════════════════════════════
            SectionCard(
                icon = Icons.Outlined.MedicalServices,
                title = "Mis Medicos",
                iconBg = PatDashColors.AccentMintBg,
                iconTint = PatDashColors.AccentMint
            ) {
                DoctorsContent()
            }

            // ══════════════════════════════════════
            // ══ SECCION 3: MI MEDICACION ═════════
            // ══════════════════════════════════════
            SectionCard(
                icon = Icons.Outlined.Medication,
                title = "Mi Medicacion",
                iconBg = PatDashColors.PurpleBg,
                iconTint = PatDashColors.Purple
            ) {
                MedicationContent()
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ CABECERA DEL PACIENTE ═══════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun PatientHeader(
    displayName: String,
    connectionStatus: ConnectionStatus,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(PatDashColors.PrimaryBlue, PatDashColors.PrimaryBlueDark)
                )
            )
            .padding(24.dp)
            .padding(bottom = 40.dp)
    ) {
        Column {
            // ── Saludo grande (accesible) ──
            Text(
                text = saludoSegunHora(),
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = displayName.ifBlank { "Paciente" },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))

            // ── Estado de conexion (lenguaje simple) ──
            val (statusText, statusColor) = when (connectionStatus) {
                is ConnectionStatus.Connected ->
                    "Conectado con tu equipo medico" to PatDashColors.AccentMint
                is ConnectionStatus.Connecting ->
                    "Conectando..." to PatDashColors.WarningAmber
                is ConnectionStatus.Error ->
                    "Sin conexion. Tus datos estan seguros." to PatDashColors.ErrorRed
                is ConnectionStatus.Disconnected ->
                    "Sin conexion" to PatDashColors.WarningAmber
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
                if (connectionStatus is ConnectionStatus.Error ||
                    connectionStatus is ConnectionStatus.Disconnected
                ) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Conectar",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE SECCION REUTILIZABLE ═════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    iconBg: Color,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = PatDashColors.CardWhite)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // ── Cabecera de la seccion ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PatDashColors.TextPrimary
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = PatDashColors.DividerLight, thickness = 1.dp)
            Spacer(Modifier.height(16.dp))

            // ── Contenido especifico ──
            content()
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ SECCION 1: MI PERFIL ════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun ProfileContent(displayName: String) {
    // ── Nombre ──
    ProfileRow(
        label = "Nombre",
        value = displayName.ifBlank { "Sin nombre" },
        icon = Icons.Outlined.Person
    )

    Spacer(Modifier.height(12.dp))

    // ── Alergias (placeholder — en futuro se lee de DB) ──
    ProfileRow(
        label = "Alergias",
        value = "Sin alergias registradas",
        icon = Icons.Outlined.Warning
    )

    Spacer(Modifier.height(16.dp))

    // ── Boton editar (grande, accesible) ──
    OutlinedButton(
        onClick = { /* TODO: Dialogo de edicion */ },
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            width = 1.5.dp
        )
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = PatDashColors.PrimaryBlue
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Editar mi perfil",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = PatDashColors.PrimaryBlue
        )
    }
}

@Composable
private fun ProfileRow(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = PatDashColors.TextSecondary
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = label,
                fontSize = 12.sp,
                color = PatDashColors.TextSecondary
            )
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PatDashColors.TextPrimary
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ SECCION 2: MIS MEDICOS ══════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun DoctorsContent() {
    // TODO: En futuro, se carga desde DB la lista de profesionales
    // vinculados a este paciente. Por ahora placeholder accesible.

    EmptyStateBox(
        icon = Icons.Outlined.MedicalServices,
        message = "Aun no tienes medicos vinculados",
        hint = "Tu medico te vinculara en la consulta escaneando tu codigo"
    )
}

// ══════════════════════════════════════════════════════════════
// ══ SECCION 3: MI MEDICACION ════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun MedicationContent() {
    // TODO: En futuro, se cargan las pautas medicas asignadas
    // al peerId de este paciente. Por ahora placeholder accesible.

    EmptyStateBox(
        icon = Icons.Outlined.Medication,
        message = "Sin medicacion asignada",
        hint = "Cuando tu medico te asigne una pauta, aparecera aqui con horarios y recordatorios"
    )
}

// ══════════════════════════════════════════════════════════════
// ══ COMPONENTES COMPARTIDOS ═════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun EmptyStateBox(icon: ImageVector, message: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = PatDashColors.DividerLight
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = PatDashColors.TextSecondary
        )
        Text(
            text = hint,
            fontSize = 13.sp,
            color = PatDashColors.TextSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp)
        )
    }
}

private fun saludoSegunHora(): String {
    val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hora < 7 -> "Buenas noches,"
        hora < 13 -> "Buenos dias,"
        hora < 20 -> "Buenas tardes,"
        else -> "Buenas noches,"
    }
}