package com.alberto.medp2p_poc.ui.patients.detail

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Patient
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RELAY_BASE_ADDR =
    "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWEBiChhAXXnZRPoM37aoawZbYQKp7WxqtC7LrfZFab4TV"

private object DetailColors {
    val PrimaryBlue      = Color(0xFF005FB8)
    val PrimaryBlueDark  = Color(0xFF003D7A)
    val PrimaryBlueLight = Color(0xFF4A90D9)
    val AccentMint       = Color(0xFF00C9A7)
    val AccentMintBg     = Color(0xFFE6FAF5)
    val SurfaceWhite     = Color(0xFFF8FAFE)
    val CardWhite        = Color(0xFFFFFFFF)
    val TextPrimary      = Color(0xFF1A1C2B)
    val TextSecondary    = Color(0xFF6B7280)
    val DividerLight     = Color(0xFFE8EDF2)
    val ErrorRed         = Color(0xFFDC3545)
    val WarningAmber     = Color(0xFFF59E0B)
    val WarningAmberBg   = Color(0xFFFFF8E1)
    val Purple           = Color(0xFF7C3AED)
    val PurpleBg         = Color(0xFFF3EEFE)
    val BlueBg           = Color(0xFFEBF3FE)

    val AvatarPalette = listOf(
        Color(0xFF005FB8), Color(0xFF00C9A7), Color(0xFF7C3AED),
        Color(0xFFE65100), Color(0xFF2E7D32), Color(0xFFC62828),
        Color(0xFF0277BD), Color(0xFF6D4C41)
    )
}

@Composable
fun PantallaDetallePaciente(
    peerId: String,
    viewModel: PatientDetailViewModel,
    dashboardViewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(peerId) { viewModel.loadPatientDetail(peerId, dashboardViewModel.currentOwnerPeerId) }
    when {
        state.isLoading -> {
            Box(modifier = Modifier.fillMaxSize().background(DetailColors.SurfaceWhite),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DetailColors.PrimaryBlue, strokeWidth = 3.dp)
            }
        }
        state.errorMessage != null -> {
            ErrorState(message = state.errorMessage!!, onBack = onBack)
        }
        state.patient != null -> {
            PatientDetailContent(
                patient       = state.patient!!,
                medications   = state.activeMedications,
                pautasActivas = state.pautasActivas,
                history       = state.medicalHistory,
                onBack        = onBack,
                onAddNote = { nota ->
                    val destinationAddr = "$RELAY_BASE_ADDR/p2p-circuit/p2p/$peerId"
                    viewModel.addClinicalNote(
                        peerId                 = peerId,
                        noteText               = nota,
                        dashboardViewModel     = dashboardViewModel,
                        destinationCircuitAddr = destinationAddr
                    )
                },
                onAddPrescription = { medId, hours ->
                    viewModel.addPrescription(peerId, medId, hours)
                },
                onAddPauta = { medicacion, dosis, frecuencia, fechaInicio, fechaFin ->
                    dashboardViewModel.crearPauta(
                        patientPeerId    = peerId,
                        medicacion       = medicacion,
                        dosis            = dosis,
                        frecuenciaDiaria = frecuencia,
                        fechaInicio      = fechaInicio,
                        fechaFin         = fechaFin
                    )
                    viewModel.loadPatientDetail(peerId, dashboardViewModel.currentOwnerPeerId)
                }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PatientDetailContent(
    patient: Patient,
    medications: List<ActiveMedication>,
    pautasActivas: List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>,
    history: List<MedicalRecord>,
    onBack: () -> Unit,
    onAddNote: (String) -> Unit,
    onAddPrescription: (medicamentoId: String, intervaloHoras: Int) -> Unit,
    onAddPauta: (medicacion: String, dosis: String, frecuencia: Int, fechaInicio: Long, fechaFin: Long) -> Unit
) {
    val tabs = listOf(
        TabInfo("Datos",      Icons.Outlined.Person),
        TabInfo("Medicacion", Icons.Outlined.MedicalServices),
        TabInfo("Historial",  Icons.Outlined.Description)
    )
    val pagerState    = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    var showNuevaPautaSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(DetailColors.SurfaceWhite)) {
        PatientDetailHeader(patient = patient, onBack = onBack)

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor   = DetailColors.CardWhite,
            contentColor     = DetailColors.PrimaryBlue,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    val pos = tabPositions[pagerState.currentPage]
                    Box(Modifier.fillMaxWidth().wrapContentSize(Alignment.BottomStart)
                        .offset(x = pos.left).width(pos.width).height(3.dp)
                        .background(color = DetailColors.PrimaryBlue,
                            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                }
            },
            divider = { HorizontalDivider(color = DetailColors.DividerLight, thickness = 1.dp) }
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = pagerState.currentPage == index
                Tab(
                    selected = selected,
                    onClick  = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(text = tab.title, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) DetailColors.PrimaryBlue else DetailColors.TextSecondary)
                    },
                    icon = {
                        Icon(imageVector = tab.icon, contentDescription = tab.title,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) DetailColors.PrimaryBlue else DetailColors.TextSecondary)
                    }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> TabDatosPersonales(patient = patient)
                1 -> TabMedicaciones(
                    medications      = medications,
                    pautasActivas    = pautasActivas,
                    onOpenNuevaPauta = { showNuevaPautaSheet = true }
                )
                2 -> TabHistorial(history = history, onAddNote = onAddNote)
            }
        }

        if (showNuevaPautaSheet) {
            NuevaPautaPacienteBottomSheet(
                patientName = patient.fullName,
                onDismiss   = { showNuevaPautaSheet = false },
                onConfirm   = { medicacion, dosis, frecuencia, fechaInicio, fechaFin ->
                    onAddPauta(medicacion, dosis, frecuencia, fechaInicio, fechaFin)
                    showNuevaPautaSheet = false
                }
            )
        }
    }
}

