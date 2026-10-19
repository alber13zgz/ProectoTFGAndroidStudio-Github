// ══════════════════════════════════════════════════════════════════════
// Nodo relay MedP2P — proxy de streams a nivel de aplicación (libp2p 1.4.x)
//
// Recibe sobres P2P de los móviles, lee SOLO destPeerId para enrutar y
// reenvía la línea tal cual al destino; devuelve al emisor la respuesta
// del destino (acuse firmado) sin modificarla. No guarda nada: si el
// destino no está conectado responde PEER_OFFLINE y el emisor lo encola.
//
// El relay NO es de confianza: el contenido va cifrado de extremo a extremo
// y firmado por el emisor; los móviles verifican todo por su cuenta.
// ══════════════════════════════════════════════════════════════════════
import { createLibp2p } from 'libp2p'
import { tcp } from '@libp2p/tcp'
import { noise } from '@chainsafe/libp2p-noise'
import { yamux } from '@chainsafe/libp2p-yamux'
import { mplex } from '@libp2p/mplex'
import { circuitRelayServer } from '@libp2p/circuit-relay-v2'
import { identify } from '@libp2p/identify'
import { createEd25519PeerId, createFromJSON } from '@libp2p/peer-id-factory'
import { toString as uint8ArrayToString } from 'uint8arrays/to-string'
import { fromString as uint8ArrayFromString } from 'uint8arrays/from-string'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

const PROTOCOL_ID = '/medp2p/registro/1.0.0'
// Igual que MAX_FRAME_LENGTH en la app: evita que un par agote la memoria del relay
const MAX_MESSAGE_BYTES = 65536
// Identidad del relay. Su PeerId está fijado en la app (RELAY_ADDRESS), así que
// debe ser SIEMPRE la misma aunque PM2 reinicie el proceso. NO subir a GitHub.
const KEY_FILE = new URL('./relay-identity.json', import.meta.url)

const peersConectados = new Map()

/** Carga la identidad persistente del relay (o la crea y la guarda la primera vez). */
async function cargarIdentidad () {
  if (existsSync(KEY_FILE)) {
    const peerId = await createFromJSON(JSON.parse(readFileSync(KEY_FILE, 'utf8')))
    console.log('Identidad cargada de', KEY_FILE.pathname)
    return peerId
  }
  const peerId = await createEd25519PeerId()
  writeFileSync(KEY_FILE, JSON.stringify({
    id: peerId.toString(),
    privKey: uint8ArrayToString(peerId.privateKey, 'base64pad'),
    pubKey: uint8ArrayToString(peerId.publicKey, 'base64pad')
  }), { mode: 0o600 })
  console.log('Identidad NUEVA generada en', KEY_FILE.pathname, '→ actualizar RELAY_ADDRESS en la app')
  return peerId
}

async function responder (stream, texto) {
  try { await stream.sink([uint8ArrayFromString(texto + '\n')]) } catch (_) {}
}

const node = await createLibp2p({
  peerId: await cargarIdentidad(),
  addresses: { listen: ['/ip4/0.0.0.0/tcp/4001'] },
  transports: [tcp()],
  connectionEncryption: [noise()],
  streamMuxers: [yamux(), mplex()],
  services: {
    identify: identify(),
    relay: circuitRelayServer({ reservations: { maxReservations: 128 } })
  }
})

node.handle(PROTOCOL_ID, async ({ stream, connection }) => {
  // PeerId del emisor AUTENTICADO por Noise en esta conexión
  const remitenteConexion = connection.remotePeer.toString()
  console.log(`[RELAY-MSG] Stream de ${remitenteConexion.substring(0, 20)}...`)

  try {
    const chunks = []
    let total = 0
    for await (const chunk of stream.source) {
      const bytes = chunk.subarray()
      chunks.push(bytes)
      total += bytes.length
      if (total > MAX_MESSAGE_BYTES) {
        console.warn('[RELAY-MSG] Mensaje demasiado grande, descartado')
        return responder(stream, 'ERROR')
      }
      if (bytes.includes(0x0A)) break
    }

    const rawMessage = Buffer.concat(chunks).toString('utf8').trim()
    const jsonStart = rawMessage.indexOf('{')
    if (jsonStart === -1) {
      console.error('[RELAY-MSG] No se encontró JSON en el mensaje')
      return responder(stream, 'ERROR')
    }
    const cleanMessage = rawMessage.substring(jsonStart)

    let envelope
    try {
      envelope = JSON.parse(cleanMessage)
    } catch (e) {
      console.error('[RELAY-MSG] JSON inválido:', e.message)
      return responder(stream, 'ERROR')
    }

    const { destPeerId, senderPeerId } = envelope
    if (!destPeerId) {
      console.error('[RELAY-MSG] Falta destPeerId en el envelope')
      return responder(stream, 'ERROR')
    }
    // Defensa en profundidad: el remitente declarado debe ser quien abrió la
    // conexión. La verificación real (firma Ed25519) la hace el destinatario.
    if (senderPeerId !== remitenteConexion) {
      console.warn(`[RELAY-MSG] senderPeerId no coincide con la conexión (${remitenteConexion.substring(0, 20)}), descartado`)
      return responder(stream, 'ERROR')
    }

    const destConn = peersConectados.get(destPeerId)
    if (!destConn) {
      console.warn(`[RELAY-MSG] Peer offline: ${destPeerId.substring(0, 20)}...`)
      return responder(stream, 'PEER_OFFLINE')
    }

    console.log(`[RELAY-MSG] ${envelope.type ?? '?'} → ${destPeerId.substring(0, 20)}... (${cleanMessage.length} bytes)`)
    const outStream = await destConn.newStream([PROTOCOL_ID])
    await outStream.sink([uint8ArrayFromString(cleanMessage + '\n')])

    // La respuesta del destino (acuse firmado) se devuelve sin tocarla
    let ack = ''
    for await (const chunk of outStream.source) {
      ack += uint8ArrayToString(chunk.subarray())
      if (ack.length > MAX_MESSAGE_BYTES) break
      if (ack.includes('\n')) break
    }
    await responder(stream, ack.trim())
  } catch (e) {
    console.error('[RELAY-MSG] Error:', e.message)
    await responder(stream, 'ERROR')
  }
})

node.addEventListener('peer:connect', e => {
  const peerId = e.detail.toString()
  const conns = node.getConnections(e.detail)
  if (conns.length > 0) {
    peersConectados.set(peerId, conns[0])
    console.log(`✓ Peer conectado: ${peerId.substring(0, 20)}... (total: ${peersConectados.size})`)
  }
})

node.addEventListener('peer:disconnect', e => {
  const peerId = e.detail.toString()
  peersConectados.delete(peerId)
  console.log(`✗ Peer desconectado: ${peerId.substring(0, 20)}... (total: ${peersConectados.size})`)
})

await node.start()
console.log('PeerId:', node.peerId.toString())
console.log('Relay arrancado en puerto 4001')
