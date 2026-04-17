package com.alberto.medp2p_poc.ui.patients.dashboard

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.ui.dashboard.ConnectionStatus
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import com.alberto.medp2p_poc.ui.qr.generateQrBitmap

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
    val Purple = Color(0xFF7C3AED)
    val PurpleBg = Color(0xFFF3EEFE)
    val BlueBg = Color(0xFFEBF3FE)
}

@Composable
fun PatientDashboardScreen(
    dashboardViewModel: DashboardViewModel,
    onCerrarSesion: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {}
) {
    val data by dashboardViewModel.dashboard.collectAsStateWithLifecycle()
    var showQrDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PatDashColors.SurfaceWhite)
            .verticalScroll(rememberScrollState())
    ) {
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
            // ══ BOTON GRANDE: COMPARTIR MI ID ══
            Button(
                onClick = { showQrDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PatDashColors.PrimaryBlue),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Outlined.QrCode, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Compartir mi ID con un Medico", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // ══ SECCION 1: MI PERFIL ══
            SectionCard(Icons.Outlined.Person, "Mi Perfil", PatDashColors.BlueBg, PatDashColors.PrimaryBlue) {
                ProfileRow("Nombre", data.displayName.ifBlank { "Sin nombre" }, Icons.Outlined.Person)
                Spacer(Modifier.height(12.dp))
                ProfileRow("Alergias", "Sin alergias registradas", Icons.Outlined.Warning)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onNavigateToProfile,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = PatDashColors.PrimaryBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("Editar mi perfil", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PatDashColors.PrimaryBlue)
                }
            }

            // ══ SECCION 2: MIS MEDICOS ══
            SectionCard(Icons.Outlined.MedicalServices, "Mis Medicos", PatDashColors.AccentMintBg, PatDashColors.AccentMint) {
                EmptyStateBox(Icons.Outlined.MedicalServices, "Aun no tienes medicos vinculados", "Tu medico te vinculara escaneando tu codigo QR")
            }

            // ══ SECCION 3: MI MEDICACION ══
            SectionCard(Icons.Outlined.Medication, "Mi Medicacion", PatDashColors.PurpleBg, PatDashColors.Purple) {
                EmptyStateBox(Icons.Outlined.Medication, "Sin medicacion asignada", "Cuando tu medico te asigne una pauta, aparecera aqui")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ══ DIALOGO QR ══
    if (showQrDialog) {
        SharePatientQrDialog(
            peerId = dashboardViewModel.activeHost?.peerId?.toString() ?: "sin-nodo",
            onDismiss = { showQrDialog = false }
        )
    }
}

@Composable
private fun SharePatientQrDialog(peerId: String, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val qrBitmap = remember(peerId) { generateQrBitmap(peerId, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Tu codigo para el medico", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Muestra este codigo QR a tu medico para que te vincule", fontSize = 13.sp, color = PatDashColors.TextSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (qrBitmap != null) {
                    Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(4.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Image(qrBitmap.asImageBitmap(), "QR", Modifier.size(240.dp).padding(16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(peerId, fontSize = 10.sp, color = PatDashColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { clipboardManager.setText(AnnotatedString(peerId)) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar al portapapeles", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", fontWeight = FontWeight.Bold) } },
        shape = RoundedCornerShape(24.dp)
    )
}

// ── Componentes reutilizables (igual que antes) ──

@Composable
private fun PatientHeader(displayName: String, connectionStatus: ConnectionStatus, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(PatDashColors.PrimaryBlue, PatDashColors.PrimaryBlueDark))).padding(24.dp).padding(bottom = 40.dp)
    ) {
        Column {
            Text(saludoSegunHora(), fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f))
            Text(displayName.ifBlank { "Paciente" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            val (statusText, statusColor) = when (connectionStatus) {
                is ConnectionStatus.Connected -> "Conectado con tu equipo medico" to PatDashColors.AccentMint
                is ConnectionStatus.Connecting -> "Conectando..." to PatDashColors.WarningAmber
                is ConnectionStatus.Error -> "Sin conexion. Tus datos estan seguros." to PatDashColors.ErrorRed
                is ConnectionStatus.Disconnected -> "Sin conexion" to PatDashColors.WarningAmber
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Text(statusText, fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f))
                if (connectionStatus is ConnectionStatus.Error || connectionStatus is ConnectionStatus.Disconnected) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Text("Conectar", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, iconBg: Color, iconTint: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(4.dp), colors = CardDefaults.cardColors(containerColor = PatDashColors.CardWhite)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(iconBg), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = iconTint)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PatDashColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = PatDashColors.DividerLight, thickness = 1.dp)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = PatDashColors.TextSecondary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 12.sp, color = PatDashColors.TextSecondary)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = PatDashColors.TextPrimary)
        }
    }
}

@Composable
private fun EmptyStateBox(icon: ImageVector, message: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = PatDashColors.DividerLight)
        Spacer(Modifier.height(10.dp))
        Text(message, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = PatDashColors.TextSecondary)
        Text(hint, fontSize = 13.sp, color = PatDashColors.TextSecondary.copy(alpha = 0.7f), textAlign = TextAlign.Center, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp))
    }
}

private fun saludoSegunHora(): String {
    val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { hora < 7 -> "Buenas noches,"; hora < 13 -> "Buenos dias,"; hora < 20 -> "Buenas tardes,"; else -> "Buenas noches," }
}