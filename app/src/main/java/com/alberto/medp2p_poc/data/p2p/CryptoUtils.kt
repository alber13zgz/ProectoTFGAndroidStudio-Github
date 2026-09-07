package com.alberto.medp2p_poc.data.p2p

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ══════════════════════════════════════════════════════════════════════
// CryptoUtils — AES-256-GCM con derivación segura (KDF)
// ══════════════════════════════════════════════════════════════════════
object CryptoUtils {

    private const val ALGORITHM     = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    // Secreto de cliente: El nodo AWS no conoce esta cadena, por lo que
    // es matemáticamente incapaz de derivar la clave solo con el PeerId.
    private const val APP_MASTER_SECRET = "MedP2P_TFG_SecureKey_2026_!@#"

    // ── Derivar clave AES-256 combinando PeerId y el Secreto Local ─────────
    private fun deriveKey(peerId: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        // Mezclamos el PeerId público con el secreto privado de la app
        val combinedInput = peerId + APP_MASTER_SECRET
        val keyBytes = digest.digest(combinedInput.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String, recipientPeerId: String): String {
        val secretKey = deriveKey(recipientPeerId)
        val cipher    = Cipher.getInstance(ALGORITHM)
        val iv        = ByteArray(IV_LENGTH_BYTE).also { SecureRandom().nextBytes(it) }

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    fun decrypt(cipherTextBase64: String, senderPeerId: String): String {
        val secretKey     = deriveKey(senderPeerId)
        val cipherMessage = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        val cipher        = Cipher.getInstance(ALGORITHM)

        val iv         = cipherMessage.copyOfRange(0, IV_LENGTH_BYTE)
        val cipherText = cipherMessage.copyOfRange(IV_LENGTH_BYTE, cipherMessage.size)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}