@Composable
private fun PatientDetailHeader(patient: Patient, onBack: () -> Unit) {
    val avatarColor = DetailColors.AvatarPalette[
        patient.avatarColorIndex.coerceIn(0, DetailColors.AvatarPalette.lastIndex)
    ]
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(DetailColors.PrimaryBlue, DetailColors.PrimaryBlueDark)))
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    ) {
        Column {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(avatarColor, avatarColor.copy(alpha = 0.7f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = patient.initials, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = patient.fullName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(text = "Vinculado el ${formatDate(patient.linkedAt)}", fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f))
                    if (patient.allergies.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = DetailColors.ErrorRed.copy(alpha = 0.2f)) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Warning, contentDescription = null,
                                    modifier = Modifier.size(13.dp), tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text(text = patient.allergies, fontSize = 11.sp, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabDatosPersonales(patient: Patient) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(text = "Informacion del paciente", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DetailColors.TextPrimary) }
        item { InfoCard(icon = Icons.Outlined.Person, label = "Nombre completo", value = patient.fullName, iconBg = DetailColors.BlueBg, iconTint = DetailColors.PrimaryBlue) }
        item {
            InfoCard(icon = Icons.Outlined.Warning, label = "Alergias conocidas",
                value = patient.allergies.ifBlank { "Sin alergias registradas" },
                iconBg = if (patient.allergies.isNotBlank()) DetailColors.WarningAmberBg else DetailColors.AccentMintBg,
                iconTint = if (patient.allergies.isNotBlank()) DetailColors.WarningAmber else DetailColors.AccentMint)
        }
        item { InfoCard(icon = Icons.Outlined.Notes, label = "Notas clinicas", value = patient.notes.ifBlank { "Sin notas adicionales" }, iconBg = DetailColors.PurpleBg, iconTint = DetailColors.Purple) }
        item { InfoCard(icon = Icons.Outlined.CalendarToday, label = "Fecha de vinculacion", value = formatDate(patient.linkedAt), iconBg = DetailColors.BlueBg, iconTint = DetailColors.PrimaryBlue) }
        item {
            InfoCard(icon = Icons.Outlined.Sync, label = "Ultima sincronizacion",
                value = patient.lastSyncAt?.let { formatDateTime(it) } ?: "Pendiente de primera sincronizacion",
                iconBg = DetailColors.AccentMintBg, iconTint = DetailColors.AccentMint)
        }
    }
}

@Composable
private fun TabMedicaciones(
    medications: List<ActiveMedication>,
    pautasActivas: List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>,
    onOpenNuevaPauta: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Medicacion activa", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DetailColors.TextPrimary)

                FilledTonalButton(
                    onClick = onOpenNuevaPauta,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DetailColors.PrimaryBlue.copy(alpha = 0.1f),
                        contentColor   = DetailColors.PrimaryBlue
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nueva pauta", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (pautasActivas.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Medicacion actual (pautas activas)", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = DetailColors.TextPrimary)
            }
            items(pautasActivas) { pauta -> PautaActivaCard(pauta = pauta) }
            item {
                HorizontalDivider(color = DetailColors.DividerLight, modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        if (medications.isEmpty() && pautasActivas.isEmpty()) {
            item {
                EmptyTabState(icon = Icons.Outlined.MedicalServices,
                    message = "Sin medicacion activa",
                    hint = "Las pautas medicas asignadas apareceran aqui")
            }
        } else {
            items(medications) { med -> MedicationCard(activeMed = med) }
        }
    }
}

@Composable
private fun MedicationCard(activeMed: ActiveMedication) {
    val med        = activeMed.medication
    val stockColor = when {
        med.stockActual <= 0 -> DetailColors.ErrorRed
        med.stockActual <= 5 -> DetailColors.WarningAmber
        else                 -> DetailColors.AccentMint
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(DetailColors.BlueBg),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Medication, contentDescription = null, modifier = Modifier.size(24.dp), tint = DetailColors.PrimaryBlue)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = med.nombreComercial, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DetailColors.TextPrimary)
                Text(text = "${med.principleActivo} - ${med.concentracionMg}mg", fontSize = 12.sp, color = DetailColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(13.dp), tint = DetailColors.Purple)
                    Spacer(Modifier.width(4.dp))
                    Text(text = activeMed.nextDoseLabel, fontSize = 12.sp, color = DetailColors.Purple, fontWeight = FontWeight.Medium)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "${med.stockActual}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = stockColor)
                Text(text = "stock", fontSize = 10.sp, color = DetailColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun TabHistorial(history: List<MedicalRecord>, onAddNote: (String) -> Unit) {
    var noteInput   by remember { mutableStateOf("") }
    var showAddNote by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Historial clinico", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DetailColors.TextPrimary)
                FilledTonalButton(onClick = { showAddNote = !showAddNote }, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DetailColors.PrimaryBlue.copy(alpha = 0.1f),
                        contentColor   = DetailColors.PrimaryBlue)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nota", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (showAddNote) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DetailColors.PrimaryBlue.copy(alpha = 0.04f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = noteInput, onValueChange = { noteInput = it },
                            label = { Text("Nueva nota clinica") },
                            placeholder = { Text("Ej: Paciente refiere mejoria...") },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            minLines = 2, maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DetailColors.PrimaryBlue,
                                unfocusedBorderColor = DetailColors.DividerLight,
                                cursorColor = DetailColors.PrimaryBlue))
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showAddNote = false; noteInput = "" }) {
                                Text("Cancelar", color = DetailColors.TextSecondary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onAddNote(noteInput); noteInput = ""; showAddNote = false },
                                enabled = noteInput.isNotBlank(), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DetailColors.PrimaryBlue)) {
                                Text("Guardar nota", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item { EmptyTabState(icon = Icons.Outlined.Description, message = "Sin registros clinicos", hint = "Las notas y sincronizaciones apareceran aqui") }
        } else {
            items(history.reversed()) { record -> HistoryNoteCard(record = record) }
        }
    }
}

