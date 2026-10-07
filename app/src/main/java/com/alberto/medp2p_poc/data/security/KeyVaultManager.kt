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
import com.alberto.medp2p_poc.data.p2p.CryptoUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

        // Clave de SQLCipher: aleatoria por instalación, nunca en el código fuente
        private const val KEY_DB_PASSPHRASE = "sqlcipher_db_passphrase_hex"
        private const val DB_KEY_LENGTH = 32
        // Cerrojo compartido: varias instancias (una por ViewModel) podrían
        // pedir la clave a la vez en el primer arranque y generar dos distintas
        private val DB_KEY_LOCK = Any()

        // Claves de cifrado X25519 propias (una por época), separadas de la identidad
        private const val KEY_ENC_KEYS = "x25519_encryption_keys_json"
        private val ENC_KEY_LOCK = Any()
        private val json = Json { ignoreUnknownKeys = true }

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

    /** Borra la identidad libp2p y sus claves de cifrado (reset de perfil). Conserva la clave de la BD. */
    fun clearVault() {
        encryptedPrefs.edit()
            .remove(KEY_PRIVATE_B64)
            .remove(KEY_PUBLIC_B64)
            .remove(KEY_PEER_ID)
            .remove(KEY_ENC_KEYS)
            .apply()
        Log.w(TAG, "Identidad borrada de la boveda (clave de BD conservada).")
    }

    // ══════════════════════════════════════════════════════════════
    // ══ CLAVES DE CIFRADO X25519 (rotables por época) ═══════════
    // ══════════════════════════════════════════════════════════════

    @Serializable
    private data class StoredEncKey(val epoch: Int, val priv: String, val pub: String, val createdAt: Long)

    /** Clave de cifrado pública propia de una época (la privada nunca sale de la bóveda). */
    data class EncKey(val epoch: Int, val publicKey: String, val createdAt: Long)

    private fun leerClavesCifrado(): List<StoredEncKey> =
        encryptedPrefs.getString(KEY_ENC_KEYS, null)
            ?.let { json.decodeFromString<List<StoredEncKey>>(it) } ?: emptyList()

    private fun guardarClavesCifrado(claves: List<StoredEncKey>) {
        check(encryptedPrefs.edit().putString(KEY_ENC_KEYS, json.encodeToString(claves)).commit()) {
            "No se pudieron persistir las claves de cifrado"
        }
    }

    private fun nuevaClave(epoch: Int): StoredEncKey {
        val (priv, pub) = CryptoUtils.generarParX25519()
        val stored = StoredEncKey(epoch, CryptoUtils.toB64Url(priv), CryptoUtils.toB64Url(pub), System.currentTimeMillis())
        priv.fill(0)
        return stored
    }

    private fun StoredEncKey.publica() = EncKey(epoch, pub, createdAt)

    /** Clave de cifrado vigente (la de mayor época); la crea si no existe. DEBE llamarse en Dispatchers.IO. */
    fun getOrCreateEncryptionKey(): EncKey = synchronized(ENC_KEY_LOCK) {
        val claves = leerClavesCifrado()
        claves.maxByOrNull { it.epoch }?.let { return it.publica() }
        val primera = nuevaClave(epoch = 1)
        guardarClavesCifrado(listOf(primera))
        Log.d(TAG, "Clave de cifrado X25519 creada (época 1).")
        primera.publica()
    }

    /** Renueva la clave de cifrado: nueva época. Las anteriores se conservan hasta purgarlas. */
    fun rotateEncryptionKey(): EncKey = synchronized(ENC_KEY_LOCK) {
        val claves = leerClavesCifrado()
        val nueva = nuevaClave(epoch = (claves.maxOfOrNull { it.epoch } ?: 0) + 1)
        guardarClavesCifrado(claves + nueva)
        Log.i(TAG, "Clave de cifrado renovada → época ${nueva.epoch}.")
        nueva.publica()
    }

    /** Clave privada X25519 de una época, o null si no existe o ya se purgó (revocada). */
    fun getEncryptionPrivateKey(epoch: Int): ByteArray? = synchronized(ENC_KEY_LOCK) {
        leerClavesCifrado().firstOrNull { it.epoch == epoch }?.let { CryptoUtils.fromB64Url(it.priv) }
    }

    /** Borra las claves privadas de épocas anteriores a [epoch]. La vigente nunca se borra. */
    fun purgeEncryptionKeysBefore(epoch: Int) = synchronized(ENC_KEY_LOCK) {
        val claves = leerClavesCifrado()
        val vigente = claves.maxOfOrNull { it.epoch } ?: return@synchronized
        val conservar = claves.filter { it.epoch >= minOf(epoch, vigente) }
        if (conservar.size != claves.size) {
            guardarClavesCifrado(conservar)
            Log.i(TAG, "Claves de cifrado anteriores a la época $epoch eliminadas (${claves.size - conservar.size}).")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ CLAVE DE LA BASE DE DATOS (SQLCipher) ═══════════════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Devuelve la passphrase de SQLCipher. Si no existe, genera 32 bytes con
     * SecureRandom y los guarda en la boveda cifrada (MasterKey del Keystore).
     *
     * DEBE llamarse en Dispatchers.IO.
     */
    fun getOrCreateDatabasePassphrase(): String = synchronized(DB_KEY_LOCK) {
        encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)?.let { return it }

        val raw = ByteArray(DB_KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }
        raw.fill(0)

        // commit() sincrono: la clave tiene que estar persistida ANTES de
        // cifrar la BD con ella; si se perdiera, la BD quedaria ilegible
        check(encryptedPrefs.edit().putString(KEY_DB_PASSPHRASE, hex).commit()) {
            "No se pudo persistir la clave de la base de datos"
        }
        Log.d(TAG, "Clave de SQLCipher generada y almacenada en boveda.")
        hex
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