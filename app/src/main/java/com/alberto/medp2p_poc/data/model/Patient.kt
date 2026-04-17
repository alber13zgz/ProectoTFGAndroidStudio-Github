package com.alberto.medp2p_poc.data.model

import java.util.UUID

// ──────────────────────────────────────────────────────────────────────
// JUSTIFICACIÓN ARQUITECTÓNICA:
// El modelo Patient se enriquece con campos clínicos reales (alergias,
// notas, fecha de vinculación) manteniendo el peerId como campo interno
// invisible para la UI. El campo avatarColor permite generar un avatar
// determinista por paciente sin almacenar imágenes pesadas en SQLite.
// ──────────────────────────────────────────────────────────────────────

data class Patient(
    val id: String = UUID.randomUUID().toString(),
    val fullName: String,
    val peerId: String,
    val allergies: String = "",
    val notes: String = "",
    val linkedAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
    val isFavorite: Boolean = false,
    val avatarColorIndex: Int = 0  // Índice 0-7 para la paleta de avatares
) {
    /** Iniciales para el avatar circular (máx. 2 caracteres). */
    val initials: String
        get() = fullName
            .split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifEmpty { "?" }
}