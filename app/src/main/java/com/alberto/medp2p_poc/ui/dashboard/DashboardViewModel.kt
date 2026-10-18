package com.alberto.medp2p_poc.ui.dashboard

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.Patient
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.data.model.UserSession
import com.alberto.medp2p_poc.data.p2p.P2PMessagingService
import com.alberto.medp2p_poc.data.p2p.PairingCode
import com.alberto.medp2p_poc.data.p2p.SuministroPayload
import com.alberto.medp2p_poc.data.model.AlertaDosis
import com.alberto.medp2p_poc.data.model.ConflictoDosis
import kotlinx.serialization.json.Json
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper.MedicoVinculado


sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting   : ConnectionStatus()
    object Connected    : ConnectionStatus()
    data class Error(val hint: String) : ConnectionStatus()
}

/** Resultado de un intento de vinculación, para mostrarlo en la UI. */
data class LinkResult(val ok: Boolean, val message: String)

/** Intento de registrar una dosis que ya consta como administrada (se pide confirmación). */
data class DosisYaRegistrada(
    val alerta: AlertaDosis,
    val quien: String,
    val cuando: Long,
    val temperatura: Float?,
    val sintomas: String?,
    val notas: String?
)

data class DashboardData(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val patientCount: Int = 0,
    val pendingNotifications: Int = 0,
    val lastSyncTimestamp: Long? = null,
    val lastLoginAt: Long? = null,
    val displayName: String = "",
    val photoUri: String = "",
    val role: UserRole = UserRole.PROFESSIONAL,
    val ownerPeerId: String = ""
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _dashboard = MutableStateFlow(DashboardData())
    val dashboard: StateFlow<DashboardData> = _dashboard.asStateFlow()

    // ── FIX UI REACTIVA: StateFlow para mantener la lista de médicos en vivo ──
    private val _medicosVinculados = MutableStateFlow<List<MedicoVinculado>>(emptyList())
    val medicosVinculados: StateFlow<List<MedicoVinculado>> = _medicosVinculados.asStateFlow()

    // ── StateFlow de Alertas Pendientes (PAUTAS MÉDICAS) ──
    private val _alertasPendientes = MutableStateFlow<List<AlertaDosis>>(emptyList())
    val alertasPendientes: StateFlow<List<AlertaDosis>> = _alertasPendientes.asStateFlow()

    // Dosis previstas registradas dos veces en las últimas 48 h (posible sobredosificación)
    private val _conflictosDosis = MutableStateFlow<List<ConflictoDosis>>(emptyList())
    val conflictosDosis: StateFlow<List<ConflictoDosis>> = _conflictosDosis.asStateFlow()

    // Al registrar una toma de una dosis que ya consta como dada: la UI pide confirmación
    private val _dosisYaRegistrada = MutableSharedFlow<DosisYaRegistrada>(extraBufferCapacity = 2)
    val dosisYaRegistrada: SharedFlow<DosisYaRegistrada> = _dosisYaRegistrada

    private val _pautasPacienteActivas = MutableStateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>>(emptyList())
    val pautasPacienteActivas: StateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>> = _pautasPacienteActivas.asStateFlow()

    // ── AÑADIDO (PASO 3): StateFlow de la lista de Pacientes del Doctor (para el Dropdown) ──
    private val _pacientesDoctor = MutableStateFlow<List<com.alberto.medp2p_poc.data.model.Patient>>(emptyList())
    val pacientesDoctor: StateFlow<List<com.alberto.medp2p_poc.data.model.Patient>> = _pacientesDoctor.asStateFlow()

    private fun loadAlertasPendientes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val owner = _dashboard.value.ownerPeerId
                val ahora = System.currentTimeMillis()
                _alertasPendientes.value = dbHelper.obtenerAlertasPendientes(owner, ahora)
                _conflictosDosis.value = dbHelper.obtenerConflictosDosis(owner, ahora - 48L * 3_600_000)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando alertas: ${e.message}")
            }
        }
    }

    private fun loadPacientesDoctor() {
        viewModelScope.launch(Dispatchers.IO) {
            _pacientesDoctor.value = dbHelper.obtenerPacientesClinico(_dashboard.value.ownerPeerId)
        }
    }

    var activeHost: Host? = null
        private set

    private var storedPrivateKey: PrivKey? = null
    private val dbHelper = AppDatabaseHelper(application)
    private lateinit var messagingService: P2PMessagingService

    val incomingMessages: SharedFlow<MedicalRecord>?
        get() = if (::messagingService.isInitialized) messagingService.incomingMessages else null

    val currentOwnerPeerId: String get() = _dashboard.value.ownerPeerId

    private val _linkEvents = MutableSharedFlow<LinkResult>(extraBufferCapacity = 4)
    val linkEvents: SharedFlow<LinkResult> = _linkEvents

    companion object {
        private const val TAG = "P2P_NETWORK"
        private const val RELAY_BASE =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWGYM8gTNoodKe3YqBAoxanGLJS44YsA118iD2juHtuXhV"
    }
    private fun loadPautasPacienteActivas() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val owner = _dashboard.value.ownerPeerId
                _pautasPacienteActivas.value = dbHelper.obtenerPautasActivasPorPaciente(owner, owner)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando pautas del paciente: ${e.message}")
            }
        }
    }
    fun initialize(session: UserSession, privateKey: PrivKey? = null) {
        storedPrivateKey = privateKey
        _dashboard.value = _dashboard.value.copy(
            displayName = session.displayName,
            role        = session.role,
            photoUri    = session.photoUri,
            ownerPeerId = session.peerId,
            lastLoginAt = session.lastLoginAt
        )
        messagingService = P2PMessagingService(getApplication(), dbHelper)

        viewModelScope.launch {
            messagingService.syncEvents.collect { timestamp ->
                _dashboard.value = _dashboard.value.copy(lastSyncTimestamp = timestamp)
                Log.d(TAG, "[SYNC] Última sincronización actualizada: $timestamp")

                // Recargamos los datos al haber actividad P2P
                loadMedicosVinculados()
                loadAlertasPendientes()
                loadPautasPacienteActivas()
                loadPacientesDoctor()
            }
        }

        // Reconexión automática: el servicio reintenta el relay en segundo plano
        // (p. ej. al quitar el modo avión) y aquí se refleja en la interfaz
        viewModelScope.launch {
            messagingService.relayConectado.collect { conectado ->
                val actual = _dashboard.value.connectionStatus
                if (conectado) {
                    activeHost = messagingService.currentHost
                    if (actual !is ConnectionStatus.Connected)
                        _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Connected)
                } else if (actual is ConnectionStatus.Connected) {
                    _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Error(
                        "Sin conexión. Los cambios se guardan y se enviarán al recuperarla."))
                }
            }
        }

        loadDashboardCounters()
        loadMedicosVinculados()
        loadAlertasPendientes()
        loadPacientesDoctor() // Carga inicial para el formulario
        startP2PNode(privateKey)
    }

    private fun loadMedicosVinculados() {
        viewModelScope.launch(Dispatchers.IO) {
            _medicosVinculados.value = dbHelper.obtenerMedicosVinculados(_dashboard.value.ownerPeerId)
        }
    }

    private fun startP2PNode(privateKey: PrivKey?) {
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Connecting)
            try {
                // Sin la identidad real no se puede firmar: una clave aleatoria haría
                // que todos los pares rechazaran los mensajes, así que se falla claramente
                val privKey: PrivKey = checkNotNull(privateKey) { "Identidad criptográfica no disponible" }
                val node = messagingService.start(privKey, _dashboard.value.ownerPeerId)
                activeHost = node
                Log.i(TAG, "✅ Nodo P2P activo. PeerId=${node.peerId}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus  = ConnectionStatus.Connected,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error arrancando nodo: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error(
                        "No se pudo iniciar la conexión segura. La app funciona en modo local."
                    )
                )
            }
        }
    }

    fun retryConnection() {
        if (_dashboard.value.connectionStatus is ConnectionStatus.Connecting) return
        viewModelScope.launch(Dispatchers.IO) {
            _dashboard.value = _dashboard.value.copy(connectionStatus = ConnectionStatus.Connecting)
            try {
                val privKey = checkNotNull(storedPrivateKey) { "Identidad criptográfica no disponible" }
                activeHost?.stop()
                activeHost = null
                val node = messagingService.start(privKey, _dashboard.value.ownerPeerId)
                activeHost = node
                Log.i(TAG, "✅ Reconexión exitosa.")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus  = ConnectionStatus.Connected,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Retry fallido: ${e.stackTraceToString()}")
                _dashboard.value = _dashboard.value.copy(
                    connectionStatus = ConnectionStatus.Error("No se pudo reconectar.")
                )
            }
        }
    }

    suspend fun sendRecord(destinationCircuitAddr: String, record: MedicalRecord) = withContext(Dispatchers.IO) {
        messagingService.enviarRegistroMedico(destinationCircuitAddr.substringAfterLast("/"), record)
    }

    private fun loadDashboardCounters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val owner    = _dashboard.value.ownerPeerId
                val patients = dbHelper.obtenerPacientesClinico(owner)
                _dashboard.value = _dashboard.value.copy(patientCount = patients.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando contadores: ${e.message}")
            }
        }
    }

    fun refreshCounters() { loadDashboardCounters() }

    /**
     * CUIDADOR: desvincula a un paciente. Se borra ya del directorio local y se
     * encola el aviso UNLINK_DOCTOR para que el paciente revoque la autorización y
     * la clave de este cuidador; si no está conectado, le llega al volver. La clave
     * del paciente se borra en cuanto confirma el aviso.
     */
    fun desvincularPaciente(patientId: String, patientPeerId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            messagingService.enviarDesvinculacion(patientPeerId)
            dbHelper.eliminarPacienteClinico(patientId, revocarClave = false)
            loadDashboardCounters()
            loadPacientesDoctor()
            loadAlertasPendientes()
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /**
     * Renueva ahora la clave de cifrado del dispositivo y la distribuye a los
     * pares conectados; los demás la recibirán cuando vuelvan a conectarse.
     */
    fun rotarClaveCifrado(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val node = activeHost
            val mensaje = if (node == null) {
                "Sin conexión: la clave solo se puede renovar con conexión."
            } else {
                val pendientes = messagingService.rotarClaveCifrado(node)
                val epoch = messagingService.claveCifradoActual().epoch
                if (pendientes == 0) "Clave renovada (época $epoch) y confirmada por todos tus vínculos."
                else "Clave renovada (época $epoch). $pendientes vínculo(s) la recibirán al conectarse."
            }
            withContext(Dispatchers.Main) { onResult(mensaje) }
        }
    }

    /**
     * PACIENTE: genera un código de vinculación de un solo uso (válido 15 min)
     * para mostrarlo en el QR. Cada apertura del diálogo crea uno nuevo.
     */
    suspend fun generarCodigoVinculacion(): String = withContext(Dispatchers.IO) {
        val owner = _dashboard.value.ownerPeerId
        val token = PairingCode.generarToken()
        val clave = messagingService.claveCifradoActual()
        dbHelper.guardarCodigoVinculacion(owner, token, System.currentTimeMillis() + PairingCode.VALIDEZ_MS)
        PairingCode.codificar(owner, token, clave.epoch, clave.publicKey)
    }

    /**
     * CUIDADOR: vincula un paciente a partir del código leído de su QR.
     * El paciente solo se guarda localmente si devuelve un ACK firmado, es decir,
     * si aceptó el código; así ambos directorios quedan siempre coherentes.
     */
    fun linkPatient(fullName: String, pairingCode: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val owner  = _dashboard.value.ownerPeerId
            val myName = _dashboard.value.displayName

            val code = PairingCode.parse(pairingCode)
            if (code == null) {
                _linkEvents.emit(LinkResult(false,
                    "Código no válido. Escanea el QR del paciente o copia su código completo."))
                return@launch
            }
            val node = activeHost
            if (node == null) {
                _linkEvents.emit(LinkResult(false, "Sin conexión: la vinculación necesita red."))
                return@launch
            }

            val patient = Patient(
                fullName         = fullName.trim(),
                peerId           = code.peerId,
                allergies        = allergies.trim(),
                avatarColorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8,
                lastSyncAt       = System.currentTimeMillis()
            )
            try {
                // Se guarda ANTES de enviar: al aceptar, el paciente envía enseguida
                // su historial firmado y este dispositivo debe reconocerlo ya como paciente
                dbHelper.insertarPacienteClinico(patient, owner)
                val aceptado = messagingService.sendLinkDoctorMessage(
                    host            = node,
                    patientPeerId   = code.peerId,
                    doctorName      = myName,
                    pairingToken    = code.token,
                    patientKeyEpoch = code.keyEpoch,
                    patientKeyPub   = code.keyPub
                )
                if (!aceptado) {
                    dbHelper.eliminarPacienteClinico(patient.id)
                    _linkEvents.emit(LinkResult(false,
                        "El paciente no aceptó la vinculación: debe estar conectado y el código no puede estar caducado ni usado."))
                    return@launch
                }
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadDashboardCounters()
                loadPacientesDoctor() // Recargar lista para el Dropdown
                _linkEvents.emit(LinkResult(true, "Paciente ${patient.fullName} vinculado."))
            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
                runCatching { dbHelper.eliminarPacienteClinico(patient.id) }
                _linkEvents.emit(LinkResult(false, "Error al vincular el paciente."))
            }
        }
    }

    fun updateProfile(newName: String, newPhotoUri: String) {
        if (newName.isBlank()) return
        val owner = _dashboard.value.ownerPeerId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.actualizarPerfil(owner, newName.trim(), newPhotoUri)
                _dashboard.value = _dashboard.value.copy(
                    displayName = newName.trim(),
                    photoUri    = newPhotoUri
                )
                Log.i(TAG, "Perfil actualizado: $newName | foto=$newPhotoUri")
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando perfil: ${e.message}")
            }
        }
    }

    fun copyPhotoToPrivateDir(context: Context, sourceUriString: String): String {
        return try {
            val sourceUri = android.net.Uri.parse(sourceUriString)
            val destFile  = File(context.filesDir, "profile_${_dashboard.value.ownerPeerId}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copiando foto: ${e.message}")
            ""
        }
    }

    suspend fun getMedicosVinculados(): List<MedicoVinculado> =
        withContext(Dispatchers.IO) {
            try {
                dbHelper.obtenerMedicosVinculados(_dashboard.value.ownerPeerId)
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando médicos vinculados: ${e.message}")
                emptyList()
            }
        }

    suspend fun getOwnAllergies(): String = withContext(Dispatchers.IO) {
        dbHelper.obtenerPacienteClinicoPorPeerId(
            peerId = _dashboard.value.ownerPeerId,
            ownerPeerId = _dashboard.value.ownerPeerId
        )?.allergies ?: ""
    }

    fun updateOwnAllergies(allergies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.actualizarAlergiasPaciente(
                peerId      = _dashboard.value.ownerPeerId,
                ownerPeerId = _dashboard.value.ownerPeerId,
                allergies   = allergies
            )
        }
    }

    fun crearPauta(
        patientPeerId: String,
        medicacion: String,
        dosis: String,
        intervaloHoras: Int, // <-- CAMBIADO: Antes frecuenciaDiaria
        fechaInicio: Long,
        fechaFin: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val owner = _dashboard.value.ownerPeerId
            val pauta = com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                patientPeerId       = patientPeerId,
                doctorCreatorPeerId = owner,
                medicacion          = medicacion,
                dosis               = dosis,
                intervaloHoras      = intervaloHoras, // <-- CAMBIADO
                fechaInicio         = fechaInicio,
                fechaFin            = fechaFin
            )
            dbHelper.insertarPautaMedicaV2(pauta)
            loadAlertasPendientes()
            Log.d(TAG, "✅ Pauta creada: ${pauta.medicacion}")

            // Offline-First: se encola aunque no haya conexión y se envía al recuperarla
            messagingService.enviarPauta(pauta)
        }
    }

    /**
     * Registra la toma de una dosis prevista. Si esa misma dosis ya consta como
     * administrada (por este u otro cuidador), no se registra: se emite
     * [dosisYaRegistrada] para que la UI pida confirmación explícita, y solo con
     * [forzar] = true se guarda (quedando marcada como conflicto para todos).
     */
    fun registrarSuministro(
        alerta: AlertaDosis,
        temperatura: Float? = null,
        sintomas: String? = null,
        notas: String? = null,
        forzar: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val owner = _dashboard.value.ownerPeerId
            val pauta = alerta.pauta
            val previa = dbHelper.obtenerTomaDeDosis(pauta.id, alerta.dosisProgramada)
            if (previa != null && !forzar) {
                val nombre = when {
                    previa.administradoPorPeerId == owner -> "ti"
                    else -> runCatching { json.decodeFromString<SuministroPayload>(previa.contenidoFirmado).administradoPorNombre }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: "otro cuidador"
                }
                _dosisYaRegistrada.emit(DosisYaRegistrada(alerta, nombre, previa.timestamp, temperatura, sintomas, notas))
                return@launch
            }
            val registro = com.alberto.medp2p_poc.data.model.RegistroSuministro(
                pautaId                  = pauta.id,
                patientPeerId            = pauta.patientPeerId,
                doctorAdministeredPeerId = owner,
                temperatura              = temperatura,
                sintomas                 = sintomas,
                notas                    = notas,
                dosisProgramada          = alerta.dosisProgramada
            )
            dbHelper.insertarRegistroSuministro(registro)
            loadAlertasPendientes()
            Log.d(TAG, "✅ Suministro registrado: pauta=${pauta.id} dosis=${alerta.dosisProgramada}" +
                if (previa != null) " (DUPLICADA, confirmada por el cuidador)" else "")

            // Offline-First: se encola aunque no haya conexión; el paciente lo
            // replicará a sus demás cuidadores
            messagingService.enviarSuministro(registro, _dashboard.value.displayName)
        }
    }

    /**
     * Comprueba ahora la conexión con el móvil del paciente (PING) para que la
     * "última sincronización" que se muestra al registrar una toma sea real.
     */
    fun comprobarSincronizacion(patientPeerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (::messagingService.isInitialized) messagingService.comprobarContacto(patientPeerId)
            loadPacientesDoctor()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCleared() {
        super.onCleared()
        if (::messagingService.isInitialized) messagingService.shutdown()
        activeHost?.stop()
        activeHost = null
    }

    fun shutdown() {
        if (::messagingService.isInitialized) messagingService.shutdown()
        activeHost?.stop()
        activeHost = null
        storedPrivateKey = null
        _dashboard.value = DashboardData()
    }
    fun eliminarCuenta(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val myPeerId = _dashboard.value.ownerPeerId
            val node     = activeHost

            // Notificar a cada médico vinculado antes de borrar datos
            if (node != null) {
                val medicos = dbHelper.obtenerMedicosVinculados(myPeerId)
                medicos.forEach { medico ->
                    val destAddr = "$RELAY_BASE/p2p-circuit/p2p/${medico.doctorPeerId}"
                    try {
                        messagingService.sendUnlinkPatientMessage(node, destAddr, myPeerId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notificando desvinculacion a ${medico.doctorPeerId}: ${e.message}")
                    }
                }
            }
            dbHelper.eliminarCuentaCompleta(myPeerId)
            Log.i(TAG, "Cuenta eliminada: $myPeerId")
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
    // ══════════════════════════════════════════════════════════════
    // ══ LÓGICA DE ESCANEO DE FÁRMACOS (HÍBRIDA) ═══════════════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Procesa un código de barras escaneado (EAN/UPC).
     * Sigue la arquitectura Híbrida/Offline-First:
     * 1. Consulta SQLite (Caché local ultrarrápida).
     * 2. Si no existe, consulta API pública (AEMPS) en 2º plano.
     * 3. Si la API responde, guarda en caché y devuelve el nombre.
     * 4. Si falla (sin red o desconocido), devuelve null.
     */
    suspend fun procesarCodigoEscaneado(codigoBarras: String): String? {
        return withContext(Dispatchers.IO) {
            // 1. Intento Local (Offline puro)
            val nombreLocal = dbHelper.obtenerNombreMedicamentoLocal(codigoBarras)
            if (nombreLocal != null) {
                Log.d(TAG, "[SCAN] Fármaco encontrado en CACHÉ LOCAL: $nombreLocal")
                return@withContext nombreLocal
            }

            // 2. Intento de Red (API CIMA)
            Log.d(TAG, "[SCAN] Fármaco no cacheado. Consultando API CIMA...")
            try {
                // El Código Nacional (CN) español de 6 cifras suele estar en las posiciones 7-12 de un EAN-13
                val cn = if (codigoBarras.startsWith("847000") && codigoBarras.length == 13) {
                    codigoBarras.substring(6, 12)
                } else {
                    codigoBarras
                }

                val url = java.net.URL("https://cima.aemps.es/cima/rest/medicamento?cn=$cn")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000 // 3 segundos max para no colgar la UI si no hay cobertura
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    // Expresión regular limpia para extraer el "nombre" del JSON sin meter librerías pesadas
                    val regex = """"nombre"\s*:\s*"([^"]+)"""".toRegex()
                    val match = regex.find(json)
                    val nombreApi = match?.groupValues?.get(1)

                    if (nombreApi != null) {
                        Log.d(TAG, "[SCAN] Fármaco encontrado en API: $nombreApi")
                        // 3. Guardamos en caché para que el próximo escaneo sea 100% offline
                        dbHelper.guardarMedicamentoLocal(codigoBarras, nombreApi)
                        return@withContext nombreApi
                    }
                }

                // Si la API no lo encuentra (o responde un 404)
                Log.w(TAG, "[SCAN] Fármaco desconocido para la API.")
                return@withContext null

            } catch (e: Exception) {
                Log.e(TAG, "[SCAN] Error de red o API no disponible: ${e.message}")
                return@withContext null // Forzamos el Fallback manual pidiendo el nombre al usuario
            }
        }
    }

    /**
     * Llamada manual desde la UI cuando la API falla y el cuidador escribe el nombre a mano.
     */
    fun guardarFarmacoManual(codigoBarras: String, nombreManual: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.guardarMedicamentoLocal(codigoBarras, nombreManual)
            Log.i(TAG, "[SCAN] Fármaco introducido y guardado manualmente: $nombreManual")
        }
    }
}