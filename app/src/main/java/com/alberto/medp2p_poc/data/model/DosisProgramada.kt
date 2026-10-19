package com.alberto.medp2p_poc.data.model

// ══════════════════════════════════════════════════════════════════════
// Identificador común de la toma programada.
//
// Cada pauta define una serie de dosis previstas: fechaInicio + k · intervalo.
// Una toma se asocia a la hora prevista de su dosis (dosisProgramada), que
// todos los dispositivos calculan igual a partir de la pauta. Así, si dos
// cuidadores registran la misma dosis, ambas tomas comparten el par
// (pautaId, dosisProgramada) y el conflicto se detecta de forma exacta.
//
// Se admite registrar una dosis un poco antes de su hora (tolerancia: una
// hora o un cuarto del intervalo, lo que sea menor).
// ══════════════════════════════════════════════════════════════════════
object DosisProgramada {

    private const val HORA_MS = 3_600_000L
    private const val TOLERANCIA_MAX_MS = HORA_MS

    fun intervaloMs(pauta: PautaMedicaV2): Long = pauta.intervaloHoras.coerceAtLeast(1) * HORA_MS

    private fun toleranciaMs(intervaloMs: Long): Long = minOf(TOLERANCIA_MAX_MS, intervaloMs / 4)

    /**
     * Hora prevista de la dosis que corresponde al instante [t], o null si
     * en ese momento la pauta no está vigente.
     */
    fun actual(pauta: PautaMedicaV2, t: Long): Long? {
        val intervalo = intervaloMs(pauta)
        val desplazado = t - pauta.fechaInicio + toleranciaMs(intervalo)
        if (desplazado < 0 || t > pauta.fechaFin) return null
        val dosis = pauta.fechaInicio + Math.floorDiv(desplazado, intervalo) * intervalo
        return if (dosis > pauta.fechaFin) null else dosis
    }
}

/** Dosis prevista pendiente de administrar (una alerta del panel). */
data class AlertaDosis(val pauta: PautaMedicaV2, val dosisProgramada: Long)

/** Misma dosis prevista registrada más de una vez: posible sobredosificación. */
data class ConflictoDosis(
    val pautaId: String,
    val patientPeerId: String,
    val medicacion: String,
    val dosisProgramada: Long,
    val veces: Int
)

/** Primera toma ya registrada para una dosis prevista (para avisar antes de registrar otra). */
data class TomaExistente(val administradoPorPeerId: String, val administradoPorNombre: String, val timestamp: Long)
