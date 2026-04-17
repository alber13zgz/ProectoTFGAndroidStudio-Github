package com.alberto.medp2p_poc.ui.dashboard

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.UserRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PantallaDashboardClinico es 100% declarativo. Lee DashboardData del
// ViewModel y renderiza tarjetas de resumen. La conexión P2P se muestra
// como "Sincronización activa/pendiente" — el médico jamás ve palabras
// como Relay, PeerId o Multiaddr. Los colores y la estructura visual
// siguen Material Design 3 con la paleta clínica azul/menta/blanco.
// ──────────────────────────────────────────────────────────────────────

// ── Paleta clínica compartida ──
private object DashColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val PrimaryBlueLight = Color(0xFF4A90D9)
    val AccentMint = Color(0xFF00C9A7)
    val AccentMintBg = Color(0xFFE6FAF5)
    val WarningAmber = Color(0xFFF59E0B)
    val WarningAmberBg = Color(0xFFFFF8E1)
    val ErrorRed = Color(0xFFDC3545)
    val ErrorRedBg = Color(0xFFFDE8EA)
    val SurfaceWhite = Color(0xFFF8FAFE)
    val CardWhite = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1C2B)
    val TextSecondary = Color(0xFF6B7280)
    val DividerLight = Color(0xFFE8EDF2)
    val CountCardBlue = Color(0xFFEBF3FE)
    val CountCardPurple = Color(0xFFF3EEFE)
    val Purple = Color(0xFF7C3AED)
}

