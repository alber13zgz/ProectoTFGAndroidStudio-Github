package com.alberto.medp2p_poc.data.security

import com.alberto.medp2p_poc.data.security.PoliticaRetencion.Epoca
import org.junit.Assert.assertEquals
import org.junit.Test

class PoliticaRetencionTest {

    private val dia = 24L * 3_600_000
    private val claves = listOf(Epoca(1, 0L), Epoca(2, 10 * dia))   // época 2 creada el día 10

    // Todos han confirmado la nueva, pero aún no ha pasado el margen de 7 días
    @Test
    fun confirmadaPeroDentroDelMargen_seConserva() {
        assertEquals(emptySet<Int>(), PoliticaRetencion.epocasABorrar(claves, minConfirmado = 2, ahora = 15 * dia))
    }

    @Test
    fun confirmadaYPasadoElMargen_seBorra() {
        assertEquals(setOf(1), PoliticaRetencion.epocasABorrar(claves, minConfirmado = 2, ahora = 17 * dia + 1))
    }

    // Un dispositivo desconectado no ha confirmado: se conserva hasta el máximo de 30 días
    @Test
    fun sinConfirmar_seConservaHastaElMaximo() {
        assertEquals(emptySet<Int>(), PoliticaRetencion.epocasABorrar(claves, minConfirmado = 1, ahora = 39 * dia))
        assertEquals(setOf(1), PoliticaRetencion.epocasABorrar(claves, minConfirmado = 1, ahora = 40 * dia))
    }

    @Test
    fun laClaveVigenteNuncaSeBorra() {
        assertEquals(emptySet<Int>(), PoliticaRetencion.epocasABorrar(listOf(Epoca(3, 0L)), null, ahora = 1000 * dia))
    }
}
