package com.alberto.medp2p_poc.data.security

// ══════════════════════════════════════════════════════════════════════
// Cuánto tiempo se conservan las claves privadas de cifrado anteriores.
//
// Tras renovar la clave (época N+1), la de la época N se sigue necesitando
// para descifrar mensajes que los demás cifraron antes de conocer la nueva.
// Se conserva hasta que:
//   - todos los dispositivos vinculados han confirmado la clave nueva y han
//     pasado GRACIA_MS desde la renovación (margen para mensajes en vuelo), o
//   - han pasado MAX_RETENCION_MS desde la renovación, aunque falte alguna
//     confirmación (coincide con la validez máxima de un mensaje).
// Si después llega un mensaje cifrado con una clave ya borrada, el receptor
// responde STALE_KEY y el emisor lo reenvía cifrado con la clave nueva.
// La clave vigente nunca se borra.
// ══════════════════════════════════════════════════════════════════════
object PoliticaRetencion {

    const val GRACIA_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_RETENCION_MS = 30L * 24 * 60 * 60 * 1000

    data class Epoca(val epoch: Int, val createdAt: Long)

    /**
     * Épocas que se pueden borrar. [minConfirmado] es la menor época propia que
     * algún par vinculado conoce (null si no hay pares).
     */
    fun epocasABorrar(claves: List<Epoca>, minConfirmado: Int?, ahora: Long): Set<Int> {
        val ordenadas = claves.sortedBy { it.epoch }
        val vigente = ordenadas.lastOrNull()?.epoch ?: return emptySet()
        val confirmadoPorTodos = minConfirmado ?: vigente
        return ordenadas.zipWithNext().mapNotNull { (clave, siguiente) ->
            val desdeRenovacion = ahora - siguiente.createdAt
            val borrar = desdeRenovacion >= MAX_RETENCION_MS ||
                (clave.epoch < confirmadoPorTodos && desdeRenovacion >= GRACIA_MS)
            if (borrar && clave.epoch != vigente) clave.epoch else null
        }.toSet()
    }
}
