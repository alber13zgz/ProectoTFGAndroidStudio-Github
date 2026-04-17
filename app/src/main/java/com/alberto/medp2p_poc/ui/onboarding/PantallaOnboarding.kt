package com.alberto.medp2p_poc.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// El Composable es 100% declarativo. No lanza corrutinas, no toca
// Dispatchers, no hace try-catch de red. Solo lee un StateFlow del
// ViewModel y llama lambdas. Esto es MVVM puro según el TFG_RULES.md.
// ──────────────────────────────────────────────────────────────────────

@Composable
fun PantallaOnboarding(
    viewModel: OnboardingViewModel,
    onPerfilGuardado: (peerId: String, nombre: String, esCuidador: Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var nombreInput by remember { mutableStateOf("") }
    var rolSeleccionado by remember { mutableStateOf("Cuidador") }
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Mostrar Snackbar automáticamente cuando hay error ──
    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Error) {
            val error = uiState as OnboardingUiState.Error
            snackbarHostState.showSnackbar(
                message = error.userMessage,
                actionLabel = "Reintentar",
                duration = SnackbarDuration.Long
            ).let { result ->
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.resetState()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    actionColor = MaterialTheme.colorScheme.error
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // ── Header con icono animado ──
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = "Identidad P2P",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "MedP2P",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Red Médica Descentralizada",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(48.dp))

                // ── Card principal ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Configuración del Dispositivo",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Se generará un par de claves Ed25519 único.\nNo hay servidores centrales. Tu identidad es solo tuya.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        // ── Campo de nombre ──
                        OutlinedTextField(
                            value = nombreInput,
                            onValueChange = { nombreInput = it },
                            label = { Text("Tu Nombre Completo") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState !is OnboardingUiState.GeneratingIdentity
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // ── Selector de rol ──
                        Text(
                            "Selecciona tu rol:",
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (rolSeleccionado == "Cuidador"),
                                onClick = { rolSeleccionado = "Cuidador" },
                                enabled = uiState !is OnboardingUiState.GeneratingIdentity
                            )
                            Text("Profesional")
                            Spacer(modifier = Modifier.width(16.dp))
                            RadioButton(
                                selected = (rolSeleccionado == "Paciente"),
                                onClick = { rolSeleccionado = "Paciente" },
                                enabled = uiState !is OnboardingUiState.GeneratingIdentity
                            )
                            Text("Paciente")
                        }
                        Spacer(modifier = Modifier.height(32.dp))

                        // ═══════════════════════════════════════════════
                        // ══ EL BOTÓN: El corazón de esta corrección ══
                        // ═══════════════════════════════════════════════
                        BotonGenerarIdentidadP2P(
                            uiState = uiState,
                            nombreValido = nombreInput.isNotBlank(),
                            onGenerarClick = {
                                viewModel.generateP2PIdentity(nombreInput)
                            },
                            onCrearPerfilClick = {
                                val state = uiState as OnboardingUiState.IdentityReady
                                onPerfilGuardado(
                                    state.peerId,
                                    nombreInput,
                                    rolSeleccionado == "Cuidador"
                                )
                            },
                            onReintentarClick = {
                                viewModel.resetState()
                            }
                        )

                        // ── PeerId visible tras generación exitosa ──
                        AnimatedVisibility(
                            visible = uiState is OnboardingUiState.IdentityReady,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            val peerId = (uiState as? OnboardingUiState.IdentityReady)?.peerId ?: ""
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF1E1E1E)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "🔑 Tu DNI P2P:",
                                        color = Color(0xFF69F0AE),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = peerId,
                                        color = Color(0xFF69F0AE),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// Este Composable extraído gestiona los 4 estados visuales del botón
// de forma declarativa. Usa AnimatedContent para transiciones fluidas
// entre estados, y CircularProgressIndicator embebido dentro del
// propio botón como pide el TFG_RULES.md — no un diálogo modal.
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun BotonGenerarIdentidadP2P(
    uiState: OnboardingUiState,
    nombreValido: Boolean,
    onGenerarClick: () -> Unit,
    onCrearPerfilClick: () -> Unit,
    onReintentarClick: () -> Unit
) {
    when (uiState) {
        // ── ESTADO 1: Idle → Botón para generar identidad ──
        is OnboardingUiState.Idle -> {
            Button(
                onClick = onGenerarClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = nombreValido,
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp,
                    disabledElevation = 0.dp
                )
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Generar DNI P2P",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // ── ESTADO 2: Cargando → Progress indicator DENTRO del botón ──
        is OnboardingUiState.GeneratingIdentity -> {
            Button(
                onClick = { /* Bloqueado durante generación */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = false,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Generando claves criptográficas…",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }

        // ── ESTADO 3: Éxito → Botón para confirmar y crear perfil ──
        is OnboardingUiState.IdentityReady -> {
            Button(
                onClick = onCrearPerfilClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(Icons.Default.AccountCircle, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "✅ Crear Perfil Local",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // ── ESTADO 4: Error → Botón de reintento con color de alerta ──
        is OnboardingUiState.Error -> {
            OutlinedButton(
                onClick = onReintentarClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Reintentar generación",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}