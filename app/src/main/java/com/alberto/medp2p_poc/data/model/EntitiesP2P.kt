package com.alberto.medp2p_poc.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

// Usamos @Serializable para poder convertir luego estos objetos a JSON y pasarlos por Netty en el P2P
@Serializable
data class Usuario(
    val peerId: String,
    val nombre: String,
    val fechaRegistro: Long
)

@Serializable
data class Paciente(
    val usuarioPeerId: String,
    val alergiasConocidas: String?,
    val medicoReferencia: String?
)

@Serializable
data class Cuidador(
    val usuarioPeerId: String,
    val telefonoContacto: String?,
    val nivelPermisos: Int
)

@Serializable
data class Medicamento(
    val idMedicamento: String = UUID.randomUUID().toString(), // UUID OBLIGATORIO PARA OFFLINE-FIRST
    val nombreComercial: String,
    val principleActivo: String,
    val concentracionMg: Float,
    val stockActual: Int
)

@Serializable
data class PautaMedica(
    val idPauta: String = UUID.randomUUID().toString(),
    val pacienteId: String,
    val medicamentoId: String,
    val intervaloHoras: Int
)

@Serializable
data class TomaDiaria(
    val idToma: String = UUID.randomUUID().toString(),
    val pautaId: String,
    val horaProgramada: Long,
    val horaRealConsumo: Long?,
    val estado: Int,
    val confirmadoPorPeerId: String? // Traceability total para el TFG
)

@Serializable
data class SyncLog(
    val logId: Long = 0, // SQLite autoincremental
    val tablaAfectada: String,
    val registroAfectadoId: String,
    val accion: String,
    val timestampModificacion: Long
)