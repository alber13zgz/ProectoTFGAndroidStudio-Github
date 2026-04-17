package com.alberto.medp2p_poc.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MedicalRecord(
    val id: String = UUID.randomUUID().toString(),
    val patientId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean,
    val senderAlias: String = "",
    val userName: String = ""  // ← valor por defecto, no rompe nada
)