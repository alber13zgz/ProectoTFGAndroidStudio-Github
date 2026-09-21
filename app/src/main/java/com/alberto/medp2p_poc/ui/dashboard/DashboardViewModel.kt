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
import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import kotlinx.coroutines.Dispatchers
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
    private val _alertasPendientes = MutableStateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>>(emptyList())
    val alertasPendientes: StateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>> = _alertasPendientes.asStateFlow()

    private val _pautasPacienteActivas = MutableStateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>>(emptyList())
    val pautasPacienteActivas: StateFlow<List<com.alberto.medp2p_poc.data.model.PautaMedicaV2>> = _pautasPacienteActivas.asStateFlow()

    // ── AÑADIDO (PASO 3): StateFlow de la lista de Pacientes del Doctor (para el Dropdown) ──
    private val _pacientesDoctor = MutableStateFlow<List<com.alberto.medp2p_poc.data.model.Patient>>(emptyList())
    val pacientesDoctor: StateFlow<List<com.alberto.medp2p_poc.data.model.Patient>> = _pacientesDoctor.asStateFlow()

    private fun loadAlertasPendientes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _alertasPendientes.value = dbHelper.obtenerAlertasPendientesHoy(_dashboard.value.ownerPeerId)
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

    companion object {
        private const val TAG = "P2P_NETWORK"
        private const val RELAY_BASE =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWEBiChhAXXnZRPoM37aoawZbYQKp7WxqtC7LrfZFab4TV"
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
                val privKey: PrivKey = privateKey ?: generateKeyPair(KeyType.ED25519).first
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
                val privKey = storedPrivateKey ?: generateKeyPair(KeyType.ED25519).first
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

    suspend fun sendRecord(destinationCircuitAddr: String, record: MedicalRecord) {
        val node = activeHost ?: run {
            Log.e(TAG, "[SEND] Sin nodo activo.")
            return
        }
        messagingService.sendMedicalRecord(node, destinationCircuitAddr, record)
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

    fun linkPatient(fullName: String, peerId: String, allergies: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val owner  = _dashboard.value.ownerPeerId
            val myName = _dashboard.value.displayName
            try {
                val colorIndex = (fullName.hashCode() and 0x7FFFFFFF) % 8
                val patient = Patient(
                    fullName         = fullName.trim(),
                    peerId           = peerId.trim(),
                    allergies        = allergies.trim(),
                    avatarColorIndex = colorIndex,
                    lastSyncAt       = System.currentTimeMillis()
                )
                dbHelper.insertarPacienteClinico(patient, owner)
                Log.d(TAG, "✅ Paciente vinculado: ${patient.fullName}")
                loadDashboardCounters()
                loadPacientesDoctor() // Recargar lista para el Dropdown

                val node = activeHost ?: return@launch
                val destAddr = "$RELAY_BASE/p2p-circuit/p2p/${peerId.trim()}"
                messagingService.sendLinkDoctorMessage(
                    host                   = node,
                    destinationCircuitAddr = destAddr,
                    doctorPeerId           = owner,
                    doctorName             = myName,
                    doctorPhotoUri         = _dashboard.value.photoUri
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error vinculando paciente: ${e.message}")
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

            val node = activeHost ?: return@launch
            val destAddr = "$RELAY_BASE/p2p-circuit/p2p/$patientPeerId"
            messagingService.sendPautaMessage(node, destAddr, pauta)
        }
    }

    fun registrarSuministro(
        pautaId: String,
        patientPeerId: String,
        temperatura: Float? = null, // <-- NUEVO: Para enviar la fiebre si la hay
        sintomas: String? = null,   // <-- NUEVO: Para enviar los checkbox
        notas: String? = null       // <-- NUEVO: Para notas libres
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val owner = _dashboard.value.ownerPeerId
            val registro = com.alberto.medp2p_poc.data.model.RegistroSuministro(
                pautaId                  = pautaId,
                patientPeerId            = patientPeerId,
                doctorAdministeredPeerId = owner,
                temperatura              = temperatura, // <-- NUEVO
                sintomas                 = sintomas,    // <-- NUEVO
                notas                    = notas        // <-- NUEVO
            )
            dbHelper.insertarRegistroSuministro(registro)
            loadAlertasPendientes()
            Log.d(TAG, "✅ Suministro registrado: pauta=$pautaId")

            val node = activeHost ?: return@launch
            val destAddr = "$RELAY_BASE/p2p-circuit/p2p/$patientPeerId"
            messagingService.sendSuministroMessage(node, destAddr, registro)
        }
    }

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