package com.alberto.medp2p_poc.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PautaMedicaV2(
    val id: String = UUID.randomUUID().toString(),
    val patientPeerId: String,
    val doctorCreatorPeerId: String,
    val medicacion: String,
    val dosis: String,
    val intervaloHoras: Int,
    val fechaInicio: Long,
    val fechaFin: Long
)

@Serializable
data class RegistroSuministro(
    val id: String = UUID.randomUUID().toString(),
    val pautaId: String,
    val patientPeerId: String,
    val doctorAdministeredPeerId: String,
    val timestampSuministro: Long = System.currentTimeMillis(),
    val temperatura: Float? = null,
    val sintomas: String? = null,
    val notas: String? = null
)