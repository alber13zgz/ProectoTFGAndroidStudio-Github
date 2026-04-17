package com.alberto.medp2p_poc

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Medicamento // 👉 IMPORTAMOS TU MODELO DE MEDICAMENTO
import io.libp2p.core.PeerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// =========================================================================
// === FASE 1: PANTALLA DE LOGIN / REGISTRO LOCAL ==========================
// =========================================================================

@Composable
fun PantallaLoginLocal(
    peerIdGeneradoAutomatico: PeerId?,
    onPerfilGuardado: (String, String, Boolean) -> Unit
) {
    var nombreInput by remember { mutableStateOf("") }
    var rolSeleccionado by remember { mutableStateOf("Cuidador") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.AccountBox, contentDescription = "Logo", modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("MedP2P", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Red Médica Descentralizada", fontSize = 16.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(48.dp))

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Configuración del Dispositivo", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Este paso creará tu perfil criptográfico. No hay servidores centrales.", fontSize = 12.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedTextField(value = nombreInput, onValueChange = { nombreInput = it }, label = { Text("Tu Nombre Completo") }, leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Selecciona tu rol:", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (rolSeleccionado == "Cuidador"), onClick = { rolSeleccionado = "Cuidador" })
                        Text("Profesional")
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = (rolSeleccionado == "Paciente"), onClick = { rolSeleccionado = "Paciente" })
                        Text("Paciente")
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { if (nombreInput.isNotBlank() && peerIdGeneradoAutomatico != null) onPerfilGuardado(peerIdGeneradoAutomatico.toString(), nombreInput, rolSeleccionado == "Cuidador") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = nombreInput.isNotBlank() && peerIdGeneradoAutomatico != null
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (peerIdGeneradoAutomatico == null) "Generando DNI P2P..." else "Crear Perfil Local")
                    }
                }
            }
        }
    }
}

// =========================================================================
// === FASE 2: NAVEGACIÓN Y PANTALLAS PRINCIPALES ==========================
// =========================================================================

data class Paciente(val id: UUID = UUID.randomUUID(), val alias: String, val peerIdGlobal: String)

sealed class Ruta(val ruta: String, val titulo: String, val icono: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Ruta("dashboard", "Inicio", Icons.Default.Home)
    object Directorio : Ruta("directorio", "Pacientes", Icons.Default.AccountBox)
    object Medicinas : Ruta("medicinas", "Vademécum", Icons.Default.List) // 👉 NUEVA PESTAÑA
    object Chat : Ruta("chat", "Historial", Icons.Default.Send)
}

