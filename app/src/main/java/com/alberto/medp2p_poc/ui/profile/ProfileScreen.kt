package com.alberto.medp2p_poc.ui.profile

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.ui.dashboard.DashboardViewModel

private object ProfileColors {
    val PrimaryBlue     = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val AccentMint      = Color(0xFF00C9A7)
    val ErrorRed        = Color(0xFFDC3545)
    val SurfaceWhite    = Color(0xFFF8FAFE)
    val CardWhite       = Color(0xFFFFFFFF)
    val TextPrimary     = Color(0xFF1A1C2B)
    val TextSecondary   = Color(0xFF6B7280)
    val DividerLight    = Color(0xFFE8EDF2)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    dashboardViewModel: DashboardViewModel,
    onBack: () -> Unit,
    onCerrarSesion: () -> Unit = {}
) {
    val data    = dashboardViewModel.dashboard.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    var displayName      by remember(data.displayName) { mutableStateOf(data.displayName) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var saved            by remember { mutableStateOf(false) }
    var allergies        by remember(data.ownerPeerId) { mutableStateOf("") }

    // Dialog eliminar cuenta
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    LaunchedEffect(data.ownerPeerId) {
        if (data.role == UserRole.PATIENT) {
            allergies = dashboardViewModel.getOwnAllergies()
        }
    }

    val persistedBitmap = remember(data.photoUri) {
        if (data.photoUri.isNotBlank())
            try { BitmapFactory.decodeFile(data.photoUri) } catch (e: Exception) { null }
        else null
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> selectedImageUri = uri; saved = false }

    val displayBitmap = remember(selectedImageUri, persistedBitmap) {
        if (selectedImageUri != null) {
            try {
                context.contentResolver.openInputStream(selectedImageUri!!)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { persistedBitmap }
        } else persistedBitmap
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mi Perfil", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ProfileColors.SurfaceWhite)
            )
        },
        containerColor = ProfileColors.SurfaceWhite
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ══ FOTO ══
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                if (displayBitmap != null) {
                    Image(bitmap = displayBitmap.asImageBitmap(), contentDescription = "Foto de perfil",
                        modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    val initials = displayName.split(" ").take(2)
                        .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ProfileColors.PrimaryBlue, ProfileColors.PrimaryBlueDark))),
                        contentAlignment = Alignment.Center) {
                        Text(initials.ifBlank { "?" }, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape)
                        .background(ProfileColors.AccentMint)
                        .clickable { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.CameraAlt, null, Modifier.size(18.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Toca el icono de camara para cambiar tu foto", fontSize = 12.sp, color = ProfileColors.TextSecondary)
            Spacer(Modifier.height(28.dp))

            // ══ INFORMACIÓN PERSONAL ══
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = ProfileColors.CardWhite)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Informacion personal", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = ProfileColors.TextPrimary, modifier = Modifier.padding(bottom = 16.dp))
                    OutlinedTextField(
                        value = displayName, onValueChange = { displayName = it; saved = false },
                        label = { Text("Nombre de visualizacion") },
                        leadingIcon = { Icon(Icons.Outlined.Person, null, tint = ProfileColors.PrimaryBlue) },
                        singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ProfileColors.PrimaryBlue,
                            unfocusedBorderColor = ProfileColors.DividerLight,
                            cursorColor = ProfileColors.PrimaryBlue))
                    if (data.role == UserRole.PATIENT) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = allergies, onValueChange = { allergies = it; saved = false },
                            label = { Text("Alergias registradas") },
                            placeholder = { Text("Ej: Penicilina, látex...") },
                            leadingIcon = { Icon(Icons.Outlined.Warning, null, tint = ProfileColors.PrimaryBlue) },
                            singleLine = false, maxLines = 3, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ProfileColors.PrimaryBlue,
                                unfocusedBorderColor = ProfileColors.DividerLight,
                                cursorColor = ProfileColors.PrimaryBlue))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ══ IDENTIDAD P2P ══
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(2.dp),
                colors = CardDefaults.cardColors(containerColor = ProfileColors.CardWhite)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Identidad P2P", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = ProfileColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(dashboardViewModel.activeHost?.peerId?.toString() ?: "Sin nodo activo",
                        fontSize = 11.sp, color = ProfileColors.TextSecondary.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text("Rol: ${data.role.displayLabel}", fontSize = 12.sp,
                        color = ProfileColors.PrimaryBlue, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(28.dp))

            // ══ GUARDAR ══
            Button(
                onClick = {
                    val finalPhotoUri = if (selectedImageUri != null)
                        dashboardViewModel.copyPhotoToPrivateDir(context, selectedImageUri.toString())
                    else data.photoUri
                    dashboardViewModel.updateProfile(displayName, finalPhotoUri)
                    if (data.role == UserRole.PATIENT) {
                        dashboardViewModel.updateOwnAllergies(allergies)
                    }
                    saved = true
                },
                modifier  = Modifier.fillMaxWidth().height(54.dp),
                shape     = RoundedCornerShape(16.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = ProfileColors.PrimaryBlue),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Outlined.Save, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Guardar cambios", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            if (saved) {
                Spacer(Modifier.height(12.dp))
                Text("Perfil actualizado correctamente", fontSize = 13.sp,
                    color = ProfileColors.AccentMint, fontWeight = FontWeight.Medium)
            }

            // ══ ELIMINAR CUENTA (solo pacientes) ══
            if (data.role == UserRole.PATIENT) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = ProfileColors.DividerLight)
                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick  = { showDeleteAccountDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = ProfileColors.ErrorRed),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, ProfileColors.ErrorRed)
                ) {
                    Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Eliminar mi cuenta", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ══ DIALOG CONFIRMAR ELIMINACIÓN ══
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            icon = {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(ProfileColors.ErrorRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Warning, null, Modifier.size(26.dp), tint = ProfileColors.ErrorRed)
                }
            },
            title = {
                Text("Eliminar cuenta", fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Text(
                    "Esta accion es irreversible. Tu cuenta sera eliminada y todos tus medicos " +
                            "vinculados seran notificados para desvincularte de sus directorios.",
                    fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    color = ProfileColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        dashboardViewModel.eliminarCuenta {
                            onCerrarSesion()
                        }
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = ProfileColors.ErrorRed),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Si, eliminar mi cuenta", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}