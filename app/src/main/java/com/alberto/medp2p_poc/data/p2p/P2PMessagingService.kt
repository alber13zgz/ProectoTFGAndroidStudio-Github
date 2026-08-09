package com.alberto.medp2p_poc.data.p2p


// ── Contexto Android ──────────────────────────────────────────────────
import android.content.Context
import android.util.Log

// ── Capa de dominio y base de datos ──────────────────────────────────
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.MedicalRecord

// ── jvm-libp2p: contratos de protocolo y construcción del nodo ───────
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

// ── Netty: pipeline de decodificación de bytes ────────────────────────
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.util.CharsetUtil

// ── Serialización JSON ────────────────────────────────────────────────
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── Corrutinas ────────────────────────────────────────────────────────
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Java ──────────────────────────────────────────────────────────────
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════
// JUSTIFICACIÓN ARQUITECTÓNICA: SERVICE LAYER
//
// P2PMessagingService es una clase Kotlin ordinaria (no un Android
// Service ni un ViewModel). Su responsabilidad única es gestionar los
// protocolos de aplicación P2P: construir el receptor de mensajes,
// arrancar el nodo libp2p y enviar MedicalRecords.
//
// No tiene conocimiento de la UI. No importa nada de Compose ni de
// ViewModel. Esto permite que los ViewModels sean testeables de forma
// aislada sustituyendo este servicio por un doble de prueba (mock).
//
// El DashboardViewModel es el único punto de entrada: instancia este
// servicio, llama a start() y expone el SharedFlow hacia la UI.
// ══════════════════════════════════════════════════════════════════════
class P2PMessagingService(
    private val context: Context,
    private val dbHelper: AppDatabaseHelper
) {

    companion object {
        const val PROTOCOL_ID = "/medp2p/registro/1.0.0"

        private const val RELAY_ADDRESS =
            "/ip4/13.48.59.216/tcp/4001/p2p/12D3KooWEBiChhAXXnZRPoM37aoawZbYQKp7WxqtC7LrfZFab4TV"

        private const val CONNECT_TIMEOUT_SECONDS = 15L

        // Tamaño máximo de un frame JSON. 8 KB es suficiente para un
        // MedicalRecord con texto clínico largo. Protege contra
        // ataques de frame gigante que agotarían la RAM del dispositivo.
        private const val MAX_FRAME_LENGTH = 8192

        private const val TAG = "P2P_MSG_SERVICE"
    }

    // ══════════════════════════════════════════════════════════════════
    // BUS DE EVENTOS: SharedFlow de mensajes entrantes
    //
    // Por qué SharedFlow y NO StateFlow:
    //   StateFlow representa ESTADO: solo conserva el último valor y
    //   colapsa emisiones rápidas. Si dos MedicalRecord llegasen en
    //   milisegundos, StateFlow descartaría el primero. Inaceptable.
    //
    //   SharedFlow representa EVENTOS discretos: cada emisión es
    //   independiente. Todos los colectores activos reciben cada mensaje.
    //
    // replay = 0: Los nuevos colectores NO reciben mensajes del pasado.
    //   Esos ya están en SQLite. Evitamos duplicados al navegar.
    //
    // extraBufferCapacity = 64: Buffer para absorber ráfagas sin
    //   bloquear el hilo del receptor Netty.
    // ══════════════════════════════════════════════════════════════════
    private val _incomingMessages = MutableSharedFlow<MedicalRecord>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<MedicalRecord> = _incomingMessages

    // ══════════════════════════════════════════════════════════════════
    // SCOPE PROPIO DEL SERVICIO
    //
    // Por qué un scope propio y no viewModelScope:
    //   El nodo P2P debe sobrevivir a rotaciones de pantalla. Android
    //   destruye y recrea el ViewModel al rotar, pero el socket TCP
    //   no debe cerrarse en ese proceso.
    //
    // Por qué SupervisorJob():
    //   Con Job normal, si un envío falla, cancela TODO el scope,
    //   incluyendo el receptor. Con SupervisorJob, el fallo de un hijo
    //   es aislado: el receptor sigue vivo aunque un envío falle.
    // ══════════════════════════════════════════════════════════════════
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Parser JSON reutilizable. ignoreUnknownKeys = true garantiza
    // compatibilidad hacia adelante: si el emisor añade campos nuevos
    // en el futuro, el receptor no fallará al deserializar.
    private val json = Json { ignoreUnknownKeys = true }

    // ══════════════════════════════════════════════════════════════════
    // FUNCIÓN 1: buildReceiverBinding()
    //
    // Construye el ProtocolBinding RECEPTOR para /medp2p/registro/1.0.0.
    //
    // Por qué debe registrarse al construir el nodo (no dinámicamente):
    //   libp2p negocia protocolos durante el handshake multistream-select
    //   al abrir cada stream. Si el protocolo no está en la lista del
    //   nodo en ese momento, el handshake falla con "protocol not
    //   supported". jvm-libp2p no permite registro de protocolos en
    //   caliente sin reiniciar el nodo.
    //
    // DECISIÓN DE DISEÑO — Semántica At-Least-Once (al menos una vez):
    //   El ACK se envía al emisor DENTRO de la corutina, estrictamente
    //   DESPUÉS de que guardarRegistroMedico() complete con éxito.
    //   Esto implementa la garantía semántica "ACK = dato persistido
    //   en disco". Si la app crashea tras escribir en SQLite pero antes
    //   del ACK, el emisor reintentará el envío (el dato puede llegar
    //   dos veces, de ahí "At-Least-Once", pero nunca se pierde).
    //   Esta semántica es la adecuada para datos clínicos donde la
    //   pérdida de datos es inaceptable.
    // ══════════════════════════════════════════════════════════════════
    private fun buildReceiverBinding(): ProtocolBinding<Unit> {

        return object : ProtocolBinding<Unit> {

            override val protocolDescriptor = ProtocolDescriptor(PROTOCOL_ID)

            override fun initChannel(
                ch: P2PChannel,
                selectedProtocol: String
            ): CompletableFuture<Unit> {

                // HANDLER 1: LineBasedFrameDecoder
                // TCP es un flujo de bytes, no de mensajes. Un JSON puede
                // llegar partido en varios segmentos TCP. Este handler
                // acumula bytes hasta encontrar '\n' y solo entonces pasa
                // el frame completo al siguiente handler del pipeline.
                ch.pushHandler(LineBasedFrameDecoder(MAX_FRAME_LENGTH))

                // HANDLER 2: StringDecoder
                // Convierte el ByteBuf (ya completo y delimitado) a String
                // UTF-8. A partir de aquí el pipeline trabaja con Strings.
                ch.pushHandler(StringDecoder(CharsetUtil.UTF_8))

                // HANDLER 3: Lógica de dominio
                ch.pushHandler(object : ChannelInboundHandlerAdapter() {

                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        val jsonString = msg as String
                        Log.i(TAG, "[RECEPTOR] JSON recibido: $jsonString")

                        try {
                            val record = json.decodeFromString<MedicalRecord>(jsonString)

                            // Lanzamos corutina para NO bloquear el EventLoop
                            // de Netty. Bloquear el EventLoop degradaría el
                            // rendimiento de TODAS las conexiones del nodo.
                            serviceScope.launch {

                                // PASO 1: Persistir en SQLite.
                                // isMine = false: este registro viene del
                                // peer remoto, no lo generamos nosotros.
                                dbHelper.guardarRegistroMedico(
                                    record.copy(isMine = false)
                                )
                                Log.d(TAG, "[RECEPTOR] Guardado en DB: id=${record.id}")

                                // PASO 2: Emitir al SharedFlow.
                                // La UI se actualiza en tiempo real.
                                // Si no hay colectores, el emit descarta
                                // el evento (el dato ya está en disco).
                                _incomingMessages.emit(record)
                                Log.d(TAG, "[RECEPTOR] Emitido al SharedFlow: id=${record.id}")

                                // PASO 3: ACK después de persistir.
                                // GARANTÍA SEMÁNTICA AT-LEAST-ONCE:
                                // El ACK solo se envía si guardarRegistroMedico()
                                // completó sin excepción.
                                if (ctx.channel().isActive) {
                                    ctx.writeAndFlush(
                                        Unpooled.copiedBuffer("ACK\n", CharsetUtil.UTF_8)
                                    )
                                    Log.i(TAG, "[RECEPTOR] ACK enviado tras persistencia.")
                                } else {
                                    Log.w(TAG, "[RECEPTOR] Canal cerrado antes del ACK. " +
                                            "El emisor reintentará (At-Least-Once).")
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "[RECEPTOR] JSON inválido: ${e.message}")
                            Log.e(TAG, "[RECEPTOR] Contenido problemático: $jsonString")
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

    // ══════════════════════════════════════════════════════════════════
    // FUNCIÓN 2: buildNode()
    //
    // Registra en el nodo DOS protocolos simultáneamente:
    //   1. CircuitStopProtocol → infraestructura de relay.
    //   2. buildReceiverBinding() → protocolo de aplicación MedP2P.
    //
    // Por qué listen("/ip4/0.0.0.0/tcp/0"):
    //   Puerto 0 → el SO asigna un puerto libre automáticamente.
    //   En móvil, fijar un puerto concreto fallaría si está ocupado.
    // ══════════════════════════════════════════════════════════════════
    private fun buildNode(privKey: PrivKey): Host {
        return host {
            identity {
                factory = { privKey }
            }
            transports {
                add(::TcpTransport)
            }
            secureChannels {
                add(::NoiseXXSecureChannel)
            }
            muxers {
                add(StreamMuxerProtocol.getYamux())
            }
            network {
                listen("/ip4/0.0.0.0/tcp/0")
            }
            protocols {
                add(CircuitStopProtocol.Binding(CircuitStopProtocol()))
                add(buildReceiverBinding())
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FUNCIÓN 3: start()
    //
    // Arranca el nodo y conecta al relay. Función suspending: se llama
    // desde viewModelScope del DashboardViewModel (Dispatchers.IO).
    // Lanza excepción si falla; el ViewModel la captura y actualiza
    // el ConnectionStatus en la UI.
    // ══════════════════════════════════════════════════════════════════
    suspend fun start(privKey: PrivKey): Host = withContext(Dispatchers.IO) {
        Log.d(TAG, "[START] Construyendo nodo con protocolo receptor registrado...")
        val node = buildNode(privKey)

        node.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.i(TAG, "[START] Nodo arrancado. PeerId=${node.peerId}")

        Log.d(TAG, "[START] Conectando al relay: $RELAY_ADDRESS")
        val relayMultiaddr = Multiaddr(RELAY_ADDRESS)
        val relayPeerId    = PeerId.fromBase58(
            RELAY_ADDRESS.substringAfterLast("/")
        )
        node.network.connect(relayPeerId, relayMultiaddr)
            .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        Log.i(TAG, "[START] ✅ Conectado al relay. Nodo listo para recibir mensajes.")
        node
    }

    // ══════════════════════════════════════════════════════════════════
    // FUNCIÓN 4: sendMedicalRecord()
    //
    // Implementa el patrón "Transactional Outbox":
    //   FASE 1 (Write-ahead): persistir en SQLite + encolar en sync_log
    //   FASE 2 (Red): abrir stream P2P y enviar JSON terminado en '\n'
    //   FASE 3 (Confirmación): esperar ACK → marcar sync_log DELIVERED
    //
    // Semántica At-Least-Once: el UUID del record actúa como clave de
    // idempotencia (INSERT OR REPLACE en el receptor no duplica datos).
    // ══════════════════════════════════════════════════════════════════
    suspend fun sendMedicalRecord(
        host: Host,
        destinationCircuitAddr: String,
        record: MedicalRecord
    ): Unit = withContext(Dispatchers.IO) {

        // ── FASE 1: WRITE-AHEAD LOCAL ──────────────────────────────────
        Log.d(TAG, "[SEND] Fase 1: Persistiendo localmente id=${record.id}")
        dbHelper.guardarRegistroMedico(record.copy(isMine = true))

        Log.d(TAG, "[SEND] Fase 1: Encolando en sync_log como SEND_PENDING id=${record.id}")
        dbHelper.enqueueSyncLog(
            recordId = record.id,
            tabla    = "historial_clinico",
            accion   = "SEND_PENDING"
        )

        // ── FASES 2 y 3: RED + CONFIRMACIÓN ───────────────────────────
        try {
            val destMultiaddr = Multiaddr(destinationCircuitAddr)
            val destPeerId    = PeerId.fromBase58(
                destinationCircuitAddr.substringAfterLast("/")
            )
            Log.d(TAG, "[SEND] Fase 2: Abriendo stream hacia $destPeerId")

            // Pipeline del lado EMISOR: necesita framer y decoder
            // para poder leer el "ACK\n" de respuesta del receptor.
            val senderBinding = object : ProtocolBinding<Unit> {
                override val protocolDescriptor = ProtocolDescriptor(PROTOCOL_ID)
                override fun initChannel(
                    ch: P2PChannel,
                    selectedProtocol: String
                ): CompletableFuture<Unit> {
                    ch.pushHandler(LineBasedFrameDecoder(MAX_FRAME_LENGTH))
                    ch.pushHandler(StringDecoder(CharsetUtil.UTF_8))
                    return CompletableFuture.completedFuture(Unit)
                }
            }

            val stream = senderBinding
                .dial(host, destPeerId, destMultiaddr)
                .stream
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.i(TAG, "[SEND] Fase 2: Stream establecido con $destPeerId")

            // CompletableFuture como puente Netty ↔ Corrutina.
            // Registramos el lector del ACK ANTES de enviar el mensaje
            // para evitar condición de carrera con peers muy rápidos.
            val ackFuture = CompletableFuture<String>()
            stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    ackFuture.complete((msg as String).trim())
                }
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    Log.e(TAG, "[SEND] Error leyendo ACK: ${cause.message}")
                    ackFuture.completeExceptionally(cause)
                    ctx.close()
                }
            })

            // Enviamos el JSON terminado en '\n' — delimitador obligatorio
            // para que LineBasedFrameDecoder del receptor dispare channelRead.
            val jsonString = json.encodeToString(record)
            stream.writeAndFlush(
                Unpooled.copiedBuffer("$jsonString\n", CharsetUtil.UTF_8)
            )
            Log.i(TAG, "[SEND] Fase 2: JSON enviado (${jsonString.length} bytes)")

            // ── FASE 3: ESPERAR ACK ────────────────────────────────────
            val ackResponse = ackFuture.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (ackResponse == "ACK") {
                dbHelper.updateSyncLogStatus(record.id, "DELIVERED")
                Log.i(TAG, "[SEND] ✅ Fase 3: DELIVERED confirmado. id=${record.id}")
            } else {
                Log.w(TAG, "[SEND] ⚠️ Respuesta inesperada: '$ackResponse'. Queda PENDING.")
            }

        } catch (e: Exception) {
            // Fallo de red: el dato ya está en SQLite y en sync_log
            // como PENDING. WorkManager puede reintentarlo más tarde.
            Log.e(TAG, "[SEND] ❌ Error de red al enviar id=${record.id}: ${e.javaClass.simpleName}")
            Log.e(TAG, "[SEND]   sync_log permanece PENDING para reintento futuro.")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FUNCIÓN 5: shutdown()
    //
    // Cancela el serviceScope (y todas sus corutinas hijas).
    // El Host NO se detiene aquí — es responsabilidad del ViewModel
    // (que lo detiene en onCleared()). Separar responsabilidades evita
    // que el ViewModel tenga una referencia a un nodo detenido sin saberlo.
    // ══════════════════════════════════════════════════════════════════
    fun shutdown() {
        Log.d(TAG, "[SHUTDOWN] Cancelando serviceScope...")
        serviceScope.cancel()
        Log.i(TAG, "[SHUTDOWN] Servicio de mensajería detenido.")
    }
}