package com.alberto.medp2p_poc.data.p2p

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    // Clave AES-256 estática para la PoC (32 bytes)
    // En producción, esto se deriva dinámicamente del escaneo del QR.
    private val SECRET_KEY_BYTES = "MedP2P_TFG_SuperSecretKey_2026!!".toByteArray(Charsets.UTF_8)
    private val secretKey = SecretKeySpec(SECRET_KEY_BYTES, "AES")

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Concatenamos IV + Texto Cifrado para poder descifrarlo después
        val cipherMessage = iv + cipherText
        return Base64.encodeToString(cipherMessage, Base64.NO_WRAP)
    }

    fun decrypt(cipherTextBase64: String): String {
        val cipherMessage = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(ALGORITHM)

        val iv = cipherMessage.copyOfRange(0, IV_LENGTH_BYTE)
        val cipherText = cipherMessage.copyOfRange(IV_LENGTH_BYTE, cipherMessage.size)

        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

        val plainTextBytes = cipher.doFinal(cipherText)
        return String(plainTextBytes, Charsets.UTF_8)
    }
}