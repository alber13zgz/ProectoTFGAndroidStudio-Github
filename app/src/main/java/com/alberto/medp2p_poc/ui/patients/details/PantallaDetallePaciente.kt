package com.alberto.medp2p_poc.ui.patients.detail

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Patient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PantallaDetallePaciente usa HorizontalPager + TabRow de MD3 para
// las 3 pestanas clinicas. Cada pestana es un Composable independiente
// que recibe datos inmutables y callbacks. El ViewModel se carga una
// sola vez al entrar (LaunchedEffect con el peerId). Cero argot P2P.
// ──────────────────────────────────────────────────────────────────────

private object DetailColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val PrimaryBlueLight = Color(0xFF4A90D9)
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

    val AvatarPalette = listOf(
        Color(0xFF005FB8), Color(0xFF00C9A7), Color(0xFF7C3AED),
        Color(0xFFE65100), Color(0xFF2E7D32), Color(0xFFC62828),
        Color(0xFF0277BD), Color(0xFF6D4C41)
    )
}

// ══════════════════════════════════════════════════════════════
// ══ PANTALLA PRINCIPAL ═══════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
fun PantallaDetallePaciente(
    peerId: String,
    viewModel: PatientDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(peerId) { viewModel.loadPatientDetail(peerId) }

    when {
        state.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DetailColors.SurfaceWhite),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = DetailColors.PrimaryBlue,
                    strokeWidth = 3.dp
                )
            }
        }

        state.errorMessage != null -> {
            ErrorState(
                message = state.errorMessage!!,
                onBack = onBack
            )
        }

        state.patient != null -> {
            PatientDetailContent(
                patient = state.patient!!,
                medications = state.activeMedications,
                history = state.medicalHistory,
                onBack = onBack,
                onAddNote = { viewModel.addClinicalNote(peerId, it) },
                onAddPrescription = { medId, hours ->
                    viewModel.addPrescription(peerId, medId, hours)
                }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ CONTENIDO CON CABECERA + PESTANAS ═══════════════════════
// ══════════════════════════════════════════════════════════════

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PatientDetailContent(
    patient: Patient,
    medications: List<ActiveMedication>,
    history: List<MedicalRecord>,
    onBack: () -> Unit,
    onAddNote: (String) -> Unit,
    onAddPrescription: (medicamentoId: String, intervaloHoras: Int) -> Unit
) {
    val tabs = listOf(
        TabInfo("Datos", Icons.Outlined.Person),
        TabInfo("Medicacion", Icons.Outlined.MedicalServices),
        TabInfo("Historial", Icons.Outlined.Description)
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailColors.SurfaceWhite)
    ) {
        // ── Cabecera del paciente ──
        PatientDetailHeader(patient = patient, onBack = onBack)

        // ── TabRow ──
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = DetailColors.CardWhite,
            contentColor = DetailColors.PrimaryBlue,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    val currentTabPosition = tabPositions[pagerState.currentPage]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .wrapContentSize(Alignment.BottomStart)
                            .offset(x = currentTabPosition.left)
                            .width(currentTabPosition.width)
                            .height(3.dp)
                            .background(
                                color = DetailColors.PrimaryBlue,
                                shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                            )
                    )
                }
            },
            divider = {
                HorizontalDivider(color = DetailColors.DividerLight, thickness = 1.dp)
            }
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = pagerState.currentPage == index
                Tab(
                    selected = selected,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            text = tab.title,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) DetailColors.PrimaryBlue
                            else DetailColors.TextSecondary
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) DetailColors.PrimaryBlue
                            else DetailColors.TextSecondary
                        )
                    }
                )
            }
        }

        // ── Pager con las 3 pestanas ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> TabDatosPersonales(patient = patient)
                1 -> TabMedicaciones(medications = medications)
                2 -> TabHistorial(
                    history = history,
                    onAddNote = onAddNote
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ CABECERA DEL PACIENTE ═══════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun PatientDetailHeader(patient: Patient, onBack: () -> Unit) {
    val avatarColor = DetailColors.AvatarPalette[
        patient.avatarColorIndex.coerceIn(0, DetailColors.AvatarPalette.lastIndex)
    ]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(DetailColors.PrimaryBlue, DetailColors.PrimaryBlueDark)
                )
            )
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    ) {
        Column {
            // ── Boton atras ──
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Avatar grande ──
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(avatarColor, avatarColor.copy(alpha = 0.7f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = patient.initials,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = patient.fullName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Vinculado el ${formatDate(patient.linkedAt)}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    if (patient.allergies.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DetailColors.ErrorRed.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = Color.White
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = patient.allergies,
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ PESTANA 1: DATOS PERSONALES ═════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun TabDatosPersonales(patient: Patient) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Informacion del paciente",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DetailColors.TextPrimary
            )
        }

        item {
            InfoCard(
                icon = Icons.Outlined.Person,
                label = "Nombre completo",
                value = patient.fullName,
                iconBg = DetailColors.BlueBg,
                iconTint = DetailColors.PrimaryBlue
            )
        }

        item {
            InfoCard(
                icon = Icons.Outlined.Warning,
                label = "Alergias conocidas",
                value = patient.allergies.ifBlank { "Sin alergias registradas" },
                iconBg = if (patient.allergies.isNotBlank())
                    DetailColors.WarningAmberBg else DetailColors.AccentMintBg,
                iconTint = if (patient.allergies.isNotBlank())
                    DetailColors.WarningAmber else DetailColors.AccentMint
            )
        }

        item {
            InfoCard(
                icon = Icons.Outlined.Notes,
                label = "Notas clinicas",
                value = patient.notes.ifBlank { "Sin notas adicionales" },
                iconBg = DetailColors.PurpleBg,
                iconTint = DetailColors.Purple
            )
        }

        item {
            InfoCard(
                icon = Icons.Outlined.CalendarToday,
                label = "Fecha de vinculacion",
                value = formatDate(patient.linkedAt),
                iconBg = DetailColors.BlueBg,
                iconTint = DetailColors.PrimaryBlue
            )
        }

        item {
            InfoCard(
                icon = Icons.Outlined.Sync,
                label = "Ultima sincronizacion",
                value = patient.lastSyncAt?.let { formatDateTime(it) }
                    ?: "Pendiente de primera sincronizacion",
                iconBg = DetailColors.AccentMintBg,
                iconTint = DetailColors.AccentMint
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ PESTANA 2: MEDICACIONES ACTIVAS ═════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun TabMedicaciones(medications: List<ActiveMedication>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Medicacion activa",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DetailColors.TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DetailColors.BlueBg
                ) {
                    Text(
                        text = "${medications.size} pautas",
                        fontSize = 12.sp,
                        color = DetailColors.PrimaryBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (medications.isEmpty()) {
            item { EmptyTabState(
                icon = Icons.Outlined.MedicalServices,
                message = "Sin medicacion activa",
                hint = "Las pautas medicas asignadas apareceran aqui"
            ) }
        } else {
            items(medications) { med ->
                MedicationCard(activeMed = med)
            }
        }
    }
}

@Composable
private fun MedicationCard(activeMed: ActiveMedication) {
    val med = activeMed.medication
    val stockColor = when {
        med.stockActual <= 0 -> DetailColors.ErrorRed
        med.stockActual <= 5 -> DetailColors.WarningAmber
        else -> DetailColors.AccentMint
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Icono de pastilla ──
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DetailColors.BlueBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Medication,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = DetailColors.PrimaryBlue
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = med.nombreComercial,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailColors.TextPrimary
                )
                Text(
                    text = "${med.principleActivo} - ${med.concentracionMg}mg",
                    fontSize = 12.sp,
                    color = DetailColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = DetailColors.Purple
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = activeMed.nextDoseLabel,
                        fontSize = 12.sp,
                        color = DetailColors.Purple,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ── Stock ──
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${med.stockActual}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = stockColor
                )
                Text(
                    text = "stock",
                    fontSize = 10.sp,
                    color = DetailColors.TextSecondary
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ PESTANA 3: HISTORIAL MEDICO ═════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun TabHistorial(
    history: List<MedicalRecord>,
    onAddNote: (String) -> Unit
) {
    var noteInput by remember { mutableStateOf("") }
    var showAddNote by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Historial clinico",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DetailColors.TextPrimary
                )
                FilledTonalButton(
                    onClick = { showAddNote = !showAddNote },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DetailColors.PrimaryBlue.copy(alpha = 0.1f),
                        contentColor = DetailColors.PrimaryBlue
                    )
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Nota", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Campo para anadir nota ──
        if (showAddNote) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DetailColors.PrimaryBlue.copy(alpha = 0.04f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            label = { Text("Nueva nota clinica") },
                            placeholder = { Text("Ej: Paciente refiere mejoria...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            minLines = 2,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DetailColors.PrimaryBlue,
                                unfocusedBorderColor = DetailColors.DividerLight,
                                cursorColor = DetailColors.PrimaryBlue
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                showAddNote = false
                                noteInput = ""
                            }) {
                                Text("Cancelar", color = DetailColors.TextSecondary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onAddNote(noteInput)
                                    noteInput = ""
                                    showAddNote = false
                                },
                                enabled = noteInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DetailColors.PrimaryBlue
                                )
                            ) {
                                Text("Guardar nota", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                EmptyTabState(
                    icon = Icons.Outlined.Description,
                    message = "Sin registros clinicos",
                    hint = "Las notas y sincronizaciones apareceran aqui"
                )
            }
        } else {
            items(history.reversed()) { record ->
                HistoryNoteCard(record = record)
            }
        }
    }
}

@Composable
private fun HistoryNoteCard(record: MedicalRecord) {
    val isMine = record.isMine
    val bgColor = if (isMine) DetailColors.BlueBg else DetailColors.AccentMintBg
    val accentColor = if (isMine) DetailColors.PrimaryBlue else DetailColors.AccentMint
    val authorLabel = if (isMine) "Tu" else record.senderAlias.ifBlank { "Paciente" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = authorLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
                Text(
                    text = formatDateTime(record.timestamp),
                    fontSize = 11.sp,
                    color = DetailColors.TextSecondary
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = record.text,
                fontSize = 14.sp,
                color = DetailColors.TextPrimary,
                lineHeight = 20.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ COMPONENTES COMPARTIDOS ═════════════════════════════════
// ══════════════════════════════════════════════════════════════

private data class TabInfo(val title: String, val icon: ImageVector)

@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    iconBg: Color,
    iconTint: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)
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
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconTint
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = DetailColors.TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    fontSize = 15.sp,
                    color = DetailColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyTabState(icon: ImageVector, message: String, hint: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = DetailColors.DividerLight
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = DetailColors.TextSecondary
            )
            Text(
                text = hint,
                fontSize = 12.sp,
                color = DetailColors.TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailColors.SurfaceWhite),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = DetailColors.ErrorRed
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = DetailColors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onBack,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DetailColors.PrimaryBlue
                )
            ) {
                Text("Volver")
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ UTILIDADES ═══════════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}