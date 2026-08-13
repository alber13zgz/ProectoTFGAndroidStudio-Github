package com.alberto.medp2p_poc.ui.auth

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alberto.medp2p_poc.data.model.UserRole

private object ClinicalColors {
    val PrimaryBlue = Color(0xFF005FB8)
    val PrimaryBlueDark = Color(0xFF003D7A)
    val PrimaryBlueLight = Color(0xFF4A90D9)
    val AccentMint = Color(0xFF00C9A7)
    val SurfaceWhite = Color(0xFFF8FAFE)
    val CardWhite = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1C2B)
    val TextSecondary = Color(0xFF6B7280)
    val TextOnPrimary = Color(0xFFFFFFFF)
    val ErrorRed = Color(0xFFDC3545)
    val DividerLight = Color(0xFFE8EDF2)
}

// ══════════════════════════════════════════════════════════════
// ══ PANTALLA PRINCIPAL DE AUTENTICACION ══════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Error) {
            val error = uiState as AuthUiState.Error
            snackbarHostState.showSnackbar(
                message = error.userMessage,
                actionLabel = "Entendido",
                duration = SnackbarDuration.Long
            ).let {
                if (it == SnackbarResult.ActionPerformed || it == SnackbarResult.Dismissed) {
                    viewModel.dismissError()
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
                    containerColor = ClinicalColors.ErrorRed,
                    contentColor = Color.White,
                    actionColor = Color.White.copy(alpha = 0.9f)
                )
            }
        },
        containerColor = ClinicalColors.SurfaceWhite
    ) { paddingValues ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ClinicalColors.PrimaryBlue,
                                ClinicalColors.PrimaryBlueDark
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                AuthHeader()
                Spacer(modifier = Modifier.height(32.dp))

                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(400)) +
                                slideInVertically(
                                    animationSpec = tween(400),
                                    initialOffsetY = { it / 8 }
                                ))
                            .togetherWith(fadeOut(animationSpec = tween(200)))
                    },
                    label = "AuthContentTransition"
                ) { state ->
                    when (state) {
                        is AuthUiState.CheckingLocalProfile -> {
                            LoadingCard(message = "Comprobando dispositivo...")
                        }
                        is AuthUiState.ShowRegistration -> {
                            RegistrationCard(
                                onRegister = { name, password, role ->
                                    viewModel.register(name, password, role)
                                }
                            )
                        }
                        is AuthUiState.ShowLogin -> {
                            LoginCard(
                                displayName = state.displayName,
                                onLogin = { password ->
                                    viewModel.login(password)
                                },
                                onResetProfile = {
                                    viewModel.resetProfile()
                                }
                            )
                        }
                        is AuthUiState.Processing -> {
                            LoadingCard(message = state.message)
                        }
                        is AuthUiState.Authenticated -> {
                            SuccessCard(displayName = state.session.displayName)
                        }
                        is AuthUiState.Error -> {
                            LoadingCard(message = "Un momento...")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ CABECERA CON LOGO MEDICO ════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun AuthHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f))
                .clip(CircleShape)
                .background(ClinicalColors.CardWhite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.LocalHospital,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = ClinicalColors.PrimaryBlue
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "MedP2P",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = ClinicalColors.TextOnPrimary
        )
        Text(
            text = "Salud conectada, datos protegidos",
            fontSize = 14.sp,
            color = ClinicalColors.TextOnPrimary.copy(alpha = 0.8f)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE REGISTRO ═════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun RegistrationCard(
    onRegister: (name: String, password: String, role: UserRole) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf(UserRole.PROFESSIONAL) }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = ClinicalColors.CardWhite)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = ClinicalColors.AccentMint
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Crear Cuenta",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ClinicalColors.TextPrimary
            )
            Text(
                text = "",
                fontSize = 13.sp,
                color = ClinicalColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre completo") },
                placeholder = { Text("Ej: Dra. Maria Lopez") },
                leadingIcon = {
                    Icon(Icons.Outlined.Person, null, tint = ClinicalColors.PrimaryBlue)
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = clinicalTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Contrasena de seguridad") },
                placeholder = { Text("Minimo 6 caracteres") },
                leadingIcon = {
                    Icon(Icons.Outlined.Lock, null, tint = ClinicalColors.PrimaryBlue)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = ClinicalColors.TextSecondary
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = clinicalTextFieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )

            if (password.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PasswordStrengthIndicator(password = password)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Cual es tu perfil?",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ClinicalColors.TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RoleCard(
                    icon = Icons.Outlined.MedicalServices,
                    label = "Profesional",
                    subtitle = "Sanitario",
                    isSelected = selectedRole == UserRole.PROFESSIONAL,
                    onClick = { selectedRole = UserRole.PROFESSIONAL },
                    modifier = Modifier.weight(1f)
                )
                RoleCard(
                    icon = Icons.Outlined.Person,
                    label = "Paciente",
                    subtitle = "Particular",
                    isSelected = selectedRole == UserRole.PATIENT,
                    onClick = { selectedRole = UserRole.PATIENT },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onRegister(name, password, selectedRole) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = name.isNotBlank() && password.length >= 6,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClinicalColors.PrimaryBlue,
                    disabledContainerColor = ClinicalColors.PrimaryBlue.copy(alpha = 0.3f)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp, pressedElevation = 1.dp, disabledElevation = 0.dp
                )
            ) {
                Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Crear Cuenta", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE LOGIN ════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun LoginCard(
    displayName: String,
    onLogin: (password: String) -> Unit,
    onResetProfile: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = ClinicalColors.CardWhite)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Avatar con iniciales ──
            val initials = displayName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .joinToString("")

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(ClinicalColors.PrimaryBlue, ClinicalColors.PrimaryBlueLight)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Hola, $displayName",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = ClinicalColors.TextPrimary
            )
            Text(
                text = "Introduce tu contraseña para continuar",
                fontSize = 13.sp,
                color = ClinicalColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Contraseña") },
                leadingIcon = {
                    Icon(Icons.Filled.Lock, null, tint = ClinicalColors.PrimaryBlue)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = ClinicalColors.TextSecondary
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = clinicalTextFieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (password.isNotBlank()) onLogin(password)
                    }
                )
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { onLogin(password) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = password.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClinicalColors.PrimaryBlue,
                    disabledContainerColor = ClinicalColors.PrimaryBlue.copy(alpha = 0.3f)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp, pressedElevation = 1.dp, disabledElevation = 0.dp
                )
            ) {
                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Acceso con Contraseña", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // ── Texto de confianza ──
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.Shield, null,
                    modifier = Modifier.size(14.dp),
                    tint = ClinicalColors.AccentMint
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Tus datos estan cifrados en este dispositivo",
                    fontSize = 11.sp,
                    color = ClinicalColors.TextSecondary
                )
            }

            // ══════════════════════════════════════════════════
            // ══ BOTON CAMBIAR DE CUENTA (NUEVO) ═════════════
            // ══════════════════════════════════════════════════
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = ClinicalColors.DividerLight, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.SwitchAccount,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = ClinicalColors.TextSecondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Iniciar sesion con otra cuenta",
                    fontSize = 14.sp,
                    color = ClinicalColors.TextSecondary
                )
            }
        }
    }

    // ── Dialogo de confirmacion (para evitar borrado accidental) ──
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            icon = {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = ClinicalColors.ErrorRed
                )
            },
            title = {
                Text(
                    text = "Cambiar de cuenta",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Se eliminara el perfil actual de este dispositivo y las claves de seguridad asociadas. Los datos clinicos locales se conservan. Esta accion no se puede deshacer.",
                    fontSize = 14.sp,
                    color = ClinicalColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onResetProfile()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClinicalColors.ErrorRed
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Eliminar y crear nueva cuenta")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE CARGA ════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun LoadingCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = ClinicalColors.CardWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = ClinicalColors.PrimaryBlue,
                strokeWidth = 4.dp,
                trackColor = ClinicalColors.DividerLight
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = ClinicalColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Esto puede tardar unos segundos",
                fontSize = 13.sp,
                color = ClinicalColors.TextSecondary
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ TARJETA DE EXITO ════════════════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun SuccessCard(displayName: String) {
    val scale = remember { Animatable(0.8f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = ClinicalColors.CardWhite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ClinicalColors.AccentMint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalHospital,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = ClinicalColors.AccentMint
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Bienvenido/a!",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = ClinicalColors.TextPrimary
            )
            Text(
                text = displayName,
                fontSize = 16.sp,
                color = ClinicalColors.PrimaryBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Tu entorno clinico esta preparado.\nConectando con la red segura...",
                fontSize = 13.sp,
                color = ClinicalColors.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = ClinicalColors.AccentMint,
                trackColor = ClinicalColors.DividerLight
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ══ COMPONENTES REUTILIZABLES ═══════════════════════════════
// ══════════════════════════════════════════════════════════════

@Composable
private fun RoleCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ClinicalColors.PrimaryBlue else ClinicalColors.DividerLight,
        animationSpec = tween(250), label = "RoleBorderColor"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) ClinicalColors.PrimaryBlue.copy(alpha = 0.06f)
        else ClinicalColors.SurfaceWhite,
        animationSpec = tween(250), label = "RoleBgColor"
    )

    OutlinedCard(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (isSelected) 2.dp else 1.dp,
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        ),
        colors = CardDefaults.outlinedCardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon, contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (isSelected) ClinicalColors.PrimaryBlue else ClinicalColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label, fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) ClinicalColors.PrimaryBlue else ClinicalColors.TextPrimary
            )
            Text(text = subtitle, fontSize = 11.sp, color = ClinicalColors.TextSecondary)
        }
    }
}

