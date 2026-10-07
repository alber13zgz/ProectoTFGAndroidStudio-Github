package com.alberto.medp2p_poc.data.p2p

import android.util.Base64
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.crypto.keys.Ed25519PublicKey
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ══════════════════════════════════════════════════════════════════════
// CryptoUtils v3 — E2EE con claves de cifrado rotables (esquema tipo HPKE Base)
//
// SEPARACIÓN DE CLAVES:
//   - Identidad (Ed25519, = PeerId): solo FIRMA. Nunca cambia.
//   - Cifrado (X25519, una por dispositivo y "época"): se publica firmada
//     por la identidad (EncryptionKeyBundle), se intercambia al vincular y
//     se renueva periódicamente con KEY_UPDATE.
//
// CIFRADO DE CADA MENSAJE:
//   1. El emisor genera un par X25519 EFÍMERO y hace ECDH con la clave de
//      cifrado vigente del destinatario.
//   2. HKDF-SHA256 deriva la clave AES-256 (salt = ephPub || recipientPub).
//   3. AES-256-GCM cifra el payload; cabecera (versión, época, ephPub) y
//      AAD (tipo, emisor, destino) quedan autenticados.
//
// Formato en el cable (Base64): [versión 1B][época 4B][ephPub 32B][IV 12B][cifrado+tag]
//
// El cifrado por sí solo NO autentica al emisor (cualquiera puede cifrar
// para una clave pública conocida). La autenticidad la aporta sign()/
// verifySignature(): cada sobre va firmado con la identidad Ed25519 del
// emisor, y como el AAD incluye el senderPeerId, re-firmar un sobre ajeno
// con otra identidad rompe el descifrado.
// ══════════════════════════════════════════════════════════════════════
object CryptoUtils {

    private const val ALGORITHM      = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val KEY_LEN        = 32
    private const val EPOCH_LEN      = 4
    const val VERSION: Byte          = 3

    private val HKDF_INFO = "medp2p/e2ee/v3/aes-256-gcm".toByteArray(Charsets.UTF_8)
    private val random    = SecureRandom()

    // ── Utilidades de codificación ───────────────────────────────────

