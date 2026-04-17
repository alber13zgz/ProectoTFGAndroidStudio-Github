package com.alberto.medp2p_poc.data.model

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// Separamos las entidades de autenticación del resto del dominio clínico.
// AuthProfile es lo que se persiste en SQLite (hash + salt, NUNCA la
// contraseña en claro). UserSession es el objeto en memoria que vive
// mientras la app esté abierta tras un login exitoso — contiene el
// peerId ya descifrado y el rol, listos para que el resto de la app
// los consuma sin saber nada de criptografía.
// ──────────────────────────────────────────────────────────────────────

/**
 * Perfil de autenticación persistido en SQLite.
 * La contraseña NUNCA se almacena en claro: solo el hash PBKDF2 y su salt.
 */
data class AuthProfile(
    val peerId: String,
    val displayName: String,
    val role: UserRole,
    val passwordHash: ByteArray,
    val passwordSalt: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    // ByteArray no genera equals/hashCode por defecto; lo sobreescribimos
    // para que los tests y comparaciones funcionen correctamente.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthProfile) return false
        return peerId == other.peerId
    }

    override fun hashCode(): Int = peerId.hashCode()
}

/**
 * Roles de usuario en la aplicación clínica.
 * Se almacena como INT en SQLite (ordinal) para eficiencia.
 */
enum class UserRole(val displayLabel: String) {
    PROFESSIONAL("Profesional sanitario"),
    PATIENT("Paciente");

    companion object {
        fun fromOrdinal(ordinal: Int): UserRole =
            entries.getOrElse(ordinal) { PATIENT }
    }
}

/**
 * Sesión activa en memoria. Se crea tras un login exitoso.
 * Es el "pasaporte" que el resto de ViewModels consultan para
 * saber quién es el usuario actual sin tocar la DB ni la bóveda.
 */
data class UserSession(
    val peerId: String,
    val displayName: String,
    val role: UserRole,
    val authenticatedAt: Long = System.currentTimeMillis()
)