@Composable
private fun PasswordStrengthIndicator(password: String) {
    val strength = calculatePasswordStrength(password)
    val (label, color) = when (strength) {
        0 -> "Muy debil" to ClinicalColors.ErrorRed
        1 -> "Debil" to Color(0xFFFF9800)
        2 -> "Aceptable" to Color(0xFFFFC107)
        else -> "Fuerte" to ClinicalColors.AccentMint
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) { index ->
                val segmentColor by animateColorAsState(
                    targetValue = if (index <= strength - 1) color else ClinicalColors.DividerLight,
                    animationSpec = tween(300), label = "StrengthSegment$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(segmentColor)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label, fontSize = 11.sp, color = color,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

private fun calculatePasswordStrength(password: String): Int {
    if (password.length < 6) return 0
    var score = 1
    if (password.length >= 8 && password.any { it.isUpperCase() }) score++
    if (password.any { it.isDigit() } && password.any { !it.isLetterOrDigit() }) score++
    return score
}

@Composable
private fun clinicalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ClinicalColors.PrimaryBlue,
    unfocusedBorderColor = ClinicalColors.DividerLight,
    focusedLabelColor = ClinicalColors.PrimaryBlue,
    unfocusedLabelColor = ClinicalColors.TextSecondary,
    cursorColor = ClinicalColors.PrimaryBlue,
    focusedLeadingIconColor = ClinicalColors.PrimaryBlue,
    unfocusedLeadingIconColor = ClinicalColors.TextSecondary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = ClinicalColors.SurfaceWhite.copy(alpha = 0.5f)
)