    /** Concatena campos con prefijo de longitud: dos listas distintas nunca dan los mismos bytes. */
    fun lengthPrefixed(vararg fields: String): ByteArray =
        fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)

    fun toB64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun fromB64Url(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    // ── Claves de cifrado X25519 ─────────────────────────────────────

    /** Genera un par X25519 (privada, pública) para una nueva época de cifrado. */
    fun generarParX25519(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(random, it) }
        val pub  = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        return priv to pub
    }

    // ── Cifrado de extremo a extremo ─────────────────────────────────

    /** Datos autenticados: vinculan el cifrado a versión, tipo, emisor y destino. */
    fun buildAad(type: String, senderPeerId: String, destPeerId: String): ByteArray =
        "medp2p|v$VERSION|$type|$senderPeerId|$destPeerId".toByteArray(Charsets.UTF_8)

    fun encrypt(plainText: String, recipientPub: ByteArray, recipientEpoch: Int, aad: ByteArray): String =
        Base64.encodeToString(
            seal(plainText.toByteArray(Charsets.UTF_8), recipientPub, recipientEpoch, aad),
            Base64.NO_WRAP
        )

    /** [privKeyForEpoch] devuelve la clave privada X25519 propia de esa época, o null si ya no existe. */
    fun decrypt(cipherTextBase64: String, privKeyForEpoch: (Int) -> ByteArray?, aad: ByteArray): String =
        String(open(Base64.decode(cipherTextBase64, Base64.NO_WRAP), privKeyForEpoch, aad), Charsets.UTF_8)

    internal fun seal(plain: ByteArray, recipientPub: ByteArray, recipientEpoch: Int, aad: ByteArray): ByteArray {
        require(recipientPub.size == X25519.POINT_SIZE) { "Clave de cifrado del destinatario inválida" }
        val (ephPriv, ephPub) = generarParX25519()

        val shared = ecdh(ephPriv, recipientPub)
        val key    = hkdfSha256(shared, salt = ephPub + recipientPub)
        val iv     = ByteArray(IV_LENGTH_BYTE).also { random.nextBytes(it) }
        val header = byteArrayOf(VERSION) + epochBytes(recipientEpoch) + ephPub

        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            cipher.updateAAD(header + aad)
            return header + iv + cipher.doFinal(plain)
        } finally {
            ephPriv.fill(0); shared.fill(0); key.fill(0)
        }
    }

    internal fun open(data: ByteArray, privKeyForEpoch: (Int) -> ByteArray?, aad: ByteArray): ByteArray {
        val headerLen = 1 + EPOCH_LEN + X25519.POINT_SIZE
        require(data.size > headerLen + IV_LENGTH_BYTE + TAG_LENGTH_BIT / 8) { "Sobre truncado" }
        require(data[0] == VERSION) { "Versión de cifrado no soportada: ${data[0]}" }

        val epoch  = ByteBuffer.wrap(data, 1, EPOCH_LEN).int
        val header = data.copyOfRange(0, headerLen)
        val ephPub = data.copyOfRange(1 + EPOCH_LEN, headerLen)
        val iv     = data.copyOfRange(headerLen, headerLen + IV_LENGTH_BYTE)
        val body   = data.copyOfRange(headerLen + IV_LENGTH_BYTE, data.size)

        // Si la época ya se purgó, la clave está revocada y el mensaje no se puede abrir
        val ownPriv = checkNotNull(privKeyForEpoch(epoch)) { "Clave de cifrado de la época $epoch no disponible" }
        val ownPub  = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(ownPriv, 0, it, 0) }
        val shared  = ecdh(ownPriv, ephPub)
        val key     = hkdfSha256(shared, salt = ephPub + ownPub)

        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            cipher.updateAAD(header + aad)
            // Si cualquier byte (o el AAD) ha sido alterado → AEADBadTagException
            return cipher.doFinal(body)
        } finally {
            ownPriv.fill(0); shared.fill(0); key.fill(0)
        }
    }

    // ── Firma Ed25519 (autenticación del emisor) ─────────────────────

    fun sign(privKey: PrivKey, data: ByteArray): String =
        Base64.encodeToString(privKey.sign(data), Base64.NO_WRAP)

    /**
     * Verifica la firma con la clave pública EMBEBIDA en [senderPeerId] y comprueba
     * que esa clave corresponde realmente al PeerId (PeerId.fromPubKey == senderPeerId).
     * Nunca lanza: cualquier anomalía (PeerId malformado, Base64 inválido...) → false.
     */
    fun verifySignature(senderPeerId: String, data: ByteArray, signatureB64: String): Boolean = try {
        val pub = ed25519PubKeyFromPeerId(senderPeerId)
        PeerId.fromPubKey(pub).toBase58() == senderPeerId &&
            pub.verify(data, Base64.decode(signatureB64, Base64.NO_WRAP))
    } catch (e: Exception) {
        false
    }

    /** PeerId Ed25519 = multihash identity: [0x00][len][PublicKey protobuf]. */
    internal fun ed25519PubKeyFromPeerId(peerId: String): Ed25519PublicKey {
        val mh = PeerId.fromBase58(peerId).bytes
        require(mh.size > 2 && mh[0] == 0x00.toByte()) { "El PeerId no contiene una clave Ed25519 embebida" }
        val len = mh[1].toInt() and 0xFF
        val pub = unmarshalPublicKey(mh.copyOfRange(2, 2 + len))
        require(pub is Ed25519PublicKey) { "La clave del PeerId no es Ed25519" }
        return pub
    }

    // ── Primitivas internas ──────────────────────────────────────────

    private fun epochBytes(epoch: Int): ByteArray = ByteBuffer.allocate(EPOCH_LEN).putInt(epoch).array()

    private fun ecdh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(X25519.POINT_SIZE)
        // false ⇒ resultado todo ceros (punto de orden bajo) → rechazar
        check(X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) { "Clave pública X25519 inválida" }
        return out
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, HKDF_INFO))
        return ByteArray(KEY_LEN).also { gen.generateBytes(it, 0, it.size) }
    }
}
