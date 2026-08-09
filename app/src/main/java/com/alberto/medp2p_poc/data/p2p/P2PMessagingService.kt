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
// PROTOCOLO DE MENSAJES P2P — Versión 2
//
// FIX FALLO 3 — Vinculación bidireccional:
// Se introduce el concepto de "tipo de mensaje". El campo "type" en
// el JSON determina cómo procesar el payload:
//
//   type = "MEDICAL_RECORD" → MedicalRecord (comportamiento anterior)
//   type = "LINK_DOCTOR"    → LinkDoctorPayload (nuevo)
//
// El receptor deserializa primero solo el campo "type" con
// Json.parseToJsonElement() antes de deserializar el payload completo.
// Esto es tolerante a versiones: mensajes con tipos desconocidos se
// descartan con un log de advertencia sin cerrar el canal.
// ══════════════════════════════════════════════════════════════════════

@Serializable
data class P2PEnvelope(
    val type: String,
    val payload: String  // JSON del payload serializado como String
)

@Serializable
data class LinkDoctorPayload(
    val doctorPeerId: String,
    val doctorName: String
)

class P2PMessagingService(
    private val context: Context,
    private val dbHelper: AppDatabaseHelper
) {
    companion object {
        const val PROTOCOL_ID = "/medp2p/registro/1.0.0"
        private const val RELAY_ADDRESS =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWEBiChhAXXnZRPoM37aoawZbYQKp7WxqtC7LrfZFab4TV"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val MAX_FRAME_LENGTH = 8192
        private const val TAG = "P2P_MSG_SERVICE"

        const val TYPE_MEDICAL_RECORD = "MEDICAL_RECORD"
        const val TYPE_LINK_DOCTOR    = "LINK_DOCTOR"
    }

    private val _incomingMessages = MutableSharedFlow<MedicalRecord>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<MedicalRecord> = _incomingMessages

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // ownerPeerId del usuario activo — se establece en start()
    // para que el receptor sepa a qué cuenta pertenecen los datos
    private var ownerPeerId: String = ""

    // ══════════════════════════════════════════════════════════════
    // buildReceiverBinding() — FIX FALLO 3
    //
    // ANTES: solo deserializaba MedicalRecord.
    // AHORA: deserializa P2PEnvelope y despacha según el campo "type":
    //   - MEDICAL_RECORD → guarda en historial_clinico con ownerPeerId
    //   - LINK_DOCTOR    → guarda en medico_vinculado con ownerPeerId
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
                            // Paso 1: leer el tipo del envelope
                            val jsonElement = json.parseToJsonElement(jsonString)
                            val type = jsonElement.jsonObject["type"]
                                ?.jsonPrimitive?.content ?: TYPE_MEDICAL_RECORD

                            when (type) {
                                TYPE_MEDICAL_RECORD -> handleMedicalRecord(ctx, jsonString)
                                TYPE_LINK_DOCTOR    -> handleLinkDoctor(ctx, jsonString)
                                else -> Log.w(TAG, "[RECEPTOR] Tipo desconocido: $type. Ignorando.")
                            }

                        } catch (e: Exception) {
                            // Compatibilidad hacia atrás: si el JSON no tiene
                            // campo "type", asumimos que es un MedicalRecord legacy
                            Log.w(TAG, "[RECEPTOR] Sin campo type, intentando como MedicalRecord legacy")
                            try {
                                handleMedicalRecord(ctx, jsonString)
                            } catch (e2: Exception) {
                                Log.e(TAG, "[RECEPTOR] JSON inválido: ${e2.message}")
                            }
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
        // Intenta deserializar como P2PEnvelope primero, luego como MedicalRecord directo
        val record = try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            json.decodeFromString<MedicalRecord>(envelope.payload)
        } catch (e: Exception) {
            json.decodeFromString<MedicalRecord>(jsonString)
        }

        serviceScope.launch {
            dbHelper.guardarRegistroMedico(record.copy(isMine = false), ownerPeerId)
            Log.d(TAG, "[RECEPTOR] MedicalRecord guardado: id=${record.id}")
            _incomingMessages.emit(record)
            sendAck(ctx)
        }
    }

    private fun handleLinkDoctor(ctx: ChannelHandlerContext, jsonString: String) {
        try {
            val envelope = json.decodeFromString<P2PEnvelope>(jsonString)
            val payload  = json.decodeFromString<LinkDoctorPayload>(envelope.payload)

            serviceScope.launch {
                dbHelper.guardarMedicoVinculado(
                    doctorPeerId = payload.doctorPeerId,
                    doctorName   = payload.doctorName,
                    ownerPeerId  = ownerPeerId
                )
                Log.i(TAG, "[RECEPTOR] Médico vinculado: ${payload.doctorName}")
                sendAck(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error procesando LINK_DOCTOR: ${e.message}")
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

        Log.i(TAG, "[START] ✅ Conectado al relay.")
        node
    }

    // ══════════════════════════════════════════════════════════════
    // sendMedicalRecord() — wraps en P2PEnvelope
    // ══════════════════════════════════════════════════════════════
    suspend fun sendMedicalRecord(
        host: Host,
        destinationCircuitAddr: String,
        record: MedicalRecord
    ): Unit = withContext(Dispatchers.IO) {
        dbHelper.guardarRegistroMedico(record.copy(isMine = true), ownerPeerId)
        dbHelper.enqueueSyncLog(record.id, "historial_clinico", "SEND_PENDING", ownerPeerId)

        val envelope = P2PEnvelope(
            type    = TYPE_MEDICAL_RECORD,
            payload = json.encodeToString(record)
        )
        sendEnvelope(host, destinationCircuitAddr, json.encodeToString(envelope), record.id)
    }

    // ══════════════════════════════════════════════════════════════
    // sendLinkDoctorMessage() — FIX FALLO 3
    //
    // Envía un mensaje LINK_DOCTOR al paciente para que guarde al
    // médico en su tabla medico_vinculado. Se llama automáticamente
    // desde DashboardViewModel.linkPatient() tras el INSERT local.
    // ══════════════════════════════════════════════════════════════
    suspend fun sendLinkDoctorMessage(
        host: Host,
        destinationCircuitAddr: String,
        doctorPeerId: String,
        doctorName: String
    ): Unit = withContext(Dispatchers.IO) {
        val payload  = LinkDoctorPayload(doctorPeerId, doctorName)
        val envelope = P2PEnvelope(
            type    = TYPE_LINK_DOCTOR,
            payload = json.encodeToString(payload)
        )
        Log.i(TAG, "[LINK_DOCTOR] Enviando vinculación a $destinationCircuitAddr")
        sendEnvelope(host, destinationCircuitAddr, json.encodeToString(envelope), doctorPeerId)
    }

    // ── Lógica común de envío de stream ──────────────────────────
    private suspend fun sendEnvelope(
        host: Host,
        destinationCircuitAddr: String,
        envelopeJson: String,
        logId: String
    ) = withContext(Dispatchers.IO) {
        try {
            val destMultiaddr = Multiaddr(destinationCircuitAddr)
            val destPeerId    = PeerId.fromBase58(destinationCircuitAddr.substringAfterLast("/"))

            val senderBinding = object : ProtocolBinding<Unit> {
                override val protocolDescriptor = ProtocolDescriptor(PROTOCOL_ID)
                override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<Unit> {
                    ch.pushHandler(LineBasedFrameDecoder(MAX_FRAME_LENGTH))
                    ch.pushHandler(StringDecoder(CharsetUtil.UTF_8))
                    return CompletableFuture.completedFuture(Unit)
                }
            }

            val stream = senderBinding
                .dial(host, destPeerId, destMultiaddr)
                .stream.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val ackFuture = CompletableFuture<String>()
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
            Log.i(TAG, "[SEND] Enviado: $logId (${envelopeJson.length} bytes)")

            val ackResponse = ackFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (ackResponse == "ACK") {
                dbHelper.updateSyncLogStatus(logId, "DELIVERED")
                Log.i(TAG, "[SEND] ✅ DELIVERED: $logId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SEND] ❌ Error enviando $logId: ${e.javaClass.simpleName}")
        }
    }

    fun shutdown() {
        serviceScope.cancel()
        Log.i(TAG, "[SHUTDOWN] Servicio detenido.")
    }
}