package com.alberto.medp2p_poc.data.p2p

import android.content.Context
import android.util.Log
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════
// PROTOCOLO DE MENSAJES P2P — Versión Final (TFG)
//
// ARQUITECTURA: Application-Layer Relay con E2EE
//
// El servidor AWS (Node.js + js-libp2p) actúa como proxy de streams:
//   1. Android abre newStream al servidor con PROTOCOL_ID
//   2. El servidor lee destPeerId del JSON y reenvía los bytes al destino
//   3. El destino responde ACK que el servidor devuelve al emisor
//
// E2EE: el payload médico va cifrado con AES-256-GCM derivando la
// clave del peerId del destinatario. El servidor solo ve destPeerId
// y senderPeerId en texto plano para el enrutamiento — nunca el
// contenido médico.
// ══════════════════════════════════════════════════════════════════════

@Serializable
data class P2PEnvelope(
    val type: String,
    val destPeerId: String,    // texto plano — el relay lo lee para enrutar
    val senderPeerId: String,  // texto plano — el receptor lo usa para descifrar
    val payload: String        // AES-256-GCM cifrado — el relay no puede leerlo
)

@Serializable
data class LinkDoctorPayload(
    val doctorPeerId: String,
    val doctorName: String,
    val doctorPhotoUri: String = ""
)

@Serializable
data class PautaPayload(
    val id: String,
    val patientPeerId: String,
    val doctorCreatorPeerId: String,
    val medicacion: String,
    val dosis: String,
    val frecuenciaDiaria: Int,
    val fechaInicio: Long,
    val fechaFin: Long
)

@Serializable
data class SuministroPayload(
    val id: String,
    val pautaId: String,
    val patientPeerId: String,
    val doctorAdministeredPeerId: String,
    val timestampSuministro: Long
)

