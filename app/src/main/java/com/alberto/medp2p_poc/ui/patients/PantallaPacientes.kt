package com.alberto.medp2p_poc.ui.patients

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.Patient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// PantallaPacientes es un Composable 100% declarativo que observa
// PatientsUiState del ViewModel. La búsqueda es instantánea (filtro
// local en memoria), los avatares se generan con iniciales + colores
// deterministas (sin imágenes pesadas), y las acciones (favorito,
// eliminar, ver historial) son callbacks puros sin lógica de negocio.
// Cero menciones a PeerId/P2P/Multiaddr en la interfaz visual.
// ──────────────────────────────────────────────────────────────────────

// ── Paleta clínica ──
private object PatColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val AccentMint = Color(0xFF00C9A7)
    val SurfaceWhite = Color(0xFFF8FAFE)
    val CardWhite = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1C2B)
    val TextSecondary = Color(0xFF6B7280)
    val DividerLight = Color(0xFFE8EDF2)
    val ErrorRed = Color(0xFFDC3545)
    val FavoriteGold = Color(0xFFFFB800)

    val AvatarPalette = listOf(
        Color(0xFF005FB8),
        Color(0xFF00C9A7),
        Color(0xFF7C3AED),
        Color(0xFFE65100),
        Color(0xFF2E7D32),
        Color(0xFFC62828),
        Color(0xFF0277BD),
        Color(0xFF6D4C41),
    )
}

// ══════════════════════════════════════════════════════════════
// ══ PANTALLA PRINCIPAL ═══════════════════════════════════════
// ══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPacientes(
    viewModel: PatientsViewModel,
    onPacienteSeleccionado: (peerId: String) -> Unit,
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLinkDialog by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    // ── AÑADIDO: Configuración del Escáner QR de ML Kit ──
    val context = LocalContext.current
    var scannedPeerId by remember { mutableStateOf("") }

    val scannerOptions = remember {
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
    }
    val scanner = remember(context) { GmsBarcodeScanning.getClient(context, scannerOptions) }

    LaunchedEffect(Unit) { viewModel.loadPatients() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis Pacientes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PatColors.SurfaceWhite)
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(visible = fabExpanded) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallFloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                scannedPeerId = "" // Limpiar por si acaso
                                showLinkDialog = true
                            },
                            containerColor = PatColors.PrimaryBlue,
                            contentColor = Color.White
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Añadir manualmente", fontSize = 13.sp)
                            }
                        }
                        // ── AÑADIDO: Botón Escanear con la cámara real ──
                        SmallFloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                scanner.startScan()
                                    .addOnSuccessListener { barcode ->
                                        val raw = barcode.rawValue
                                        if (!raw.isNullOrBlank()) {
                                            scannedPeerId = raw
                                            showLinkDialog = true
                                        } else {
                                            Toast.makeText(context, "QR no válido", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(context, "Escaneo cancelado", Toast.LENGTH_SHORT).show()
                                    }
                            },
                            containerColor = PatColors.PrimaryBlue,
                            contentColor = Color.White
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Escanear QR", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { fabExpanded = !fabExpanded },
                    containerColor = PatColors.PrimaryBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(if (fabExpanded) Icons.Outlined.Close else Icons.Outlined.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Vincular", fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = PatColors.SurfaceWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PatientListHeader(
                totalCount = state.totalCount,
                filteredCount = state.patients.size,
                searchQuery = state.searchQuery,
                filterFavorites = state.filterFavorites,
                onSearchChange = { viewModel.updateSearchQuery(it) },
                onToggleFavoriteFilter = { viewModel.toggleFavoriteFilter() }
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = PatColors.PrimaryBlue,
                            strokeWidth = 3.dp
                        )
                    }
                }

                state.patients.isEmpty() && state.searchQuery.isNotEmpty() -> {
                    EmptySearchState(query = state.searchQuery)
                }

                state.patients.isEmpty() -> {
                    EmptyPatientsState()
                }

                else -> {
                    PatientList(
                        patients = state.patients,
                        onSelect = { onPacienteSeleccionado(it.peerId) },
                        onToggleFavorite = { viewModel.toggleFavorite(it.id) },
                        onDelete = { viewModel.removePatient(it.id) }
                    )
                }
            }
        }
    }

    // ── AÑADIDO: Pasamos el initialPeerId al diálogo ──
    if (showLinkDialog) {
        LinkPatientDialog(
            initialPeerId = scannedPeerId,
            onDismiss = {
                showLinkDialog = false
                scannedPeerId = ""
            },
            onConfirm = { name, peerId, allergies ->
                viewModel.linkPatient(name, peerId, allergies)
                showLinkDialog = false
                scannedPeerId = ""
            }
        )
    }
}