@Composable
fun PantallaDashboardClinico(viewModel: DashboardViewModel) {
    val data by viewModel.dashboard.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.SurfaceWhite)
            .verticalScroll(rememberScrollState())
    ) {
        // ══════════════════════════════════════════
        // ══ CABECERA CON GRADIENTE ═══════════════
        // ══════════════════════════════════════════
        DashboardHeader(
            displayName = data.displayName,
            role = data.role
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .offset(y = (-24).dp) // Overlap sobre la cabecera
        ) {
            // ══════════════════════════════════════════
            // ══ TARJETA DE ESTADO DE SINCRONIZACIÓN ══
            // ══════════════════════════════════════════
            SyncStatusCard(
                connectionStatus = data.connectionStatus,
                lastSyncTimestamp = data.lastSyncTimestamp,
                onRetry = { viewModel.retryConnection() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ══════════════════════════════════════════
            // ══ TARJETAS DE MÉTRICAS ═════════════════
            // ══════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MetricCard(
                    icon = Icons.Outlined.People,
                    label = "Pacientes\nactivos",
                    value = "${data.patientCount}",
                    backgroundColor = DashColors.CountCardBlue,
                    iconTint = DashColors.PrimaryBlue,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    icon = Icons.Outlined.Notifications,
                    label = "Alertas\npendientes",
                    value = "${data.pendingNotifications}",
                    backgroundColor = DashColors.CountCardPurple,
                    iconTint = DashColors.Purple,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ══════════════════════════════════════════
            // ══ ACCIONES RÁPIDAS ═════════════════════
            // ══════════════════════════════════════════
            Text(
                text = "Acciones rápidas",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DashColors.TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            QuickActionCard(
                icon = Icons.Outlined.PersonAdd,
                title = "Vincular paciente",
                subtitle = "Escanea o comparte un código de vinculación",
                accentColor = DashColors.PrimaryBlue
            )

            Spacer(modifier = Modifier.height(10.dp))

            QuickActionCard(
                icon = Icons.Outlined.MedicalServices,
                title = "Nueva pauta médica",
                subtitle = "Añade medicación a un paciente vinculado",
                accentColor = DashColors.AccentMint
            )

            Spacer(modifier = Modifier.height(10.dp))

            val context = LocalContext.current
            QuickActionCard(
                icon = Icons.Outlined.Share,
                title = "Compartir mi código",
                subtitle = "Envía tu identificador a otro profesional",
                accentColor = DashColors.Purple,
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Vinculación MedP2P")
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir código"))
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ══════════════════════════════════════════
            // ══ SECCIÓN DE ACTIVIDAD RECIENTE ════════
            // ══════════════════════════════════════════
            Text(
                text = "Actividad reciente",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DashColors.TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            RecentActivityPlaceholder()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ CABECERA CON SALUDO Y GRADIENTE ═════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun DashboardHeader(displayName: String, role: UserRole) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DashColors.PrimaryBlue,
                        DashColors.PrimaryBlueDark
                    )
                )
            )
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = saludoSegunHora(),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Text(
                    text = displayName.ifBlank { "Profesional" },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = role.displayLabel,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // ── Avatar ──
            val initials = displayName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials.ifBlank { "?" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════���═══════════
// ══ TARJETA DE ESTADO DE SINCRONIZACIÓN ═════════════════════
// ══════════════════════════════════════════════════════════════

// ──────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// Esta tarjeta traduce ConnectionStatus (que internamente significa
// "relay conectado/desconectado") a lenguaje clínico comprensible.
// "Sincronización activa" = relay OK. "Pendiente" = conectando.
// "No disponible" = error. Nunca aparecen palabras técnicas.
// ──────────────────────────────────────────────────────────────

@Composable
private fun SyncStatusCard(
    connectionStatus: ConnectionStatus,
    lastSyncTimestamp: Long?,
    onRetry: () -> Unit
) {
    val (backgroundColor, iconTint, icon, title, subtitle) = when (connectionStatus) {
        is ConnectionStatus.Connected -> SyncCardData(
            bg = DashColors.AccentMintBg,
            tint = DashColors.AccentMint,
            icon = Icons.Outlined.CloudDone,
            title = "Sincronización activa",
            subtitle = lastSyncTimestamp?.let {
                "Última: ${formatTimestamp(it)}"
            } ?: "Datos actualizados"
        )
        is ConnectionStatus.Connecting -> SyncCardData(
            bg = DashColors.WarningAmberBg,
            tint = DashColors.WarningAmber,
            icon = Icons.Outlined.CloudSync,
            title = "Conectando…",
            subtitle = "Estableciendo canal seguro"
        )
        is ConnectionStatus.Error -> SyncCardData(
            bg = DashColors.ErrorRedBg,
            tint = DashColors.ErrorRed,
            icon = Icons.Outlined.CloudOff,
            title = "Sincronización no disponible",
            subtitle = connectionStatus.hint
        )
        is ConnectionStatus.Disconnected -> SyncCardData(
            bg = DashColors.WarningAmberBg,
            tint = DashColors.WarningAmber,
            icon = Icons.Outlined.CloudOff,
            title = "Sin conexión",
            subtitle = "Los datos locales están seguros"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Icono con fondo redondeado ──
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                // Animación de pulso si está conectando
                if (connectionStatus is ConnectionStatus.Connecting) {
                    val alpha by rememberInfiniteTransition(
                        label = "pulse"
                    ).animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint.copy(alpha = alpha)
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DashColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = DashColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Botón de reintento solo si hay error
            if (connectionStatus is ConnectionStatus.Error) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reintentar",
                        tint = DashColors.PrimaryBlue
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE MÉTRICA ══════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun MetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    backgroundColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconTint
                )
            }

            Column {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = DashColors.TextPrimary
                )
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = DashColors.TextSecondary,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ ACCIÓN RÁPIDA ═══════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        onClick = { onClick?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = accentColor
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DashColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = DashColors.TextSecondary
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = DashColors.DividerLight
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ PLACEHOLDER DE ACTIVIDAD RECIENTE ═══════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun RecentActivityPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = DashColors.DividerLight
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sin actividad reciente",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DashColors.TextSecondary
            )
            Text(
                text = "Las sincronizaciones con pacientes aparecerán aquí",
                fontSize = 12.sp,
                color = DashColors.TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ UTILIDADES ═══════════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

private data class SyncCardData(
    val bg: Color,
    val tint: Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

private fun saludoSegunHora(): String {
    val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hora < 7 -> "Buenas noches,"
        hora < 13 -> "Buenos días,"
        hora < 20 -> "Buenas tardes,"
        else -> "Buenas noches,"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}