package com.alberto.medp2p_poc.data.model

// ══════════════════════════════════════════════════════════════════════
// Historial de tomas (RF-08): una fila por dosis administrada, con quién
// la administró y si coincide sospechosamente con otra de la misma pauta.
// ══════════════════════════════════════════════════════════════════════
data class TomaHistorial(
    val id: String,
    val pautaId: String,
    val medicacion: String,
    val dosis: String,
    val intervaloHoras: Int,
    val timestamp: Long,
    val administradoPorPeerId: String,
    val administradoPorNombre: String,   // nombre declarado por el autor (dentro del registro firmado)
    val temperatura: Float?,
    val sintomas: String?,
    val notas: String?,
    val firmada: Boolean,                // lleva la firma Ed25519 de quien la administró
    val posibleDuplicado: Boolean = false,
    val dosisProgramada: Long = 0L       // hora prevista de la dosis (0 = registro antiguo sin ella)
)

/**
 * Detección de conflictos del modelo append-only: si dos cuidadores
 * registran la misma dosis estando desconectados, al sincronizar se
 * conservan AMBOS registros (no se borra ninguno) y aquí se señalan.
 *
 * Regla principal (exacta): dos tomas con el mismo identificador de dosis
 * programada (pautaId + dosisProgramada) son la misma dosis dada dos veces.
 * Para registros antiguos sin dosisProgramada se usa la regla aproximada:
 * dos tomas de la misma pauta separadas menos de la MITAD de su intervalo.
 */
object DetectorDuplicados {

    fun marcar(tomas: List<TomaHistorial>): List<TomaHistorial> {
        val duplicadas = mutableSetOf<String>()
        tomas.filter { it.dosisProgramada > 0 }
            .groupBy { it.pautaId to it.dosisProgramada }.values
            .filter { it.size > 1 }
            .forEach { misma -> misma.forEach { duplicadas += it.id } }
        tomas.filter { it.dosisProgramada == 0L }.groupBy { it.pautaId }.values.forEach { dePauta ->
            val ordenadas = dePauta.sortedBy { it.timestamp }
            ordenadas.zipWithNext { a, b ->
                val umbralMs = a.intervaloHoras.coerceAtLeast(1) * 3_600_000L / 2
                if (b.timestamp - a.timestamp < umbralMs) {
                    duplicadas += a.id
                    duplicadas += b.id
                }
            }
        }
        return tomas.map { it.copy(posibleDuplicado = it.id in duplicadas) }
    }
}
