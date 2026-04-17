package com.alberto.medp2p_poc.data.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPrivateKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// Esta clase es el unico punto de acceso a las claves criptograficas
// de libp2p. Usa EncryptedSharedPreferences (respaldado por Android
// Keystore / AES-256-GCM) como capa de cifrado en reposo. La
// contrasena del usuario NO se almacena aqui: solo se usa para
// derivar un hash PBKDF2 que se verifica contra lo guardado en SQLite.
// ──────────────────────────────────────────────────────────────────────

class KeyVaultManager(private val context: Context) {

    companion object {
        private const val TAG = "P2P_SECURITY"
        private const val VAULT_FILE = "medp2p_key_vault"
        private const val KEY_PRIVATE_B64 = "libp2p_private_key_b64"
        private const val KEY_PUBLIC_B64 = "libp2p_public_key_b64"
        private const val KEY_PEER_ID = "libp2p_peer_id"

        // PBKDF2 params — OWASP recomendado para moviles
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
    }

    /** Acceso al almacen cifrado (lazy para no bloquear el constructor). */
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            VAULT_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ══ GENERACION DE IDENTIDAD ═════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Genera un par de claves Ed25519 nuevo, lo almacena cifrado
     * en EncryptedSharedPreferences y devuelve el PeerId resultante.
     *
     * DEBE llamarse en Dispatchers.IO. Nunca en Main Thread.
     */
    fun generateAndStoreIdentity(): GeneratedIdentity {
        Log.d(TAG, "Generando par de claves Ed25519...")

        // 1. Generar par de claves con libp2p
        //    generateKeyPair() es una funcion top-level en io.libp2p.core.crypto
        //    KeyType es un enum (no KEY_TYPE)
        val (privKey, pubKey) = generateKeyPair(KeyType.ED25519)
        val peerId = PeerId.fromPubKey(pubKey)

        // 2. Serializar a Base64 para almacenamiento
        //    .bytes() devuelve la serializacion protobuf completa
        val privB64 = Base64.encodeToString(privKey.bytes(), Base64.NO_WRAP)
        val pubB64 = Base64.encodeToString(pubKey.bytes(), Base64.NO_WRAP)

        // 3. Guardar en la boveda cifrada
        encryptedPrefs.edit()
            .putString(KEY_PRIVATE_B64, privB64)
            .putString(KEY_PUBLIC_B64, pubB64)
            .putString(KEY_PEER_ID, peerId.toString())
            .apply()

        Log.d(TAG, "Identidad almacenada en boveda. PeerId=$peerId")

        return GeneratedIdentity(
            peerId = peerId.toString(),
            privateKey = privKey,
            publicKey = pubKey
        )
    }

    /**
     * Recupera la clave privada de la boveda cifrada.
     * Devuelve null si no existe (primer uso / datos borrados).
     *
     * DEBE llamarse en Dispatchers.IO.
     */
    fun retrievePrivateKey(): PrivKey? {
        val privB64 = encryptedPrefs.getString(KEY_PRIVATE_B64, null) ?: return null
        return try {
            val bytes = Base64.decode(privB64, Base64.NO_WRAP)
            // unmarshalPrivateKey() es funcion top-level en io.libp2p.core.crypto
            unmarshalPrivateKey(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error recuperando clave privada: ${e.message}")
            null
        }
    }

    /** Recupera el PeerId almacenado (sin necesidad de recalcular). */
    fun retrieveStoredPeerId(): String? =
        encryptedPrefs.getString(KEY_PEER_ID, null)

    /** Comprueba si ya existe una identidad generada previamente. */
    fun hasStoredIdentity(): Boolean =
        encryptedPrefs.contains(KEY_PRIVATE_B64)

    /** Borra toda la boveda (para debug / reset de la app). */
    fun clearVault() {
        encryptedPrefs.edit().clear().apply()
        Log.w(TAG, "Boveda de claves borrada.")
    }

    // ══════════════════════════════════════════════════════════════
    // ══ HASHING DE CONTRASENA (PBKDF2) ══════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_LENGTH
        )
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun verifyPassword(
        inputPassword: String,
        storedHash: ByteArray,
        storedSalt: ByteArray
    ): Boolean {
        val inputHash = hashPassword(inputPassword, storedSalt)
        return inputHash.contentEquals(storedHash)
    }

    data class GeneratedIdentity(
        val peerId: String,
        val privateKey: PrivKey,
        val publicKey: PubKey
    )
}