@Composable
fun AppNavegacion(miDireccionVisible: String, direccionDestinoAutomatica: String, logConsola: String, onEnviarMensaje: (String, String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()

    val historialMensajes = remember { mutableStateListOf<MedicalRecord>() }
    var direccionDestinoActual by remember { mutableStateOf(direccionDestinoAutomatica) }

    fun recargarHistorial() {
        if (direccionDestinoActual.isNotBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                val dbHelper = com.alberto.medp2p_poc.data.db.AppDatabaseHelper(context)
                val peerIdDestino = direccionDestinoActual.substringAfterLast("/")
                val historialDB = dbHelper.obtenerHistorial(peerIdDestino)

                withContext(Dispatchers.Main) {
                    historialMensajes.clear()
                    historialMensajes.addAll(historialDB)
                }
            }
        }
    }

    LaunchedEffect(direccionDestinoActual) { recargarHistorial() }

    Scaffold(bottomBar = { BarraNavegacion(navController) }) { paddingValues ->
        NavHost(navController = navController, startDestination = Ruta.Dashboard.ruta, modifier = Modifier.padding(paddingValues)) {
            composable(Ruta.Dashboard.ruta) { PantallaDashboard(miDireccionVisible, logConsola) }

            composable(Ruta.Directorio.ruta) {
                PantallaDirectorio(onPacienteSeleccionado = { peerId ->
                    direccionDestinoActual = peerId
                    navController.navigate(Ruta.Chat.ruta)
                })
            }

            // 👉 INYECTAMOS LA NUEVA PANTALLA EN LA NAVEGACIÓN
            composable(Ruta.Medicinas.ruta) { PantallaVademecum() }

            composable(Ruta.Chat.ruta) {
                PantallaChat(
                    direccionDestino = direccionDestinoActual,
                    historial = historialMensajes,
                    onEnviar = { destino, mensaje ->
                        onEnviarMensaje(destino, mensaje)
                        coroutineScope.launch {
                            delay(300)
                            recargarHistorial()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun BarraNavegacion(navController: NavHostController) {
    // 👉 AÑADIDA RUTA.MEDICINAS A LA BARRA INFERIOR
    val items = listOf(Ruta.Dashboard, Ruta.Directorio, Ruta.Medicinas, Ruta.Chat)
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val rutaActual = navBackStackEntry?.destination?.route
        items.forEach { pantalla ->
            NavigationBarItem(
                icon = { Icon(pantalla.icono, contentDescription = pantalla.titulo) },
                label = { Text(pantalla.titulo) },
                selected = rutaActual == pantalla.ruta,
                onClick = { navController.navigate(pantalla.ruta) { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } }
            )
        }
    }
}

@Composable
fun PantallaDashboard(miDireccionVisible: String, logConsola: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Panel de Control", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Resumen de tu turno actual", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        val conectadoWAN = miDireccionVisible.contains("p2p-circuit")
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (conectadoWAN) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (conectadoWAN) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = if (conectadoWAN) Color(0xFF2E7D32) else Color(0xFFE65100), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (conectadoWAN) "Conectado a Red Global" else "Red Local (Offline)", fontWeight = FontWeight.Bold, color = if (conectadoWAN) Color(0xFF2E7D32) else Color(0xFFE65100))
                    Text("Matrícula: ${miDireccionVisible.take(20)}...", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { }, modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Vincular") }
            OutlinedButton(
                onClick = { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, miDireccionVisible) }; context.startActivity(Intent.createChooser(intent, "Compartir ID")) },
                modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(12.dp)
            ) { Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Mi ID") }
        }
        Spacer(Modifier.height(32.dp))
        Text("Log de Sincronización P2P", style = MaterialTheme.typography.labelMedium)
        Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Text(text = logConsola, color = Color(0xFF69F0AE), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
        }
    }
}

@Composable
fun PantallaChat(direccionDestino: String, historial: List<MedicalRecord>, onEnviar: (String, String) -> Unit) {
    var mensajeInput by remember { mutableStateOf("") }
    val formatter = SimpleDateFormat("HH:mm - dd/MM", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(value = direccionDestino, onValueChange = {}, label = { Text("ID del Destinatario") }, modifier = Modifier.fillMaxWidth(), singleLine = true, readOnly = true)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(historial.size) { index ->
                val mensaje = historial[historial.size - 1 - index]
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mensaje.isMine) Arrangement.End else Arrangement.Start) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (mensaje.isMine) Color(0xFFDCF8C6) else Color(0xFFE0E0E0))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = mensaje.text, color = Color.Black)
                            Text(
                                text = formatter.format(Date(mensaje.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = mensajeInput, onValueChange = { mensajeInput = it }, label = { Text("Pauta Médica o Mensaje...") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (mensajeInput.isNotBlank() && direccionDestino.isNotBlank()) { onEnviar(direccionDestino, mensajeInput); mensajeInput = "" } },
                enabled = mensajeInput.isNotBlank() && direccionDestino.isNotBlank()
            ) { Icon(Icons.Default.Send, contentDescription = "Enviar") }
        }
    }
}

@Composable
fun PantallaDirectorio(onPacienteSeleccionado: (String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var listaPacientes by remember { mutableStateOf<List<Paciente>>(emptyList()) }
    var mostrarDialogo by remember { mutableStateOf(false) }
    var nuevoAlias by remember { mutableStateOf("") }
    var nuevoPeerId by remember { mutableStateOf("") }

    fun recargarLista() {
        coroutineScope.launch(Dispatchers.IO) {
            val dbHelper = com.alberto.medp2p_poc.data.db.AppDatabaseHelper(context)
            val pacientesDB = dbHelper.obtenerTodosLosPacientes()
            withContext(Dispatchers.Main) { listaPacientes = pacientesDB }
        }
    }

    LaunchedEffect(Unit) { recargarLista() }

    if (mostrarDialogo) {
        AlertDialog(
            onDismissRequest = { mostrarDialogo = false },
            title = { Text("Vincular Nuevo Paciente", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Introduce los datos de red.", fontSize = 14.sp, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = nuevoAlias, onValueChange = { nuevoAlias = it }, label = { Text("Nombre / Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = nuevoPeerId, onValueChange = { nuevoPeerId = it }, label = { Text("ID de Red (PeerID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nuevoAlias.isNotBlank() && nuevoPeerId.isNotBlank()) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val dbHelper = com.alberto.medp2p_poc.data.db.AppDatabaseHelper(context)
                                dbHelper.vincularPacienteExterno(nuevoPeerId, nuevoAlias)
                                withContext(Dispatchers.Main) { mostrarDialogo = false; nuevoAlias = ""; nuevoPeerId = ""; recargarLista() }
                            }
                        }
                    },
                    enabled = nuevoAlias.isNotBlank() && nuevoPeerId.isNotBlank()
                ) { Text("Vincular") }
            },
            dismissButton = { TextButton(onClick = { mostrarDialogo = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { mostrarDialogo = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, contentDescription = "Añadir", tint = Color.White) } }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Text("Mis Pacientes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (listaPacientes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay pacientes vinculados.\nPulsa el botón +.", textAlign = TextAlign.Center, color = Color.Gray) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(listaPacientes.size) { index ->
                        val paciente = listaPacientes[index]
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column { Text(paciente.alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("ID: ${paciente.peerIdGlobal.take(15)}...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                                }
                                Button(onClick = { onPacienteSeleccionado(paciente.peerIdGlobal) }) { Text("Historial") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// === FASE 3: NUEVA PANTALLA DE VADEMÉCUM (MEDICAMENTOS) ==================
// =========================================================================

@Composable
fun PantallaVademecum() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var listaMedicamentos by remember { mutableStateOf<List<Medicamento>>(emptyList()) }

    var mostrarDialogo by remember { mutableStateOf(false) }
    var nombre by remember { mutableStateOf("") }
    var principio by remember { mutableStateOf("") }
    var concentracion by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }

    fun recargarLista() {
        coroutineScope.launch(Dispatchers.IO) {
            val dbHelper = com.alberto.medp2p_poc.data.db.AppDatabaseHelper(context)
            val medsDB = dbHelper.obtenerVademecum()
            withContext(Dispatchers.Main) { listaMedicamentos = medsDB }
        }
    }

    LaunchedEffect(Unit) { recargarLista() }

    if (mostrarDialogo) {
        AlertDialog(
            onDismissRequest = { mostrarDialogo = false },
            title = { Text("Añadir Medicamento", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre Comercial (ej. Gelocatil)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = principio, onValueChange = { principio = it }, label = { Text("Principio Activo (ej. Paracetamol)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = concentracion, onValueChange = { concentracion = it }, label = { Text("Dosis (mg)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = stock, onValueChange = { stock = it }, label = { Text("Stock") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val concFloat = concentracion.toFloatOrNull() ?: 0f
                        val stockInt = stock.toIntOrNull() ?: 0
                        if (nombre.isNotBlank() && principio.isNotBlank()) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val dbHelper = com.alberto.medp2p_poc.data.db.AppDatabaseHelper(context)
                                val nuevoMed = Medicamento(
                                    nombreComercial = nombre,
                                    principleActivo = principio,
                                    concentracionMg = concFloat,
                                    stockActual = stockInt
                                )
                                dbHelper.insertarMedicamento(nuevoMed)
                                withContext(Dispatchers.Main) {
                                    mostrarDialogo = false
                                    nombre = ""; principio = ""; concentracion = ""; stock = ""
                                    recargarLista()
                                }
                            }
                        }
                    },
                    enabled = nombre.isNotBlank() && principio.isNotBlank()
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { mostrarDialogo = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { mostrarDialogo = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, contentDescription = "Añadir", tint = Color.White) } }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Text("Vademécum Local", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (listaMedicamentos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay medicamentos registrados.\nPulsa el botón + para añadir uno.", textAlign = TextAlign.Center, color = Color.Gray) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(listaMedicamentos.size) { index ->
                        val med = listaMedicamentos[index]
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(med.nombreComercial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${med.principleActivo} - ${med.concentracionMg}mg", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Stock", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text("${med.stockActual}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (med.stockActual > 5) Color(0xFF2E7D32) else Color.Red)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}