package com.alberto.medp2p_poc.data.p2p

import io.libp2p.core.crypto.PrivKey
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════════════
// Clave de cifrado X25519 publicada por un dispositivo, FIRMADA con su
// identidad Ed25519. Así cualquier par puede comprobar que la clave
// pertenece de verdad a ese PeerId, aunque le llegue a través del relay.
//
// La época (epoch) crece en cada renovación: un par solo acepta una clave
// de época mayor que la que ya conoce (no se puede "volver atrás" a una
// clave antigua aunque esté bien firmada).
// ══════════════════════════════════════════════════════════════════════
@Serializable
data class EncryptionKeyBundle(
    val peerId: String,     // identidad dueña de la clave
    val epoch: Int,         // versión de la clave (1, 2, 3...)
    val publicKey: String,  // X25519, Base64url
    val createdAt: Long,
    val signature: String   // Ed25519 de peerId sobre los campos anteriores
) {
    fun verificar(): Boolean =
        epoch > 0 && CryptoUtils.verifySignature(peerId, bytesFirmados(peerId, epoch, publicKey, createdAt), signature)

    companion object {
        private fun bytesFirmados(peerId: String, epoch: Int, publicKey: String, createdAt: Long) =
            CryptoUtils.lengthPrefixed("medp2p-enckey", peerId, epoch.toString(), publicKey, createdAt.toString())

        fun crear(identity: PrivKey, peerId: String, epoch: Int, publicKey: String, createdAt: Long) =
            EncryptionKeyBundle(
                peerId, epoch, publicKey, createdAt,
                CryptoUtils.sign(identity, bytesFirmados(peerId, epoch, publicKey, createdAt))
            )
    }
}