@Serializable
data class UnlinkPatientPayload(val patientPeerId: String)

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
        const val TYPE_UNLINK_PATIENT   = "UNLINK_PATIENT"
    }

    private val _incomingMessages = MutableSharedFlow<MedicalRecord>(
        replay = 0, extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<MedicalRecord> = _incomingMessages

    private val _syncEvents = MutableSharedFlow<Long>(
        replay = 0, extraBufferCapacity = 16
    )
    val syncEvents: SharedFlow<Long> = _syncEvents

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var ownerPeerId: String = ""

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
                        try {
                            val jsonElement = json.parseToJsonElement(jsonString)
                            val type = jsonElement.jsonObject["type"]
                                ?.jsonPrimitive?.content ?: TYPE_MEDICAL_RECORD
                            when (type) {
                                TYPE_MEDICAL_RECORD   -> handleMedicalRecord(ctx, jsonString)
                                TYPE_LINK_DOCTOR      -> handleLinkDoctor(ctx, jsonString)
                                TYPE_SEND_PAUTA       -> handleSendPauta(ctx, jsonString)
                                TYPE_SUMINISTRO_PAUTA -> handleSuministroPauta(ctx, jsonString)
                                TYPE_UNLINK_PATIENT   -> handleUnlinkPatient(ctx, jsonString)
                                else -> Log.w(TAG, "[RECEPTOR] Tipo desconocido: $type.")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[RECEPTOR] Sin campo type, intentando legacy")
                            try { handleMedicalRecord(ctx, jsonString) }
                            catch (e2: Exception) { Log.e(TAG, "[RECEPTOR] JSON inválido: ${e2.message}") }
                        }
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

    private fun handleMedicalRecord(ctx: ChannelHandlerContext, jsonString: String) {
        val record = try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            // La clave se deriva del senderPeerId — mismo valor que usó el emisor
            // al cifrar con CryptoUtils.encrypt(recordJson, destPeerId=ESTE_NODO)
            // Como la clave se deriva del peerId del DESTINATARIO, y aquí somos
            // el destinatario, usamos nuestro ownerPeerId para descifrar.
            val decryptedJson = CryptoUtils.decrypt(envelope.payload, ownerPeerId)
            json.decodeFromString<MedicalRecord>(decryptedJson)
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error descifrando MedicalRecord: ${e.message}")
            return
        }

        serviceScope.launch {
            dbHelper.guardarRegistroMedico(record.copy(isMine = false), ownerPeerId)
            Log.d(TAG, "[RECEPTOR] MedicalRecord guardado: id=${record.id}")
            _incomingMessages.emit(record)
            _syncEvents.emit(System.currentTimeMillis())
            sendAck(ctx)
        }
    }

    private fun handleLinkDoctor(ctx: ChannelHandlerContext, jsonString: String) {
        try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            // Mismo razonamiento: somos el destinatario, usamos ownerPeerId
            val decryptedJson = CryptoUtils.decrypt(envelope.payload, ownerPeerId)
            val payload = json.decodeFromString<LinkDoctorPayload>(decryptedJson)

            serviceScope.launch {
                dbHelper.guardarMedicoVinculado(
                    doctorPeerId = payload.doctorPeerId,
                    doctorName   = payload.doctorName,
                    ownerPeerId  = ownerPeerId,
                    doctorPhotoUri = payload.doctorPhotoUri
                )
                Log.i(TAG, "[RECEPTOR] Médico vinculado: ${payload.doctorName}")
                _syncEvents.emit(System.currentTimeMillis())
                sendAck(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando LINK_DOCTOR: ${e.message}")
        }
    }

    private fun handleSendPauta(ctx: ChannelHandlerContext, jsonString: String) {
        try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            val decryptedJson = CryptoUtils.decrypt(envelope.payload, ownerPeerId)
            val p = json.decodeFromString<PautaPayload>(decryptedJson)
            serviceScope.launch {
                dbHelper.insertarPautaMedicaV2(
                    com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                        id = p.id, patientPeerId = p.patientPeerId,
                        doctorCreatorPeerId = p.doctorCreatorPeerId,
                        medicacion = p.medicacion, dosis = p.dosis,
                        frecuenciaDiaria = p.frecuenciaDiaria,
                        fechaInicio = p.fechaInicio, fechaFin = p.fechaFin
                    )
                )
                Log.i(TAG, "[RECEPTOR] PautaMedicaV2 guardada: ${p.medicacion}")
                _syncEvents.emit(System.currentTimeMillis())
                sendAck(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando SEND_PAUTA: ${e.message}")
        }
    }

    private fun handleSuministroPauta(ctx: ChannelHandlerContext, jsonString: String) {
        try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            val decryptedJson = CryptoUtils.decrypt(envelope.payload, ownerPeerId)
            val s = json.decodeFromString<SuministroPayload>(decryptedJson)
            serviceScope.launch {
                dbHelper.insertarRegistroSuministro(
                    com.alberto.medp2p_poc.data.model.RegistroSuministro(
                        id = s.id, pautaId = s.pautaId,
                        patientPeerId = s.patientPeerId,
                        doctorAdministeredPeerId = s.doctorAdministeredPeerId,
                        timestampSuministro = s.timestampSuministro
                    )
                )
                Log.i(TAG, "[RECEPTOR] RegistroSuministro guardado: pauta=${s.pautaId.take(8)}")
                _syncEvents.emit(System.currentTimeMillis())
                sendAck(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando SUMINISTRO_PAUTA: ${e.message}")
        }
    }

    private fun handleUnlinkPatient(ctx: ChannelHandlerContext, jsonString: String) {
        try {
            val envelope      = json.decodeFromString<P2PEnvelope>(jsonString)
            val decryptedJson = CryptoUtils.decrypt(envelope.payload, ownerPeerId)
            val payload       = json.decodeFromString<UnlinkPatientPayload>(decryptedJson)
            serviceScope.launch {
                dbHelper.desvincularPaciente(payload.patientPeerId, ownerPeerId)
                Log.i(TAG, "[RECEPTOR] Paciente desvinculado: ${payload.patientPeerId.take(12)}")
                _syncEvents.emit(System.currentTimeMillis())
                sendAck(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando UNLINK_PATIENT: ${e.message}")
        }
    }

    private fun sendAck(ctx: ChannelHandlerContext) {
        if (ctx.channel().isActive) {
            ctx.writeAndFlush(Unpooled.copiedBuffer("ACK\n", CharsetUtil.UTF_8))
            Log.i(TAG, "[RECEPTOR] ACK enviado.")
        }
    }

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

    suspend fun start(privKey: PrivKey, ownerPeerId: String = ""): Host = withContext(Dispatchers.IO) {
        this@P2PMessagingService.ownerPeerId = ownerPeerId
        Log.d(TAG, "[START] ownerPeerId=$ownerPeerId")

        val node = buildNode(privKey)
        node.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.i(TAG, "[START] Nodo arrancado. PeerId=${node.peerId}")

        val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
        val relayPeerId    = PeerId.fromBase58(RELAY_ADDRESS.substringAfterLast("/"))
        node.network.connect(relayPeerId, relayMultiaddr)
            .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.i(TAG, "[START] ✅ Conectado al relay AWS.")

        node
    }

    suspend fun sendMedicalRecord(
        host: Host,
        destinationCircuitAddr: String,
        record: MedicalRecord
    ): Unit = withContext(Dispatchers.IO) {
        dbHelper.guardarRegistroMedico(record.copy(isMine = true), ownerPeerId)
        dbHelper.enqueueSyncLog(record.id, "historial_clinico", "SEND_PENDING", ownerPeerId)

        val destPeerId       = destinationCircuitAddr.substringAfterLast("/")
        val recordJson       = json.encodeToString(record)
        // Cifrar con la clave derivada del peerId del destinatario
        val encryptedPayload = CryptoUtils.encrypt(recordJson, destPeerId)

        val envelope = P2PEnvelope(
            type         = TYPE_MEDICAL_RECORD,
            destPeerId   = destPeerId,
            senderPeerId = ownerPeerId,
            payload      = encryptedPayload
        )

        val success = sendEnvelope(host, json.encodeToString(envelope), record.id)
        if (success) _syncEvents.emit(System.currentTimeMillis())
    }

    suspend fun sendLinkDoctorMessage(
        host: Host,
        destinationCircuitAddr: String,
        doctorPeerId: String,
        doctorName: String,
        doctorPhotoUri: String = ""
    ): Unit = withContext(Dispatchers.IO) {
        val destPeerId       = destinationCircuitAddr.substringAfterLast("/")
        val payloadJson      = json.encodeToString(LinkDoctorPayload(doctorPeerId, doctorName))
        val encryptedPayload = CryptoUtils.encrypt(payloadJson, destPeerId)

        val envelope = P2PEnvelope(
            type         = TYPE_LINK_DOCTOR,
            destPeerId   = destPeerId,
            senderPeerId = ownerPeerId,
            payload      = encryptedPayload
        )

        Log.i(TAG, "[LINK_DOCTOR] Enviando vinculación a $destPeerId")
        sendEnvelope(host, json.encodeToString(envelope), doctorPeerId)
    }

    suspend fun sendPautaMessage(
        host: Host,
        destinationCircuitAddr: String,
        pauta: com.alberto.medp2p_poc.data.model.PautaMedicaV2
    ): Unit = withContext(Dispatchers.IO) {
        val destPeerId = destinationCircuitAddr.substringAfterLast("/")
        val payloadJson = json.encodeToString(PautaPayload(
            id = pauta.id, patientPeerId = pauta.patientPeerId,
            doctorCreatorPeerId = pauta.doctorCreatorPeerId,
            medicacion = pauta.medicacion, dosis = pauta.dosis,
            frecuenciaDiaria = pauta.frecuenciaDiaria,
            fechaInicio = pauta.fechaInicio, fechaFin = pauta.fechaFin
        ))
        val encryptedPayload = CryptoUtils.encrypt(payloadJson, destPeerId)
        val envelope = P2PEnvelope(
            type = TYPE_SEND_PAUTA, destPeerId = destPeerId,
            senderPeerId = ownerPeerId, payload = encryptedPayload
        )
        Log.i(TAG, "[SEND_PAUTA] Enviando pauta a $destPeerId")
        val success = sendEnvelope(host, json.encodeToString(envelope), pauta.id)
        if (success) _syncEvents.emit(System.currentTimeMillis())
    }

    suspend fun sendSuministroMessage(
        host: Host,
        destinationCircuitAddr: String,
        registro: com.alberto.medp2p_poc.data.model.RegistroSuministro
    ): Unit = withContext(Dispatchers.IO) {
        val destPeerId = destinationCircuitAddr.substringAfterLast("/")
        val payloadJson = json.encodeToString(SuministroPayload(
            id = registro.id, pautaId = registro.pautaId,
            patientPeerId = registro.patientPeerId,
            doctorAdministeredPeerId = registro.doctorAdministeredPeerId,
            timestampSuministro = registro.timestampSuministro
        ))
        val encryptedPayload = CryptoUtils.encrypt(payloadJson, destPeerId)
        val envelope = P2PEnvelope(
            type = TYPE_SUMINISTRO_PAUTA, destPeerId = destPeerId,
            senderPeerId = ownerPeerId, payload = encryptedPayload
        )
        Log.i(TAG, "[SUMINISTRO] Enviando suministro a $destPeerId")
        val success = sendEnvelope(host, json.encodeToString(envelope), registro.id)
        if (success) _syncEvents.emit(System.currentTimeMillis())
    }

    private suspend fun sendEnvelope(
        host: Host,
        envelopeJson: String,
        logId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val relayPeerId    = PeerId.fromBase58(RELAY_ADDRESS.substringAfterLast("/"))
            val relayMultiaddr = Multiaddr(RELAY_ADDRESS)

            val streamPromise = host.newStream<Unit>(
                listOf(PROTOCOL_ID),
                relayPeerId,
                relayMultiaddr
            )
            val stream = streamPromise.stream.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

            stream.writeAndFlush(Unpooled.copiedBuffer("$envelopeJson\n", CharsetUtil.UTF_8))
            Log.i(TAG, "[SEND] Enviado al relay AWS: $logId")

            return@withContext when (val ack = ackFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "ACK" -> {
                    dbHelper.updateSyncLogStatus(logId, "DELIVERED")
                    Log.i(TAG, "[SEND] ✅ DELIVERED: $logId")
                    true
                }
                "PEER_OFFLINE" -> {
                    Log.w(TAG, "[SEND] ⚠️ Peer offline, queda en PENDING: $logId")
                    false
                }
                else -> {
                    Log.w(TAG, "[SEND] Respuesta inesperada: $ack")
                    false
                }
            }

        } catch (e: Exception) {
            val causa = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val msg   = e.cause?.message ?: e.message
            Log.e(TAG, "[SEND] ❌ Error: $causa - $msg")
            false
        }
    }

    fun shutdown() {
        serviceScope.cancel()
        Log.i(TAG, "[SHUTDOWN] Servicio detenido.")
    }

    suspend fun sendUnlinkPatientMessage(
        host: Host,
        destinationCircuitAddr: String,
        patientPeerId: String
    ): Unit = withContext(Dispatchers.IO) {
        val destPeerId       = destinationCircuitAddr.substringAfterLast("/")
        val payloadJson      = json.encodeToString(UnlinkPatientPayload(patientPeerId))
        val encryptedPayload = CryptoUtils.encrypt(payloadJson, destPeerId)
        val envelope = P2PEnvelope(
            type         = TYPE_UNLINK_PATIENT,
            destPeerId   = destPeerId,
            senderPeerId = ownerPeerId,
            payload      = encryptedPayload
        )
        Log.i(TAG, "[UNLINK] Enviando desvinculacion a $destPeerId")
        sendEnvelope(host, json.encodeToString(envelope), patientPeerId)
    }
}