// ══════════════════════════════════════════════════════════════
// ══ CABECERA: TÍTULO + BARRA DE BÚSQUEDA + FILTRO ═══════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun PatientListHeader(
    totalCount: Int,
    filteredCount: Int,
    searchQuery: String,
    filterFavorites: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleFavoriteFilter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PatColors.PrimaryBlue, PatColors.PrimaryBlueDark)
                )
            )
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Mis Pacientes",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "$totalCount vinculados" +
                            if (searchQuery.isNotEmpty()) " · $filteredCount resultados" else "",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            FilledIconToggleButton(
                checked = filterFavorites,
                onCheckedChange = { onToggleFavoriteFilter() },
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    checkedContainerColor = PatColors.FavoriteGold.copy(alpha = 0.25f),
                    containerColor = Color.White.copy(alpha = 0.1f)
                )
            ) {
                Icon(
                    imageVector = if (filterFavorites) Icons.Filled.Favorite
                    else Icons.Filled.FavoriteBorder,
                    contentDescription = "Filtrar favoritos",
                    tint = if (filterFavorites) PatColors.FavoriteGold
                    else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    text = "Buscar por nombre o alergia...",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Limpiar",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = Color.White,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ══ LISTA DE PACIENTES ══════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun PatientList(
    patients: List<Patient>,
    onSelect: (Patient) -> Unit,
    onToggleFavorite: (Patient) -> Unit,
    onDelete: (Patient) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = patients,
            key = { it.id }
        ) { patient ->
            PatientCard(
                patient = patient,
                onSelect = { onSelect(patient) },
                onToggleFavorite = { onToggleFavorite(patient) },
                onDelete = { onDelete(patient) }
            )
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE PACIENTE ═════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun PatientCard(
    patient: Patient,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val avatarColor = PatColors.AvatarPalette[
        patient.avatarColorIndex.coerceIn(0, PatColors.AvatarPalette.lastIndex)
    ]

    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = PatColors.CardWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(avatarColor, avatarColor.copy(alpha = 0.7f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = patient.initials,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = patient.fullName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PatColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (patient.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = PatColors.FavoriteGold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                if (patient.allergies.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = PatColors.ErrorRed.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = patient.allergies,
                            fontSize = 12.sp,
                            color = PatColors.ErrorRed.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "Vinculado el ${formatDate(patient.linkedAt)}",
                    fontSize = 11.sp,
                    color = PatColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-4).dp)
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (patient.isFavorite) Icons.Filled.Favorite
                        else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorito",
                        modifier = Modifier.size(18.dp),
                        tint = if (patient.isFavorite) PatColors.FavoriteGold
                        else PatColors.DividerLight
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Eliminar",
                        modifier = Modifier.size(18.dp),
                        tint = PatColors.DividerLight
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = PatColors.ErrorRed
                )
            },
            title = {
                Text(
                    text = "Desvincular paciente",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Se eliminara del directorio pero los historiales previos se conservan.",
                    fontSize = 14.sp,
                    color = PatColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PatColors.ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Desvincular")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ══ DIÁLOGO DE VINCULACIÓN ═══════════════════════════════════
// ══════════════════════════════════════════════════════════════

// ── AÑADIDO: initialPeerId para pre-rellenar desde el escáner ──
@Composable
private fun LinkPatientDialog(
    initialPeerId: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String, peerId: String, allergies: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var code by remember(initialPeerId) { mutableStateOf(initialPeerId) }
    var allergies by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PatColors.PrimaryBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PersonAdd,
                    contentDescription = null,
                    tint = PatColors.PrimaryBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Vincular nuevo paciente",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Introduce los datos del paciente. El codigo de vinculacion se obtiene desde su dispositivo.",
                    fontSize = 13.sp,
                    color = PatColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre completo") },
                    placeholder = { Text("Ej: Juan Garcia Perez") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = PatColors.PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Codigo de vinculacion") },
                    placeholder = { Text("Escanear o pegar codigo") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            tint = PatColors.PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )

                OutlinedTextField(
                    value = allergies,
                    onValueChange = { allergies = it },
                    label = { Text("Alergias conocidas (opcional)") },
                    placeholder = { Text("Ej: Penicilina, Ibuprofeno") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = PatColors.ErrorRed.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, code, allergies) },
                enabled = name.isNotBlank() && code.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PatColors.PrimaryBlue,
                    disabledContainerColor = PatColors.PrimaryBlue.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Vincular paciente", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancelar", color = PatColors.TextSecondary)
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = PatColors.CardWhite
    )
}

// ══════════════════════════════════════════════════════════════
// ══ ESTADOS VACÍOS ══════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun EmptyPatientsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(PatColors.PrimaryBlue.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.People,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = PatColors.PrimaryBlue.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Sin pacientes vinculados",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = PatColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pulsa el boton Vincular para anadir tu primer paciente al directorio",
                fontSize = 14.sp,
                color = PatColors.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp)
        ) {
            Icon(
                Icons.Outlined.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = PatColors.DividerLight
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Sin resultados para: $query",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PatColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Prueba con otro nombre o revisa los filtros",
                fontSize = 13.sp,
                color = PatColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ UTILIDADES ═══════════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun dialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PatColors.PrimaryBlue,
    unfocusedBorderColor = PatColors.DividerLight,
    focusedLabelColor = PatColors.PrimaryBlue,
    unfocusedLabelColor = PatColors.TextSecondary,
    cursorColor = PatColors.PrimaryBlue,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = PatColors.SurfaceWhite.copy(alpha = 0.5f)
)

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}