package com.alberto.medp2p_poc.ui.dashboard

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.ui.qr.generateQrBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDashboardClinico(
    viewModel: DashboardViewModel,
    onNavigateToPatients: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {}
) {
    val data by viewModel.dashboard.collectAsStateWithLifecycle()

    // ── Estado del BottomSheet de vinculacion ──
    var showLinkSheet by remember { mutableStateOf(false) }
    // ── Estado del dialogo de ID manual ──
    var showManualDialog by remember { mutableStateOf(false) }
    // ── Estado del dialogo QR de "Compartir mi codigo" ──
    var showShareQrDialog by remember { mutableStateOf(false) }
    // ── Estado para el peerId pre-rellenado (desde QR o manual) ──
    var scannedPeerId by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val scannerOptions = remember {
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
    }
    val scanner = remember(context) { GmsBarcodeScanning.getClient(context, scannerOptions) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashColors.SurfaceWhite)
            .verticalScroll(rememberScrollState())
    ) {
        DashboardHeader(displayName = data.displayName, role = data.role)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .offset(y = (-24).dp)
        ) {
            SyncStatusCard(
                connectionStatus = data.connectionStatus,
                lastSyncTimestamp = data.lastSyncTimestamp,
                onRetry = { viewModel.retryConnection() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ══ METRICAS — Pacientes clicable ══
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
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToPatients() }
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

            Text(
                text = "Acciones rapidas",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DashColors.TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // ── Vincular paciente → abre BottomSheet ──
            QuickActionCard(
                icon = Icons.Outlined.PersonAdd,
                title = "Vincular paciente",
                subtitle = "Escanea QR o introduce ID manualmente",
                accentColor = DashColors.PrimaryBlue,
                onClick = { showLinkSheet = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            QuickActionCard(
                icon = Icons.Outlined.MedicalServices,
                title = "Nueva pauta medica",
                subtitle = "Anade medicacion a un paciente vinculado",
                accentColor = DashColors.AccentMint
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Compartir mi codigo → dialogo QR ──
            QuickActionCard(
                icon = Icons.Outlined.QrCode,
                title = "Compartir mi codigo",
                subtitle = "Muestra tu QR para que te vinculen",
                accentColor = DashColors.Purple,
                onClick = { showShareQrDialog = true }
            )

            Spacer(modifier = Modifier.height(20.dp))

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

    // ══════════════════════════════════════════════════════════════
    // ══ BOTTOM SHEET: VINCULAR PACIENTE ═════════════════════════
    // ══════════════════════════════════════════════════════════════
    if (showLinkSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLinkSheet = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = DashColors.CardWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Vincular nuevo paciente",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = DashColors.TextPrimary
                )
                Text(
                    text = "Elige como obtener el ID del paciente",
                    fontSize = 13.sp,
                    color = DashColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )

                // Opcion 1: Escanear QR
                LinkOptionCard(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = "Escanear Codigo QR",
                    subtitle = "Usa la camara para leer el QR del paciente",
                    color = DashColors.PrimaryBlue,
                    onClick = {
                        showLinkSheet = false
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                val rawValue = barcode.rawValue
                                if (!rawValue.isNullOrBlank()) {
                                    scannedPeerId = rawValue
                                    showManualDialog = true
                                }
                            }
                            .addOnFailureListener {
                                // Reabrir el sheet — NO abrir el diálogo manual automáticamente.
                                // El usuario elige si quiere intentar el QR de nuevo o ir manual.
                                showLinkSheet = true

                            }
                    }
                )

                Spacer(Modifier.height(12.dp))

                // Opcion 2: Escribir manualmente
                LinkOptionCard(
                    icon = Icons.Outlined.Edit,
                    title = "Escribir ID manualmente",
                    subtitle = "Pega o escribe el codigo del paciente",
                    color = DashColors.AccentMint,
                    onClick = {
                        showLinkSheet = false
                        scannedPeerId = ""
                        showManualDialog = true
                    }
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ══ DIALOGO: ID MANUAL ══
    if (showManualDialog) {
        ManualLinkDialog(
            initialPeerId = scannedPeerId,
            onDismiss = { showManualDialog = false },
            onConfirm = { name, peerId, allergies ->
                viewModel.linkPatient(name, peerId, allergies)
                showManualDialog = false
                onNavigateToPatients()
            }
        )
    }

    // ══ DIALOGO: COMPARTIR MI QR ══
    if (showShareQrDialog) {
        ShareMyQrDialog(
            peerId = data.displayName, // Usamos el peerId real del nodo
            viewModel = viewModel,
            onDismiss = { showShareQrDialog = false }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ══ BOTTOM SHEET LINK OPTION CARD ═══════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun LinkOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.SurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(24.dp), tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DashColors.TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = DashColors.TextSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = DashColors.DividerLight)
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ DIALOGO VINCULACION MANUAL ══════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun ManualLinkDialog(
    initialPeerId: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, peerId: String, allergies: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var peerId by remember { mutableStateOf(initialPeerId) }
    var allergies by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DashColors.PrimaryBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.PersonAdd, null, Modifier.size(24.dp), tint = DashColors.PrimaryBlue)
            }
        },
        title = {
            Text("Vincular manualmente", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre del paciente") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = peerId, onValueChange = { peerId = it },
                    label = { Text("PeerId del paciente") },
                    placeholder = { Text("Pegar aqui el codigo") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = allergies, onValueChange = { allergies = it },
                    label = { Text("Alergias (opcional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, peerId, allergies) },
                enabled = name.isNotBlank() && peerId.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.PrimaryBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Vincular", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar", color = DashColors.TextSecondary)
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

// ══════════════════════════════════════════════════════════════
// ══ DIALOGO: COMPARTIR MI QR ════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun ShareMyQrDialog(
    peerId: String,
    viewModel: DashboardViewModel,
    onDismiss: () -> Unit
) {
    val data by viewModel.dashboard.collectAsStateWithLifecycle()
    val hostPeerId = viewModel.activeHost?.peerId?.toString() ?: "sin-nodo-activo"
    val clipboardManager = LocalClipboardManager.current

    val qrBitmap: Bitmap? = remember(hostPeerId) {
        generateQrBitmap(hostPeerId, 512)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Mi codigo de vinculacion", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Codigo QR",
                            modifier = Modifier
                                .size(220.dp)
                                .padding(12.dp)
                        )
                    }
                } else {
                    Text("No se pudo generar el QR", color = DashColors.ErrorRed)
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = hostPeerId,
                    fontSize = 11.sp,
                    color = DashColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(hostPeerId))
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copiar al portapapeles", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

// ══════════════════════════════════════════════════════════════
// ══ COMPONENTES EXISTENTES (sin cambios funcionales) ════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun DashboardHeader(displayName: String, role: UserRole) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DashColors.PrimaryBlue, DashColors.PrimaryBlueDark)
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
                Text(saludoSegunHora(), fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                Text(
                    text = displayName.ifBlank { "Profesional" },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.15f)) {
                    Text(
                        text = role.displayLabel, fontSize = 11.sp, color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            val initials = displayName.split(" ").take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(initials.ifBlank { "?" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    connectionStatus: ConnectionStatus, lastSyncTimestamp: Long?, onRetry: () -> Unit
) {
    val (backgroundColor, iconTint, icon, title, subtitle) = when (connectionStatus) {
        is ConnectionStatus.Connected -> SyncCardData(DashColors.AccentMintBg, DashColors.AccentMint, Icons.Outlined.CloudDone, "Sincronizacion activa", lastSyncTimestamp?.let { "Ultima: ${formatTimestamp(it)}" } ?: "Datos actualizados")
        is ConnectionStatus.Connecting -> SyncCardData(DashColors.WarningAmberBg, DashColors.WarningAmber, Icons.Outlined.CloudSync, "Conectando…", "Estableciendo canal seguro")
        is ConnectionStatus.Error -> SyncCardData(DashColors.ErrorRedBg, DashColors.ErrorRed, Icons.Outlined.CloudOff, "Sincronizacion no disponible", connectionStatus.hint)
        is ConnectionStatus.Disconnected -> SyncCardData(DashColors.WarningAmberBg, DashColors.WarningAmber, Icons.Outlined.CloudOff, "Sin conexion", "Los datos locales estan seguros")
    }

    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                if (connectionStatus is ConnectionStatus.Connecting) {
                    val alpha by rememberInfiniteTransition("pulse").animateFloat(
                        0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), "pulseA"
                    )
                    Icon(icon, null, Modifier.size(24.dp), tint = iconTint.copy(alpha = alpha))
                } else {
                    Icon(icon, null, Modifier.size(24.dp), tint = iconTint)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DashColors.TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = DashColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (connectionStatus is ConnectionStatus.Error) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, "Reintentar", tint = DashColors.PrimaryBlue)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    icon: ImageVector, label: String, value: String,
    backgroundColor: Color, iconTint: Color, modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(130.dp), shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(backgroundColor),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, Modifier.size(22.dp), tint = iconTint) }
            Column {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DashColors.TextPrimary)
                Text(label, fontSize = 11.sp, color = DashColors.TextSecondary, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector, title: String, subtitle: String,
    accentColor: Color, onClick: (() -> Unit)? = null
) {
    Card(
        onClick = { onClick?.invoke() }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, Modifier.size(22.dp), tint = accentColor) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DashColors.TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = DashColors.TextSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = DashColors.DividerLight)
        }
    }
}

@Composable
private fun RecentActivityPlaceholder() {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = DashColors.CardWhite)
    ) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.History, null, Modifier.size(36.dp), tint = DashColors.DividerLight)
            Spacer(Modifier.height(12.dp))
            Text("Sin actividad reciente", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DashColors.TextSecondary)
            Text("Las sincronizaciones con pacientes apareceran aqui", fontSize = 12.sp, color = DashColors.TextSecondary.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private data class SyncCardData(val bg: Color, val tint: Color, val icon: ImageVector, val title: String, val subtitle: String)

private fun saludoSegunHora(): String {
    val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { hora < 7 -> "Buenas noches,"; hora < 13 -> "Buenos dias,"; hora < 20 -> "Buenas tardes,"; else -> "Buenas noches," }
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
}