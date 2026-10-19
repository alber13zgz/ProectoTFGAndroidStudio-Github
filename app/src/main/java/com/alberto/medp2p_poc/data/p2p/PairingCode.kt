package com.alberto.medp2p_poc.data.p2p

import android.util.Base64
import io.libp2p.core.PeerId
import java.security.SecureRandom

// ══════════════════════════════════════════════════════════════════════
// Código de vinculación de un solo uso que el paciente muestra en su QR:
//
//     "<PeerId>#<token>#<época>#<clave de cifrado X25519>"
//
//   - El PeerId identifica al paciente y contiene su clave pública Ed25519.
//   - La clave de cifrado (y su época) llegan por un canal físico (la
//     pantalla del paciente), así que el cuidador la obtiene auténtica sin
//     depender del relay.
//   - El token (128 bits aleatorios, caduca a los 15 min, un solo uso)
//     demuestra al paciente que quien pide la vinculación ha visto el QR
//     en persona. Sin él, conocer el PeerId (que el relay ve en claro para
//     enrutar) no basta para vincularse.
// ══════════════════════════════════════════════════════════════════════
object PairingCode {

    const val VALIDEZ_MS = 15L * 60 * 1000
    private const val TOKEN_BYTES = 16
    private const val SEPARADOR = '#'
    private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{22}$")   // 16 bytes en Base64url sin relleno
    private val CLAVE_REGEX = Regex("^[A-Za-z0-9_-]{43}$")   // 32 bytes en Base64url sin relleno
    private val EPOCH_REGEX = Regex("^[1-9][0-9]{0,8}$")
    private val random = SecureRandom()

    data class Parsed(val peerId: String, val token: String, val keyEpoch: Int, val keyPub: String)

    fun generarToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun codificar(peerId: String, token: String, keyEpoch: Int, keyPub: String): String =
        listOf(peerId, token, keyEpoch.toString(), keyPub).joinToString(SEPARADOR.toString())

    /** Devuelve null si el texto no es un código de vinculación válido. */
    fun parse(raw: String): Parsed? {
        val partes = raw.trim().split(SEPARADOR)
        if (partes.size != 4) return null
        val (peerId, token, epoch, keyPub) = partes
        if (!TOKEN_REGEX.matches(token) || !EPOCH_REGEX.matches(epoch) || !CLAVE_REGEX.matches(keyPub)) return null
        return try {
            PeerId.fromBase58(peerId)
            Parsed(peerId, token, epoch.toInt(), keyPub)
        } catch (e: Exception) {
            null
        }
    }
}
