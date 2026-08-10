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
import io.netty.buffer.ByteBuf
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
// PROTOCOLO DE MENSAJES P2P — Versión 4
//
// ARQUITECTURA DE ENVÍO:
// Se usa host.newStream() en lugar de senderBinding.dial() porque
// jvm-libp2p 1.1.1 no expone un API público para que el cliente
// resuelva direcciones /p2p-circuit/ con TcpTransport solo.
//
// RESERVA OBLIGATORIA (Circuit Relay v2):
// Antes de poder recibir conexiones a través del relay, cada nodo
// debe enviar un mensaje RESERVE al relay vía el protocolo HOP.
// Sin reserva, el relay no registra al peer y cualquier intento
// externo de dial lanza NothingToCompleteException.
// El mensaje RESERVE es 3 bytes en protobuf wire format manual:
//   0x02 = longitud (2 bytes)
//   0x08 = field 1 (type), wire_type varint
//   0x00 = valor 0 = RESERVE
//
// PROTOCOLO DE TIPOS DE MENSAJES:
//   type = "MEDICAL_RECORD" → nota clínica del médico al paciente
//   type = "LINK_DOCTOR"    → notificación de vinculación bidireccional
// ══════════════════════════════════════════════════════════════════════

@Serializable
data class P2PEnvelope(
    val type: String,
    val destPeerId: String,
    val payload: String
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
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWCRuAADNKXPNUBtAk3x12zK4ZdxQknZCJ5rFf77oUqgda"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val MAX_FRAME_LENGTH = 8192
        private const val TAG = "P2P_MSG_SERVICE"
        const val TYPE_MEDICAL_RECORD = "MEDICAL_RECORD"
        const val TYPE_LINK_DOCTOR    = "LINK_DOCTOR"

        // Protocolo HOP de Circuit Relay v2 — para hacer la reserva
        private const val HOP_PROTOCOL_ID = "/libp2p/circuit/relay/0.2.0/hop"
    }

    private val _incomingMessages = MutableSharedFlow<MedicalRecord>(
        replay = 0, extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<MedicalRecord> = _incomingMessages

    // Bus de eventos de sincronización — emite timestamp tras cada
    // mensaje recibido o enviado con éxito. DashboardViewModel lo
    // colecta para actualizar lastSyncTimestamp en la UI.
    private val _syncEvents = MutableSharedFlow<Long>(
        replay = 0, extraBufferCapacity = 16
    )
    val syncEvents: SharedFlow<Long> = _syncEvents

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var ownerPeerId: String = ""

    // ══════════════════════════════════════════════════════════════
    // buildReceiverBinding() — receptor de mensajes entrantes
    // Distingue el tipo de mensaje por el campo "type" del envelope.
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
                                TYPE_MEDICAL_RECORD -> handleMedicalRecord(ctx, jsonString)
                                TYPE_LINK_DOCTOR    -> handleLinkDoctor(ctx, jsonString)
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
            // ¡DESCIFRAMOS EL PAYLOAD!
            val decryptedJson = CryptoUtils.decrypt(envelope.payload)
            json.decodeFromString<MedicalRecord>(decryptedJson)
        } catch (e: Exception) {
            Log.e(TAG, "[RECEPTOR] Error descifrando: ${e.message}")
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

            val decryptedJson = CryptoUtils.decrypt(envelope.payload)
            val payload  = json.decodeFromString<LinkDoctorPayload>(decryptedJson)

            serviceScope.launch {
                dbHelper.guardarMedicoVinculado(
                    doctorPeerId = payload.doctorPeerId,
                    doctorName   = payload.doctorName,
                    ownerPeerId  = ownerPeerId
                )
                Log.i(TAG, "[RECEPTOR] Médico vinculado: ${payload.doctorName}")
                _syncEvents.emit(System.currentTimeMillis())
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

    // ══════════════════════════════════════════════════════════════
    // buildNode() — construye el nodo libp2p
    //
    // Solo CircuitStopProtocol (receptor entrante vía relay).
    // CircuitHopProtocol NO se añade: su constructor Binding es
    // privado en jvm-libp2p 1.1.1 — reservado para servidores relay.
    // El envío saliente se gestiona con host.newStream() tras la
    // reserva en el relay (reserveWithRelay).
    // ══════════════════════════════════════════════════════════════
    private fun buildNode(privKey: PrivKey): Host {
        return host {
            identity { factory = { privKey } }
            transports { add(::TcpTransport) }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { add(StreamMuxerProtocol.getYamux()) }
            network { listen("/ip4/0.0.0.0/tcp/0") }
            protocols {
                // Acepta conexiones entrantes enrutadas por el relay
                add(CircuitStopProtocol.Binding(CircuitStopProtocol()))
                // Receptor de mensajes de la aplicación
                add(buildReceiverBinding())
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // start() — arranca el nodo, conecta al relay y hace la RESERVA.
    //
    // La reserva es obligatoria en Circuit Relay v2: sin ella el
    // relay no registra a este peer y cualquier intento externo de
    // dial lanza NothingToCompleteException.
    // ══════════════════════════════════════════════════════════════
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
        Log.i(TAG, "[START] ✅ Conectado al relay TCP.")

        // Registrar este peer en el relay para que sea alcanzable
        reserveWithRelay(node, relayPeerId, relayMultiaddr)

        node
    }

    // ══════════════════════════════════════════════════════════════
    // reserveWithRelay() — envía RESERVE al relay vía protocolo HOP.
    //
    // El mensaje RESERVE es protobuf wire format construido a mano
    // (sin librería protobuf externa):
    //   Byte 0: 0x02 → longitud del payload = 2 bytes
    //   Byte 1: 0x08 → field_number=1 (type), wire_type=0 (varint)
    //   Byte 2: 0x00 → valor 0 = HopMessage.Type.RESERVE
    //
    // La respuesta del relay contiene STATUS 200 (OK) si acepta.
    // El byte 0xC8 0x01 en protobuf es el valor 200 en varint.
    // ══════════════════════════════════════════════════════════════
    private fun reserveWithRelay(host: Host, relayPeerId: PeerId, relayMultiaddr: Multiaddr) {
        try {
            // Mensaje RESERVE en protobuf wire format — 3 bytes, sin librería
            val reserveBytes = byteArrayOf(0x02, 0x08, 0x00)

            val streamPromise = host.newStream<Unit>(
                listOf(HOP_PROTOCOL_ID),
                relayPeerId,
                relayMultiaddr
            )
            val stream = streamPromise.stream.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val responseFuture = CompletableFuture<ByteArray>()
            stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    val buf = msg as ByteBuf
                    val arr = ByteArray(buf.readableBytes())
                    buf.readBytes(arr)
                    buf.release()
                    responseFuture.complete(arr)
                }
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    Log.e(TAG, "[RESERVE] Error en canal: ${cause.message}")
                    responseFuture.completeExceptionally(cause)
                }
            })

            stream.writeAndFlush(Unpooled.copiedBuffer(reserveBytes))
            Log.d(TAG, "[RESERVE] Mensaje RESERVE enviado al relay.")

            val response = responseFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // STATUS 200 = OK en Circuit Relay v2
            // En protobuf varint, 200 se codifica como 0xC8 0x01
            // Buscamos cualquiera de los dos indicadores en la respuesta
            val statusOk = response.any { it.toInt() and 0xFF == 200 } ||
                    (response.size >= 2 && response.any { it.toInt() and 0xFF == 0xC8 })

            if (statusOk) {
                Log.i(TAG, "[RESERVE] ✅ Reserva aceptada. Este peer es alcanzable vía relay.")
            } else {
                val hex = response.joinToString(" ") { "%02X".format(it) }
                Log.w(TAG, "[RESERVE] ⚠️ Respuesta inesperada del relay: $hex")
            }

        } catch (e: Exception) {
            Log.e(TAG, "[RESERVE] ❌ Error haciendo reserva: ${e.javaClass.simpleName} - ${e.message}")
            Log.w(TAG, "[RESERVE] El nodo funcionará pero puede no ser alcanzable desde fuera.")
        }
    }

    suspend fun sendMedicalRecord(
        host: Host,
        destinationCircuitAddr: String,
        record: MedicalRecord
    ): Unit = withContext(Dispatchers.IO) {
        dbHelper.guardarRegistroMedico(record.copy(isMine = true), ownerPeerId)
        dbHelper.enqueueSyncLog(record.id, "historial_clinico", "SEND_PENDING", ownerPeerId)

        // 1. Convertimos el récord a JSON normal
        val recordJson = json.encodeToString(record)
        // 2. ¡CIFRAMOS EL JSON!
        val encryptedPayload = CryptoUtils.encrypt(recordJson)
        // 3. Extraemos el destino para el sobre
        val destPeerId = destinationCircuitAddr.substringAfterLast("/")

        val envelope = P2PEnvelope(
            type    = TYPE_MEDICAL_RECORD,
            destPeerId = destPeerId, // AWS leerá esto
            payload = encryptedPayload // AWS verá texto incomprensible aquí
        )

        val success = sendEnvelope(host, destinationCircuitAddr, json.encodeToString(envelope), record.id)
        if (success) _syncEvents.emit(System.currentTimeMillis())
    }

    suspend fun sendLinkDoctorMessage(
        host: Host,
        destinationCircuitAddr: String,
        doctorPeerId: String,
        doctorName: String
    ): Unit = withContext(Dispatchers.IO) {
        val payloadObj  = LinkDoctorPayload(doctorPeerId, doctorName)
        val payloadJson = json.encodeToString(payloadObj)

        // 1. Ciframos los datos del médico
        val encryptedPayload = CryptoUtils.encrypt(payloadJson)
        // 2. Extraemos el destinatario
        val destPeerId = destinationCircuitAddr.substringAfterLast("/")

        // 3. Metemos el destinatario visible y el payload cifrado
        val envelope = P2PEnvelope(
            type       = TYPE_LINK_DOCTOR,
            destPeerId = destPeerId,
            payload    = encryptedPayload
        )

        Log.i(TAG, "[LINK_DOCTOR] Enviando vinculación a $destinationCircuitAddr")
        sendEnvelope(host, destinationCircuitAddr, json.encodeToString(envelope), doctorPeerId)
    }

    // ══════════════════════════════════════════════════════════════
    // sendEnvelope() — envía por host.newStream() sobre la conexión
    // ya establecida con el relay.
    //
    // host.newStream() con una dirección /p2p-circuit/ usa la
    // conexión TCP ya negociada con el relay. El relay, al ver que
    // el destino tiene una reserva activa, enruta el stream
    // directamente al peer de destino sin que el emisor necesite
    // conocer su IP real (NAT traversal real).
    // ══════════════════════════════════════════════════════════════
    private suspend fun sendEnvelope(
        host: Host,
        destinationCircuitAddr: String,
        envelopeJson: String,
        logId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Ya no intentamos conectar al destino final directamente.
            // Nos conectamos SIEMPRE a nuestro servidor de AWS (el cartero).
            val relayPeerId = PeerId.fromBase58(RELAY_ADDRESS.substringAfterLast("/"))
            val relayMultiaddr = Multiaddr(RELAY_ADDRESS)

            // 2. Abrimos el stream hacia AWS usando el protocolo de Node.js
            val streamPromise = host.newStream<Unit>(
                listOf("/medp2p/registro/1.0.0"), // <- ¡El protocolo de nuestro servidor AWS!
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

            // 3. Enviamos el sobre cerrado al cartero de AWS.
            // El sobre lleva dentro a quién va dirigido (destPeerId) y el historial cifrado.
            stream.writeAndFlush(Unpooled.copiedBuffer("$envelopeJson\n", CharsetUtil.UTF_8))
            Log.i(TAG, "[SEND] Enviado al relay AWS: $logId")

            // 4. Esperamos la respuesta del servidor (que nos reenviará la del paciente)
            val ackResponse = ackFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (ackResponse == "ACK") {
                dbHelper.updateSyncLogStatus(logId, "DELIVERED")
                Log.i(TAG, "[SEND] ✅ DELIVERED: $logId")
                return@withContext true
            } else if (ackResponse == "PEER_OFFLINE") {
                Log.w(TAG, "[SEND] ⚠️ El paciente no está conectado a AWS. Se queda en PENDING.")
                return@withContext false
            }

            Log.w(TAG, "[SEND] Respuesta no esperada: $ackResponse")
            false
        } catch (e: Exception) {
            val causaReal = e.cause?.javaClass?.simpleName ?: "Desconocida"
            val mensajeReal = e.cause?.message ?: e.message
            Log.e(TAG, "[SEND] ❌ Error enviando a AWS: $causaReal - $mensajeReal")
            false
        }
    }

    fun shutdown() {
        serviceScope.cancel()
        Log.i(TAG, "[SHUTDOWN] Servicio detenido.")
    }
}