@Composable
private fun HistoryNoteCard(record: MedicalRecord) {
    val isMine      = record.isMine
    val accentColor = if (isMine) DetailColors.PrimaryBlue else DetailColors.AccentMint
    val authorLabel = if (isMine) "Tu" else record.senderAlias.ifBlank { "Paciente" }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accentColor))
                    Spacer(Modifier.width(8.dp))
                    Text(text = authorLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                }
                Text(text = formatDateTime(record.timestamp), fontSize = 11.sp, color = DetailColors.TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Text(text = record.text, fontSize = 14.sp, color = DetailColors.TextPrimary, lineHeight = 20.sp)
        }
    }
}

private data class TabInfo(val title: String, val icon: ImageVector)

@Composable
private fun InfoCard(icon: ImageVector, label: String, value: String, iconBg: Color, iconTint: Color) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = iconTint)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(text = label, fontSize = 11.sp, color = DetailColors.TextSecondary, fontWeight = FontWeight.Medium)
                Text(text = value, fontSize = 15.sp, color = DetailColors.TextPrimary, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun EmptyTabState(icon: ImageVector, message: String, hint: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)) {
        Column(modifier = Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = DetailColors.DividerLight)
            Spacer(Modifier.height(12.dp))
            Text(text = message, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DetailColors.TextSecondary)
            Text(text = hint, fontSize = 12.sp, color = DetailColors.TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DetailColors.SurfaceWhite), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = DetailColors.ErrorRed)
            Spacer(Modifier.height(16.dp))
            Text(text = message, fontSize = 16.sp, color = DetailColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetailColors.PrimaryBlue)) { Text("Volver") }
        }
    }
}

