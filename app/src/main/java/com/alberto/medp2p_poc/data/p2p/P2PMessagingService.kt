package com.alberto.medp2p_poc.data.p2p

import android.content.Context
import android.util.Log
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
import com.alberto.medp2p_poc.data.model.PautaMedicaV2
import com.alberto.medp2p_poc.data.model.RegistroSuministro
import com.alberto.medp2p_poc.data.security.KeyVaultManager
import io.libp2p.core.Host
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.circuit.CircuitStopProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════
// PROTOCOLO DE MENSAJES P2P — v3 (sobre firmado + E2EE con claves rotables)
//
// ARQUITECTURA: Application-Layer Relay
//   1. Android abre newStream al servidor con PROTOCOL_ID
//   2. El servidor lee destPeerId del JSON y reenvía los bytes al destino
//   3. El destino responde un acuse FIRMADO (ACK / REJECTED) que el servidor
//      devuelve al emisor; PEER_OFFLINE lo genera el propio relay
//
// SEGURIDAD EXTREMO A EXTREMO (el relay no es de confianza):
//   - Confidencialidad: payload cifrado con X25519 efímero + HKDF + AES-256-GCM
//     hacia la clave de cifrado VIGENTE del destinatario (CryptoUtils).
//   - Autenticidad: cada sobre y cada acuse van firmados con la identidad
//     Ed25519 del autor; se verifica con la clave embebida en su PeerId.
//     Las pautas y suministros llevan además la firma de su AUTOR
//     (RegistroFirmado), que se conserva cuando el paciente los reenvía.
//   - Anti-replay: msgId único + ventana temporal + tabla mensaje_recibido.
//   - Autorización: cada tipo de mensaje exige un vínculo previo concreto
//     (medico_vinculado / paciente_clinico) y coherencia firmante ↔ payload.
//
// CICLO DE VIDA DE LAS CLAVES DE CIFRADO (una por dispositivo y época):
//   - Autenticación: firmadas por la identidad Ed25519 (EncryptionKeyBundle);
//     la del paciente llega además por el QR, un canal físico.
//   - Intercambio: QR (paciente → cuidador) y LINK_DOCTOR (cuidador → paciente).
//   - Almacenamiento: privadas en KeyVaultManager; públicas de los pares en clave_par.
//   - Renovación: cada ROTACION_CLAVE_MS se crea una época nueva y se envía
//     KEY_UPDATE firmado a cada par hasta que lo confirma con ACK firmado.
//   - Revocación: al desvincular se borra la clave del par; las privadas
//     propias antiguas se purgan cuando todos los pares conocen la nueva.
//
// SINCRONIZACIÓN OFFLINE-FIRST:
//   - Cola de envío (sync_log): todo registro clínico se guarda en local y
//     se encola ANTES de enviarse; un bucle en segundo plano reconecta con
//     el relay y reintenta los pendientes hasta recibir un ACK firmado.
//   - Replicación en estrella: el dispositivo del paciente es el punto de
//     encuentro. Al recibir una pauta o un suministro de un cuidador, la
//     reenvía (con la firma del autor) a los demás cuidadores; al vincular
//     un cuidador nuevo, le envía todo el historial firmado.
//   - Append-only: los registros se identifican por UUID y nunca se
//     sobrescriben; un duplicado se ignora.
// ══════════════════════════════════════════════════════════════════════

@Serializable
data class P2PEnvelope(
    val version: Int,          // versión del formato de sobre (firmada)
    val type: String,
    val msgId: String,         // UUID único → detección de repeticiones
    val timestamp: Long,       // hora de emisión (ms) → ventana de validez
    val destPeerId: String,    // texto plano — el relay lo lee para enrutar
    val senderPeerId: String,  // texto plano — autenticado por la firma
    val payload: String,       // X25519 + AES-256-GCM — el relay no puede leerlo
    val signature: String      // Ed25519 del emisor sobre todos los campos anteriores
)

// Acuse de recibo firmado por el RECEPTOR: el relay no puede fabricar un ACK
// (haría creer al emisor que un mensaje descartado fue entregado)
@Serializable
data class P2PReceipt(
    val version: Int,
    val status: String,          // ACK | REJECTED
    val msgId: String,           // sobre al que responde
    val receiverPeerId: String,  // quien firma el acuse
    val signature: String        // Ed25519 del receptor (incluye también al emisor original)
)

@Serializable
data class LinkDoctorPayload(
    val doctorPeerId: String,
    val doctorName: String,
    val pairingToken: String,              // token de un solo uso leído del QR del paciente
    val doctorEncKey: EncryptionKeyBundle, // clave de cifrado del cuidador, firmada
    val patientKeyEpochSeen: Int,          // época de la clave del paciente leída en el QR
    val doctorPhotoUri: String = ""
)

@Serializable
data class PautaPayload(
    val id: String,
    val patientPeerId: String,
    val doctorCreatorPeerId: String,
    val medicacion: String,
    val dosis: String,
    val intervaloHoras: Int, // <-- CORREGIDO A INTERVALO
    val fechaInicio: Long,
    val fechaFin: Long
)

@Serializable
data class SuministroPayload(
    val id: String,
    val pautaId: String,
    val patientPeerId: String,
    val doctorAdministeredPeerId: String,
    val timestampSuministro: Long,
    val temperatura: Float? = null, // <-- NUEVO: Pasa por red
    val sintomas: String? = null,   // <-- NUEVO: Pasa por red
    val notas: String? = null,      // <-- NUEVO: Pasa por red
    val administradoPorNombre: String = ""   // para el historial de los demás cuidadores
)

@Serializable
data class UnlinkPatientPayload(val patientPeerId: String)

@Serializable
data class UnlinkDoctorPayload(val doctorPeerId: String)

