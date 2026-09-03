package com.alberto.medp2p_poc.data.model

// ══════════════════════════════════════════════════════════════════════
// VERSIÓN 4 — Cambios en AuthProfile:
//   - photoUri: ruta al archivo local de foto de perfil (vacío si no hay)
//   - lastLoginAt: timestamp del último login exitoso. Se escribe en cada
//     login y se muestra en la UI independientemente del estado P2P.
//     Resuelve el "Estado de Sincronización Fantasma": el usuario ve
//     feedback inmediato al iniciar sesión sin esperar al relay.
//
// Cambios en UserSession:
//   - photoUri: propagado desde AuthProfile para que la UI (TopBar)
//     pueda mostrar la foto reactivamente sin consultar la DB.
// ══════════════════════════════════════════════════════════════════════

data class AuthProfile(
    val peerId: String,
    val displayName: String,
    val role: UserRole,
    val passwordHash: ByteArray,
    val passwordSalt: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    val photoUri: String = "",
    val lastLoginAt: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthProfile) return false
        return peerId == other.peerId
    }
    override fun hashCode(): Int = peerId.hashCode()
}

enum class UserRole(val displayLabel: String) {
    PROFESSIONAL("Cuidador"),
    PATIENT("Paciente");

    companion object {
        fun fromOrdinal(ordinal: Int): UserRole =
            entries.getOrElse(ordinal) { PATIENT }
    }
}

data class UserSession(
    val peerId: String,
    val displayName: String,
    val role: UserRole,
    val photoUri: String = "",
    val lastLoginAt: Long = System.currentTimeMillis(),
    val authenticatedAt: Long = System.currentTimeMillis()
)