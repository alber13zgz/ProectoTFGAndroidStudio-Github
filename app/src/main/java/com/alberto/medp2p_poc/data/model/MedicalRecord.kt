package com.alberto.medp2p_poc.data.model

import kotlinx.serialization.Serializable // IMPORTANTE
import java.util.UUID

/**
 * Modelo de datos para un Registro Médico (Mensaje) en el historial.
 * Se almacena en SQLite de forma local.
 */
@Serializable // 👉 ESTA ETIQUETA ES VITAL
data class MedicalRecord(
    val id: String = UUID.randomUUID().toString(),
    val patientId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean,
    val senderAlias: String = ""
)