@Composable
private fun PautaActivaCard(pauta: com.alberto.medp2p_poc.data.model.PautaMedicaV2) {
    val diasRestantes = ((pauta.fechaFin - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
    val urgencyColor  = when {
        diasRestantes <= 2  -> DetailColors.ErrorRed
        diasRestantes <= 7  -> DetailColors.WarningAmber
        else                -> DetailColors.AccentMint
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = DetailColors.CardWhite)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(urgencyColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Medication, null, Modifier.size(22.dp), tint = urgencyColor)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(pauta.medicacion, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DetailColors.TextPrimary)
                Text("${pauta.dosis}  ·  ${pauta.frecuenciaDiaria}x/día", fontSize = 12.sp, color = DetailColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarToday, null, Modifier.size(12.dp), tint = urgencyColor)
                    Spacer(Modifier.width(4.dp))
                    Text("Hasta el ${formatDate(pauta.fechaFin)}  ·  $diasRestantes días restantes",
                        fontSize = 11.sp, color = urgencyColor, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuevaPautaPacienteBottomSheet(
    patientName: String,
    onDismiss: () -> Unit,
    onConfirm: (medicacion: String, dosis: String, frecuencia: Int, fechaInicio: Long, fechaFin: Long) -> Unit
) {
    var medicacion       by remember { mutableStateOf("") }
    var dosis            by remember { mutableStateOf("") }
    var frecuenciaText   by remember { mutableStateOf("1") }
    var duracionDiasText by remember { mutableStateOf("") }

    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = DetailColors.CardWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Nueva Pauta Médica", fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = DetailColors.TextPrimary)
            Text("Asignando pauta para: $patientName", fontSize = 12.sp, color = DetailColors.PrimaryBlue, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(value = medicacion, onValueChange = { medicacion = it },
                label = { Text("Medicacion") }, placeholder = { Text("Ej: Ibuprofeno") },
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = dosis, onValueChange = { dosis = it },
                label = { Text("Dosis") }, placeholder = { Text("Ej: 400mg") },
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = frecuenciaText,
                onValueChange = { if (it.all(Char::isDigit) && it.length <= 2) frecuenciaText = it },
                label = { Text("Frecuencia diaria (veces/dia)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = duracionDiasText,
                onValueChange = { if (it.all(Char::isDigit) && it.length <= 3) duracionDiasText = it },
                label = { Text("Duracion del tratamiento (dias)") },
                placeholder = { Text("Ej: 7, 14, 30") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            val frecuencia   = frecuenciaText.toIntOrNull() ?: 0
            val duracionDias = duracionDiasText.toIntOrNull() ?: 0
            val formValid    = medicacion.isNotBlank()
                    && dosis.isNotBlank() && frecuencia > 0 && duracionDias > 0

            Button(
                onClick = {
                    if (formValid) {
                        val fechaInicio = System.currentTimeMillis()
                        val fechaFin    = fechaInicio + (duracionDias * 86_400_000L)
                        onConfirm(medicacion, dosis, frecuencia, fechaInicio, fechaFin)
                        Toast.makeText(context, "Pauta creada correctamente", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Falta rellenar algún campo obligatorio", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled  = true,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(16.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = DetailColors.PrimaryBlue)
            ) {
                Icon(Icons.Outlined.MedicalServices, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Crear Pauta", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))