class P2PMessagingService(
    private val context: Context,
    private val dbHelper: AppDatabaseHelper
) {
    companion object {
        const val PROTOCOL_ID = "/medp2p/registro/1.0.0"
        private const val RELAY_ADDRESS =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWP71KTgsJ2AvWcrB8KmxreDUeohcUFYLE3ynY4cABhYFx"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val MAX_FRAME_LENGTH = 65536
        private const val TAG = "P2P_MSG_SERVICE"
        const val TYPE_MEDICAL_RECORD = "MEDICAL_RECORD"
        const val TYPE_LINK_DOCTOR    = "LINK_DOCTOR"
        const val TYPE_SEND_PAUTA       = "SEND_PAUTA"
        const val TYPE_SUMINISTRO_PAUTA = "SUMINISTRO_PAUTA"
        const val TYPE_UNLINK_PATIENT   = "UNLINK_PATIENT"   // paciente → cuidador
        const val TYPE_UNLINK_DOCTOR    = "UNLINK_DOCTOR"    // cuidador → paciente
        const val TYPE_KEY_UPDATE       = "KEY_UPDATE"

        const val ENVELOPE_VERSION = 3
        private const val RESP_ACK          = "ACK"
        private const val RESP_REJECTED     = "REJECTED"
        private const val RESP_PEER_OFFLINE = "PEER_OFFLINE"
        private const val RESP_SIN_CLAVE    = "SIN_CLAVE"   // local: no hay clave del destinatario
        // Tolerancia a relojes desincronizados entre móviles
        private const val MAX_CLOCK_SKEW_MS = 5L * 60 * 1000
        // Ventana de validez: amplia porque el modelo es Offline-First y un
        // sobre puede quedar encolado días; más allá se rechaza y se purga
        private const val MAX_MESSAGE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        // Los pendientes se dan por caducados un día antes de que el receptor los rechace
        private const val MARGEN_EXPIRACION_MS = 24L * 60 * 60 * 1000
        // Periodo de renovación de la clave de cifrado del dispositivo
        private const val ROTACION_CLAVE_MS = 30L * 24 * 60 * 60 * 1000
        // Cada cuánto se comprueba la conexión con el relay y se reintenta la cola
        private const val INTERVALO_MANTENIMIENTO_MS = 30_000L
    }

    private val _incomingMessages = MutableSharedFlow<MedicalRecord>(
        replay = 0, extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<MedicalRecord> = _incomingMessages

    private val _syncEvents = MutableSharedFlow<Long>(
        replay = 0, extraBufferCapacity = 16
    )
    val syncEvents: SharedFlow<Long> = _syncEvents

    /** true mientras hay conexión con el relay (la actualiza el bucle de mantenimiento). */
    private val _relayConectado = MutableStateFlow(false)
    val relayConectado: StateFlow<Boolean> = _relayConectado.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val keyVault = KeyVaultManager(context.applicationContext)
    private val relayPeerId = PeerId.fromBase58(RELAY_ADDRESS.substringAfterLast("/"))
    private val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
    private val colaMutex = Mutex()
    private var ownerPeerId: String = ""
    private var ownPrivKey: PrivKey? = null
    private var host: Host? = null
    private var mantenimientoJob: Job? = null

    /** Nodo libp2p en marcha (aunque el relay aún no esté alcanzable), o null. */
    val currentHost: Host? get() = host

    // ══════════════════════════════════════════════════════════════
    // buildReceiverBinding() — receptor de mensajes entrantes
    // ══════════════════════════════════════════════════════════════
    private fun buildReceiverBinding(): ProtocolBinding<Unit> {
        return object : ProtocolBinding<Unit> {
            override val protocolDescriptor = ProtocolDescriptor(PROTOCOL_ID)

            override fun initChannel(
                ch: P2PChannel,
                selectedProtocol: String
            ): CompletableFuture<Unit> {
                ch.pushHandler(LineBasedFrameDecoder(MAX_FRAME_LENGTH))
                ch.pushHandler(StringDecoder(CharsetUtil.UTF_8))
                ch.pushHandler(object : ChannelInboundHandlerAdapter() {

                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        val jsonString = msg as String
                        Log.i(TAG, "[RECEPTOR] Mensaje recibido (${jsonString.length} bytes)")
                        val envelope = try {
                            json.decodeFromString<P2PEnvelope>(jsonString)
                        } catch (e: Exception) {
                            // Incluye sobres de versiones anteriores: ya no se aceptan
                            Log.w(TAG, "[RECEPTOR] ❌ Sobre con formato inválido: ${e.message}")
                            reply(ctx, RESP_REJECTED)
                            return
                        }
                        serviceScope.launch { procesarSobre(ctx, envelope) }
                    }

                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        Log.e(TAG, "[RECEPTOR] Error de canal: ${cause.message}")
                        ctx.close()
                    }
                })
                return CompletableFuture.completedFuture(Unit)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Claves de cifrado propias
    // ══════════════════════════════════════════════════════════════

    /** Clave de cifrado vigente de este dispositivo (para el QR de vinculación). */
    fun claveCifradoActual(): KeyVaultManager.EncKey = keyVault.getOrCreateEncryptionKey()

    /** Clave de cifrado vigente firmada con la identidad (para enviarla a los pares). */
    private fun bundlePropio(): EncryptionKeyBundle {
        val identity = checkNotNull(ownPrivKey) { "Servicio P2P no iniciado" }
        val actual = keyVault.getOrCreateEncryptionKey()
        return EncryptionKeyBundle.crear(identity, ownerPeerId, actual.epoch, actual.publicKey, actual.createdAt)
    }

    /**
     * Envía KEY_UPDATE a los pares que aún no conocen la clave vigente y,
     * después, purga las claves privadas antiguas que ya ningún par usa.
     * Los pares desconectados se reintentan en la siguiente vuelta del bucle.
     */
    private suspend fun sincronizarClaves(host: Host) {
        val bundle = bundlePropio()
        val pendientes = dbHelper.obtenerParesSinConfirmar(ownerPeerId, bundle.epoch)
        for (peer in pendientes) {
            val ok = enviar(host, TYPE_KEY_UPDATE, peer, json.encodeToString(bundle))
            if (ok) {
                dbHelper.marcarEpochConfirmado(ownerPeerId, peer, bundle.epoch)
                Log.i(TAG, "[CLAVES] ${peer.take(12)} confirmó la clave de época ${bundle.epoch}")
            }
        }
        keyVault.purgeEncryptionKeysBefore(dbHelper.minEpochConfirmado(ownerPeerId) ?: bundle.epoch)
    }

    /**
     * Renueva ya la clave de cifrado (además de la renovación periódica automática).
     * Devuelve cuántos pares aún no han confirmado la clave nueva.
     */
    suspend fun rotarClaveCifrado(host: Host): Int = withContext(Dispatchers.IO) {
        val nueva = keyVault.rotateEncryptionKey()
        sincronizarClaves(host)
        dbHelper.obtenerParesSinConfirmar(ownerPeerId, nueva.epoch).size
    }

    // ══════════════════════════════════════════════════════════════
    // Construcción y verificación del sobre
    // ══════════════════════════════════════════════════════════════

    /** Bytes firmados del sobre: todos los campos salvo la firma. */
    private fun signedBytes(e: P2PEnvelope): ByteArray =
        CryptoUtils.lengthPrefixed("medp2p-sig", e.version.toString(), e.type, e.msgId,
            e.timestamp.toString(), e.senderPeerId, e.destPeerId, e.payload)

    /**
     * Bytes firmados del acuse. Incluye al emisor original para que un acuse
     * no pueda reutilizarse como respuesta a otro emisor.
     */
    private fun receiptBytes(version: Int, status: String, msgId: String,
                             receiverPeerId: String, originalSenderPeerId: String): ByteArray =
        CryptoUtils.lengthPrefixed("medp2p-receipt", version.toString(), status, msgId,
            receiverPeerId, originalSenderPeerId)

    private fun openEnvelope(envelope: P2PEnvelope): String =
        CryptoUtils.decrypt(
            envelope.payload,
            { epoch -> keyVault.getEncryptionPrivateKey(epoch) },
            CryptoUtils.buildAad(envelope.type, envelope.senderPeerId, envelope.destPeerId)
        )

    /**
     * Cifra el payload para la clave vigente del destinatario, rellena los
     * metadatos y firma el sobre con la identidad Ed25519 propia.
     * [recipientKey] solo se pasa al vincular (la clave viene del QR).
     * En los reintentos de la cola se conservan [msgId] y [timestamp] originales.
     */
    private fun buildEnvelope(
        type: String,
        destPeerId: String,
        plainJson: String,
        recipientKey: Pair<Int, String>? = null,
        msgId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): P2PEnvelope {
        val identity = checkNotNull(ownPrivKey) { "Servicio P2P no iniciado" }
        val (epoch, pubB64) = recipientKey
            ?: dbHelper.obtenerClavePar(ownerPeerId, destPeerId)
            ?: error("Sin clave de cifrado de ${destPeerId.take(12)}: hay que volver a vincular")
        val unsigned = P2PEnvelope(
            version      = ENVELOPE_VERSION,
            type         = type,
            msgId        = msgId,
            timestamp    = timestamp,
            destPeerId   = destPeerId,
            senderPeerId = ownerPeerId,
            payload      = CryptoUtils.encrypt(
                plainJson, CryptoUtils.fromB64Url(pubB64), epoch,
                CryptoUtils.buildAad(type, ownerPeerId, destPeerId)
            ),
            signature    = ""
        )
        return unsigned.copy(signature = CryptoUtils.sign(identity, signedBytes(unsigned)))
    }

    /** Envío directo (sin cola) para mensajes interactivos. Nunca lanza: cualquier fallo → false. */
    private suspend fun enviar(
        host: Host,
        type: String,
        destPeerId: String,
        plainJson: String,
        recipientKey: Pair<Int, String>? = null
    ): Boolean {
        val envelope = try {
            buildEnvelope(type, destPeerId, plainJson, recipientKey)
        } catch (e: Exception) {
            Log.e(TAG, "[SEND] ❌ No se pudo preparar $type: ${e.message}")
            return false
        }
        return enviarSobre(host, envelope) == RESP_ACK
    }

    /** Devuelve el motivo de rechazo, o null si el sobre es auténtico y vigente. */
    private fun validarSobre(env: P2PEnvelope): String? {
        val now = System.currentTimeMillis()
        return when {
            env.version != ENVELOPE_VERSION        -> "versión ${env.version} no soportada"
            env.destPeerId != ownerPeerId          -> "destino distinto de este nodo"
            env.senderPeerId == ownerPeerId        -> "remitente igual al receptor"
            env.msgId.isBlank() || env.msgId.length > 64 -> "msgId inválido"
            env.timestamp > now + MAX_CLOCK_SKEW_MS -> "marca de tiempo en el futuro"
            env.timestamp < now - MAX_MESSAGE_AGE_MS -> "mensaje caducado"
            !CryptoUtils.verifySignature(env.senderPeerId, signedBytes(env), env.signature) ->
                "firma Ed25519 inválida o no corresponde al senderPeerId"
            else -> null
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pipeline de recepción: autenticidad → anti-replay → descifrado
    //                        → autorización → persistencia → ACK
    // ══════════════════════════════════════════════════════════════
    private suspend fun procesarSobre(ctx: ChannelHandlerContext, env: P2PEnvelope) {
        validarSobre(env)?.let { motivo ->
            Log.w(TAG, "[RECEPTOR] ❌ ${env.type} de ${env.senderPeerId.take(12)} rechazado: $motivo")
            replyReceipt(ctx, env, RESP_REJECTED)
            return
        }

        // Idempotente: si ya se procesó, se confirma sin volver a aplicarlo
        if (!dbHelper.registrarMensajeRecibido(env.msgId, env.senderPeerId, System.currentTimeMillis())) {
            Log.w(TAG, "[RECEPTOR] Mensaje repetido ${env.msgId.take(8)}: ya aplicado, se ignora")
            replyReceipt(ctx, env, RESP_ACK)
            return
        }

        val aplicado = try {
            val plain = openEnvelope(env)
            when (env.type) {
                TYPE_MEDICAL_RECORD   -> handleMedicalRecord(env.senderPeerId, plain)
                TYPE_LINK_DOCTOR      -> handleLinkDoctor(env.senderPeerId, plain)
                TYPE_SEND_PAUTA,
                TYPE_SUMINISTRO_PAUTA -> handleRegistroClinico(env.senderPeerId, plain, env.type)
                TYPE_UNLINK_PATIENT   -> handleUnlinkPatient(env.senderPeerId, plain)
                TYPE_UNLINK_DOCTOR    -> handleUnlinkDoctor(env.senderPeerId, plain)
                TYPE_KEY_UPDATE       -> handleKeyUpdate(env.senderPeerId, plain)
                else -> denegar(env.type, env.senderPeerId, "tipo desconocido")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando ${env.type}: ${e.message}")
            false
        }

        if (aplicado) {
            _syncEvents.emit(System.currentTimeMillis())
            replyReceipt(ctx, env, RESP_ACK)
        } else {
            // Libera el msgId: un reenvío legítimo posterior se vuelve a evaluar
            dbHelper.olvidarMensajeRecibido(env.msgId)
            replyReceipt(ctx, env, RESP_REJECTED)
        }
    }

    private fun denegar(type: String, sender: String, motivo: String): Boolean {
        Log.w(TAG, "[AUTORIZACION] ❌ $type de ${sender.take(12)} denegado: $motivo")
        return false
    }

    // ── Handlers: cada uno exige coherencia firmante ↔ payload y un vínculo previo ──

    private suspend fun handleMedicalRecord(sender: String, plain: String): Boolean {
        if (!dbHelper.esCuidadorVinculado(ownerPeerId, sender) &&
            !dbHelper.esPacienteVinculado(ownerPeerId, sender)
        ) return denegar(TYPE_MEDICAL_RECORD, sender, "remitente no vinculado")

        val record = json.decodeFromString<MedicalRecord>(plain)
        dbHelper.guardarRegistroMedico(record.copy(isMine = false), ownerPeerId)
        Log.d(TAG, "[RECEPTOR] MedicalRecord guardado: id=${record.id}")
        _incomingMessages.emit(record)
        return true
    }

    // Vinculación inicial: el firmante solo puede vincularse A SÍ MISMO, debe
    // presentar un código de un solo uso que solo se obtiene viendo el QR en
    // persona, y su clave de cifrado debe venir firmada por su propia identidad
    private fun handleLinkDoctor(sender: String, plain: String): Boolean {
        val payload = json.decodeFromString<LinkDoctorPayload>(plain)
        val clave = payload.doctorEncKey
        when {
            payload.doctorPeerId != sender ->
                return denegar(TYPE_LINK_DOCTOR, sender, "doctorPeerId no coincide con el firmante")
            clave.peerId != sender || !clave.verificar() ->
                return denegar(TYPE_LINK_DOCTOR, sender, "clave de cifrado no firmada por el cuidador")
            !dbHelper.consumirCodigoVinculacion(ownerPeerId, payload.pairingToken, System.currentTimeMillis()) ->
                return denegar(TYPE_LINK_DOCTOR, sender, "código de vinculación inválido, caducado o ya usado")
        }

        dbHelper.guardarMedicoVinculado(
            doctorPeerId   = payload.doctorPeerId,
            doctorName     = payload.doctorName,
            ownerPeerId    = ownerPeerId,
            doctorPhotoUri = payload.doctorPhotoUri
        )
        dbHelper.guardarClavePar(ownerPeerId, sender, clave.epoch, clave.publicKey)
        // El cuidador conoce mi clave de la época que vio en el QR; si desde
        // entonces la he renovado, sincronizarClaves() le enviará la nueva
        dbHelper.marcarEpochConfirmado(ownerPeerId, sender, payload.patientKeyEpochSeen)
        Log.i(TAG, "[RECEPTOR] Médico vinculado: ${payload.doctorName}")

        // Sincronización inicial: el nuevo cuidador recibe todo el historial firmado
        val historial = dbHelper.obtenerRegistrosFirmadosDePaciente(ownerPeerId, TYPE_SEND_PAUTA, TYPE_SUMINISTRO_PAUTA)
        historial.forEach { r ->
            encolar(sender, r.tipo, json.encodeToString(RegistroFirmado(r.tipo, r.autorPeerId, r.contenido, r.firma)))
        }
        if (historial.isNotEmpty()) {
            Log.i(TAG, "[SYNC] ${historial.size} registros encolados para el nuevo cuidador")
            vaciarColaAhora()
        }
        return true
    }

    // Renovación de clave de un par ya vinculado: debe estar firmada por su
    // identidad y ser de una época posterior a la conocida
    private fun handleKeyUpdate(sender: String, plain: String): Boolean {
        val clave = json.decodeFromString<EncryptionKeyBundle>(plain)
        when {
            dbHelper.obtenerClavePar(ownerPeerId, sender) == null ->
                return denegar(TYPE_KEY_UPDATE, sender, "par no vinculado")
            clave.peerId != sender || !clave.verificar() ->
                return denegar(TYPE_KEY_UPDATE, sender, "clave no firmada por el remitente")
        }
        // Una época igual o anterior no se aplica, pero se confirma (ACK) para
        // que el emisor deje de reenviarla; así es idempotente
        if (dbHelper.guardarClavePar(ownerPeerId, sender, clave.epoch, clave.publicKey)) {
            Log.i(TAG, "[CLAVES] Clave de ${sender.take(12)} renovada → época ${clave.epoch}")
        }
        return true
    }

    /**
     * Pautas y suministros. Llegan por dos caminos:
     *  - DIRECTO: un cuidador vinculado escribe al paciente; el autor del
     *    registro debe ser el propio remitente. El paciente lo reenvía después
     *    a sus demás cuidadores.
     *  - REENVIADO: el paciente lo reenvía a un cuidador; el registro debe ser
     *    de ese paciente y la firma interna identifica al cuidador que lo creó.
     */
    private fun handleRegistroClinico(sender: String, plain: String, tipoSobre: String): Boolean {
        val reg = json.decodeFromString<RegistroFirmado>(plain)
        if (reg.tipo != tipoSobre) return denegar(tipoSobre, sender, "tipo del registro distinto del sobre")
        if (!reg.verificar()) return denegar(tipoSobre, sender, "firma del autor del registro inválida")

        val (pacienteDelRegistro, autorDeclarado) = when (tipoSobre) {
            TYPE_SEND_PAUTA -> json.decodeFromString<PautaPayload>(reg.contenido)
                .let { it.patientPeerId to it.doctorCreatorPeerId }
            else -> json.decodeFromString<SuministroPayload>(reg.contenido)
                .let { it.patientPeerId to it.doctorAdministeredPeerId }
        }
        if (autorDeclarado != reg.autorPeerId)
            return denegar(tipoSobre, sender, "el autor declarado no es quien firmó el registro")

        val directo   = dbHelper.esCuidadorVinculado(ownerPeerId, sender)  // soy el paciente
        val reenviado = dbHelper.esPacienteVinculado(ownerPeerId, sender)  // soy un cuidador
        when {
            directo && reg.autorPeerId != sender ->
                return denegar(tipoSobre, sender, "un cuidador solo puede enviar registros propios")
            directo && pacienteDelRegistro != ownerPeerId ->
                return denegar(tipoSobre, sender, "el registro es de otro paciente")
            reenviado && pacienteDelRegistro != sender ->
                return denegar(tipoSobre, sender, "el paciente solo puede reenviar registros propios")
            !directo && !reenviado ->
                return denegar(tipoSobre, sender, "remitente no vinculado")
        }

        guardarRegistroFirmado(reg)
        if (directo) replicarAOtrosCuidadores(reg, excluir = sender)
        return true
    }

    /** Inserta la pauta o el suministro (append-only) junto con la firma de su autor. */
    private fun guardarRegistroFirmado(reg: RegistroFirmado) {
        if (reg.tipo == TYPE_SEND_PAUTA) {
            val p = json.decodeFromString<PautaPayload>(reg.contenido)
            dbHelper.insertarPautaMedicaV2(PautaMedicaV2(
                id = p.id, patientPeerId = p.patientPeerId,
                doctorCreatorPeerId = p.doctorCreatorPeerId,
                medicacion = p.medicacion, dosis = p.dosis,
                intervaloHoras = p.intervaloHoras,
                fechaInicio = p.fechaInicio, fechaFin = p.fechaFin
            ))
            dbHelper.guardarFirmaPauta(p.id, reg.contenido, reg.firma)
            Log.i(TAG, "[RECEPTOR] Pauta guardada: ${p.medicacion} (autor ${reg.autorPeerId.take(12)})")
        } else {
            val s = json.decodeFromString<SuministroPayload>(reg.contenido)
            dbHelper.insertarRegistroSuministro(RegistroSuministro(
                id = s.id, pautaId = s.pautaId,
                patientPeerId = s.patientPeerId,
                doctorAdministeredPeerId = s.doctorAdministeredPeerId,
                timestampSuministro = s.timestampSuministro,
                temperatura = s.temperatura, sintomas = s.sintomas, notas = s.notas
            ))
            dbHelper.guardarFirmaSuministro(s.id, reg.contenido, reg.firma)
            Log.i(TAG, "[RECEPTOR] Suministro guardado: pauta=${s.pautaId.take(8)} (autor ${reg.autorPeerId.take(12)})")
        }
    }

    /** PACIENTE: reenvía un registro recibido de un cuidador a todos los demás. */
    private fun replicarAOtrosCuidadores(reg: RegistroFirmado, excluir: String) {
        val destinos = dbHelper.obtenerMedicosVinculados(ownerPeerId)
            .map { it.doctorPeerId }.filter { it != excluir }
        destinos.forEach { encolar(it, reg.tipo, json.encodeToString(reg)) }
        if (destinos.isNotEmpty()) {
            Log.i(TAG, "[SYNC] ${reg.tipo} replicado a ${destinos.size} cuidador(es)")
            vaciarColaAhora()
        }
    }

    // El paciente solo puede desvincularse A SÍ MISMO de un cuidador que lo tenga en su directorio
    private fun handleUnlinkPatient(sender: String, plain: String): Boolean {
        val payload = json.decodeFromString<UnlinkPatientPayload>(plain)
        when {
            payload.patientPeerId != sender ->
                return denegar(TYPE_UNLINK_PATIENT, sender, "solo el propio paciente puede desvincularse")
            !dbHelper.esPacienteVinculado(ownerPeerId, sender) ->
                return denegar(TYPE_UNLINK_PATIENT, sender, "paciente no vinculado a este cuidador")
        }
        dbHelper.desvincularPaciente(payload.patientPeerId, ownerPeerId)   // también revoca su clave
        Log.i(TAG, "[RECEPTOR] Paciente desvinculado: ${payload.patientPeerId.take(12)}")
        return true
    }

    // El cuidador solo puede desvincularse A SÍ MISMO de un paciente: revoca su autorización
    private fun handleUnlinkDoctor(sender: String, plain: String): Boolean {
        val payload = json.decodeFromString<UnlinkDoctorPayload>(plain)
        when {
            payload.doctorPeerId != sender ->
                return denegar(TYPE_UNLINK_DOCTOR, sender, "solo el propio cuidador puede desvincularse")
            !dbHelper.esCuidadorVinculado(ownerPeerId, sender) ->
                return denegar(TYPE_UNLINK_DOCTOR, sender, "cuidador no vinculado a este paciente")
        }
        dbHelper.desvincularCuidador(sender, ownerPeerId)   // directorio + clave de cifrado
        Log.i(TAG, "[RECEPTOR] Cuidador desvinculado: ${sender.take(12)}")
        return true
    }

    // ══════════════════════════════════════════════════════════════
    // Acuses de recibo firmados
    // ══════════════════════════════════════════════════════════════

    private fun reply(ctx: ChannelHandlerContext, response: String) {
        if (ctx.channel().isActive) {
            ctx.writeAndFlush(Unpooled.copiedBuffer("$response\n", CharsetUtil.UTF_8))
            Log.i(TAG, "[RECEPTOR] ${response.take(40)} enviado.")
        }
    }

    /** Responde con un acuse firmado con la identidad Ed25519 de este nodo. */
    private fun replyReceipt(ctx: ChannelHandlerContext, env: P2PEnvelope, status: String) {
        val key = ownPrivKey ?: return reply(ctx, status)
        val receipt = P2PReceipt(
            version        = ENVELOPE_VERSION,
            status         = status,
            msgId          = env.msgId,
            receiverPeerId = ownerPeerId,
            signature      = CryptoUtils.sign(
                key, receiptBytes(ENVELOPE_VERSION, status, env.msgId, ownerPeerId, env.senderPeerId)
            )
        )
        reply(ctx, json.encodeToString(receipt))
    }

    /**
     * Interpreta la respuesta que devuelve el relay. Solo cuenta como ACK/REJECTED
     * si es un acuse firmado por el destinatario esperado para ESTE msgId.
     */
    private fun interpretarRespuesta(raw: String, envelope: P2PEnvelope): String {
        if (raw == RESP_PEER_OFFLINE) return raw   // lo genera el relay: no puede ir firmado
        val receipt = try {
            json.decodeFromString<P2PReceipt>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "[SEND] Respuesta sin firmar descartada: ${raw.take(40)}")
            return "UNVERIFIED"
        }
        val valido = receipt.version == ENVELOPE_VERSION &&
            receipt.msgId == envelope.msgId &&
            receipt.receiverPeerId == envelope.destPeerId &&
            CryptoUtils.verifySignature(
                envelope.destPeerId,
                receiptBytes(receipt.version, receipt.status, receipt.msgId,
                    receipt.receiverPeerId, envelope.senderPeerId),
                receipt.signature
            )
        if (!valido) {
            Log.e(TAG, "[SEND] ❌ Acuse con firma inválida o de otro mensaje: posible manipulación del relay")
            return "UNVERIFIED"
        }
        return receipt.status
    }

    // ══════════════════════════════════════════════════════════════
    // Arranque del nodo y mantenimiento en segundo plano
    // ══════════════════════════════════════════════════════════════

    private fun buildNode(privKey: PrivKey): Host {
        return host {
            identity { factory = { privKey } }
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network { listen("/ip4/0.0.0.0/tcp/0") }
            protocols {
                add(CircuitStopProtocol.Binding(CircuitStopProtocol()))
                add(buildReceiverBinding())
            }
        }
    }

    /**
     * Arranca el nodo y conecta con el relay. Si el relay no es alcanzable
     * lanza excepción (la UI muestra "modo local"), pero el nodo queda en
     * marcha y el bucle de mantenimiento seguirá intentando reconectar.
     */
    suspend fun start(privKey: PrivKey, ownerPeerId: String = ""): Host = withContext(Dispatchers.IO) {
        this@P2PMessagingService.ownerPeerId = ownerPeerId
        this@P2PMessagingService.ownPrivKey = privKey
        Log.d(TAG, "[START] ownerPeerId=$ownerPeerId")

        dbHelper.purgarMensajesRecibidos(System.currentTimeMillis() - MAX_MESSAGE_AGE_MS)

        // Renovación periódica de la clave de cifrado del dispositivo
        val clave = keyVault.getOrCreateEncryptionKey()
        if (System.currentTimeMillis() - clave.createdAt > ROTACION_CLAVE_MS) {
            keyVault.rotateEncryptionKey()
        }

        // Reinicio (botón "reintentar"): parar el nodo y el bucle anteriores
        mantenimientoJob?.cancel()
        host?.let { anterior -> runCatching { anterior.stop() } }

        val node = buildNode(privKey)
        node.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        host = node
        Log.i(TAG, "[START] Nodo arrancado. PeerId=${node.peerId}")

        mantenimientoJob = serviceScope.launch { bucleMantenimiento(node) }

        conectarRelay(node)
        Log.i(TAG, "[START] ✅ Conectado al relay AWS.")
        node
    }

    private fun conectarRelay(node: Host) {
        node.network.connect(relayPeerId, relayMultiaddr).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        _relayConectado.value = true
    }

    private fun conectadoAlRelay(node: Host): Boolean =
        node.network.connections.any { it.secureSession().remoteId == relayPeerId }

    /**
     * Cada INTERVALO_MANTENIMIENTO_MS: reconecta con el relay si hace falta
     * (p. ej. tras quitar el modo avión), distribuye la clave de cifrado
     * vigente y reintenta la cola de envío.
     */
    private suspend fun bucleMantenimiento(node: Host) {
        while (true) {
            val conectado = conectadoAlRelay(node) || runCatching { conectarRelay(node) }.isSuccess
            if (_relayConectado.value != conectado) {
                Log.i(TAG, "[RED] Relay ${if (conectado) "alcanzable" else "no alcanzable"}")
            }
            _relayConectado.value = conectado
            if (conectado) {
                runCatching { sincronizarClaves(node) }
                    .onFailure { Log.e(TAG, "[CLAVES] Error sincronizando: ${it.message}") }
                vaciarCola(node)
            }
            delay(INTERVALO_MANTENIMIENTO_MS)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Cola de envío offline (sync_log)
    // ══════════════════════════════════════════════════════════════

    /** Guarda un mensaje en la cola. Funciona sin conexión. */
    private fun encolar(destPeerId: String, tipo: String, contenido: String, tabla: String = "", registroId: String = "") {
        dbHelper.encolarMensaje(ownerPeerId, UUID.randomUUID().toString(), destPeerId, tipo, contenido, tabla, registroId)
    }

    /** Intenta vaciar la cola ya, sin esperar a la siguiente vuelta del bucle. */
    private fun vaciarColaAhora() {
        val node = host ?: return
        serviceScope.launch { if (_relayConectado.value) vaciarCola(node) }
    }

    /**
     * Envía los pendientes en orden de creación. Si un destinatario no está
     * disponible se saltan sus siguientes mensajes para conservar el orden.
     */
    private suspend fun vaciarCola(node: Host) = colaMutex.withLock {
        val caducados = dbHelper.expirarMensajesPendientes(
            ownerPeerId, System.currentTimeMillis() - MAX_MESSAGE_AGE_MS + MARGEN_EXPIRACION_MS)
        if (caducados > 0) Log.w(TAG, "[COLA] $caducados mensaje(s) caducados sin entregar")

        val noDisponibles = mutableSetOf<String>()
        var entregados = 0
        for (m in dbHelper.obtenerMensajesPendientes(ownerPeerId)) {
            if (m.destPeerId in noDisponibles) continue
            val estado = try {
                enviarSobre(node, buildEnvelope(
                    m.tipoMensaje, m.destPeerId, m.contenido,
                    msgId = m.msgId, timestamp = m.timestampCreacion
                ))
            } catch (e: Exception) {
                Log.e(TAG, "[COLA] No se puede preparar ${m.tipoMensaje}: ${e.message}")
                RESP_SIN_CLAVE
            }
            when (estado) {
                RESP_ACK -> {
                    dbHelper.actualizarEstadoMensaje(m.msgId, "DELIVERED")
                    entregados++
                }
                RESP_REJECTED, RESP_SIN_CLAVE -> {
                    dbHelper.actualizarEstadoMensaje(m.msgId, "REJECTED")
                    Log.e(TAG, "[COLA] ❌ ${m.tipoMensaje} a ${m.destPeerId.take(12)} descartado ($estado)")
                }
                else -> {
                    dbHelper.actualizarEstadoMensaje(m.msgId, "SEND_PENDING")   // cuenta el intento
                    noDisponibles += m.destPeerId
                }
            }
        }
        if (entregados > 0) {
            Log.i(TAG, "[COLA] ✅ $entregados mensaje(s) entregados")
            _syncEvents.emit(System.currentTimeMillis())
        }
    }

    // ══════════════════════════════════════════════════════════════
    // API para la capa de presentación
    // ══════════════════════════════════════════════════════════════

    /** Registro médico libre. Se guarda en local y se encola; llega cuando haya red. */
    fun enviarRegistroMedico(destPeerId: String, record: MedicalRecord) {
        dbHelper.guardarRegistroMedico(record.copy(isMine = true), ownerPeerId)
        encolar(destPeerId, TYPE_MEDICAL_RECORD, json.encodeToString(record), "historial_clinico", record.id)
        vaciarColaAhora()
    }

    /** CUIDADOR: pauta nueva (ya guardada en local). Se firma como autor y se encola para el paciente. */
    fun enviarPauta(pauta: PautaMedicaV2) {
        val identity = ownPrivKey ?: run { Log.e(TAG, "[SEND_PAUTA] Servicio no iniciado"); return }
        val contenido = json.encodeToString(PautaPayload(
            id = pauta.id, patientPeerId = pauta.patientPeerId,
            doctorCreatorPeerId = pauta.doctorCreatorPeerId,
            medicacion = pauta.medicacion, dosis = pauta.dosis,
            intervaloHoras = pauta.intervaloHoras,
            fechaInicio = pauta.fechaInicio, fechaFin = pauta.fechaFin
        ))
        val reg = RegistroFirmado.crear(identity, TYPE_SEND_PAUTA, ownerPeerId, contenido)
        dbHelper.guardarFirmaPauta(pauta.id, contenido, reg.firma)
        encolar(pauta.patientPeerId, TYPE_SEND_PAUTA, json.encodeToString(reg), "pauta_medica_v2", pauta.id)
        vaciarColaAhora()
    }

    /** CUIDADOR: suministro nuevo (ya guardado en local). Se firma como autor y se encola para el paciente. */
    fun enviarSuministro(registro: RegistroSuministro, nombreAutor: String) {
        val identity = ownPrivKey ?: run { Log.e(TAG, "[SUMINISTRO] Servicio no iniciado"); return }
        val contenido = json.encodeToString(SuministroPayload(
            id = registro.id, pautaId = registro.pautaId,
            patientPeerId = registro.patientPeerId,
            doctorAdministeredPeerId = registro.doctorAdministeredPeerId,
            timestampSuministro = registro.timestampSuministro,
            temperatura = registro.temperatura, sintomas = registro.sintomas, notas = registro.notas,
            administradoPorNombre = nombreAutor
        ))
        val reg = RegistroFirmado.crear(identity, TYPE_SUMINISTRO_PAUTA, ownerPeerId, contenido)
        dbHelper.guardarFirmaSuministro(registro.id, contenido, reg.firma)
        encolar(registro.patientPeerId, TYPE_SUMINISTRO_PAUTA, json.encodeToString(reg),
            "registro_suministro", registro.id)
        vaciarColaAhora()
    }

    /**
     * CUIDADOR → PACIENTE. Interactivo (no se encola): ambos deben estar
     * conectados. Se cifra con la clave del paciente leída del QR. Si el
     * paciente lo acepta (ACK firmado) se guarda su clave y se da por hecho
     * que conoce la mía. Devuelve true si la vinculación fue aceptada.
     */
    suspend fun sendLinkDoctorMessage(
        host: Host,
        patientPeerId: String,
        doctorName: String,
        pairingToken: String,
        patientKeyEpoch: Int,
        patientKeyPub: String
    ): Boolean = withContext(Dispatchers.IO) {
        val miClave = try { bundlePropio() } catch (e: Exception) {
            Log.e(TAG, "[LINK_DOCTOR] Servicio no iniciado: ${e.message}")
            return@withContext false
        }
        val payload = LinkDoctorPayload(
            doctorPeerId        = ownerPeerId,
            doctorName          = doctorName,
            pairingToken        = pairingToken,
            doctorEncKey        = miClave,
            patientKeyEpochSeen = patientKeyEpoch
        )
        Log.i(TAG, "[LINK_DOCTOR] Enviando vinculación a $patientPeerId")
        val aceptado = enviar(host, TYPE_LINK_DOCTOR, patientPeerId, json.encodeToString(payload),
            recipientKey = patientKeyEpoch to patientKeyPub)
        if (aceptado) {
            dbHelper.guardarClavePar(ownerPeerId, patientPeerId, patientKeyEpoch, patientKeyPub)
            dbHelper.marcarEpochConfirmado(ownerPeerId, patientPeerId, miClave.epoch)
        }
        aceptado
    }

    /** PACIENTE → CUIDADOR: el paciente se da de baja del directorio del cuidador. */
    suspend fun sendUnlinkPatientMessage(
        host: Host,
        destinationCircuitAddr: String,
        patientPeerId: String
    ): Unit = withContext(Dispatchers.IO) {
        val destPeerId = destinationCircuitAddr.substringAfterLast("/")
        Log.i(TAG, "[UNLINK] Enviando desvinculacion a $destPeerId")
        enviar(host, TYPE_UNLINK_PATIENT, destPeerId, json.encodeToString(UnlinkPatientPayload(patientPeerId)))
    }

    /** CUIDADOR → PACIENTE: revoca la autorización del cuidador en el dispositivo del paciente. */
    suspend fun sendUnlinkDoctorMessage(host: Host, patientPeerId: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "[UNLINK_DOCTOR] Revocando vínculo con $patientPeerId")
            enviar(host, TYPE_UNLINK_DOCTOR, patientPeerId,
                json.encodeToString(UnlinkDoctorPayload(ownerPeerId)))
        }

    /**
     * Envía un sobre por el relay y devuelve el resultado: ACK / REJECTED
     * (acuses firmados y verificados), PEER_OFFLINE, UNVERIFIED o ERROR.
     */
    private suspend fun enviarSobre(host: Host, envelope: P2PEnvelope): String = withContext(Dispatchers.IO) {
        try {
            val stream = host.newStream<Unit>(listOf(PROTOCOL_ID), relayPeerId, relayMultiaddr)
                .stream.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val ackFuture = CompletableFuture<String>()
            stream.pushHandler(LineBasedFrameDecoder(MAX_FRAME_LENGTH))
            stream.pushHandler(StringDecoder(CharsetUtil.UTF_8))
            stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ackFuture.complete((msg as String).trim())
                }
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    ackFuture.completeExceptionally(cause)
                    ctx.close()
                }
            })

            stream.writeAndFlush(Unpooled.copiedBuffer("${json.encodeToString(envelope)}\n", CharsetUtil.UTF_8))
            val raw = ackFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val estado = interpretarRespuesta(raw, envelope)
            when (estado) {
                RESP_ACK          -> Log.i(TAG, "[SEND] ✅ ${envelope.type} entregado (${envelope.msgId.take(8)})")
                RESP_PEER_OFFLINE -> Log.w(TAG, "[SEND] ⚠️ Destino desconectado, queda pendiente (${envelope.msgId.take(8)})")
                RESP_REJECTED     -> Log.e(TAG, "[SEND] ❌ El receptor rechazó el sobre (${envelope.msgId.take(8)})")
                else              -> Log.w(TAG, "[SEND] Respuesta no verificable ($estado), queda pendiente")
            }
            estado
        } catch (e: Exception) {
            val causa = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val msg   = e.cause?.message ?: e.message
            Log.e(TAG, "[SEND] ❌ Error: $causa - $msg")
            "ERROR"
        }
    }

    fun shutdown() {
        serviceScope.cancel()
        host = null
        _relayConectado.value = false
        Log.i(TAG, "[SHUTDOWN] Servicio detenido.")
    }
}
