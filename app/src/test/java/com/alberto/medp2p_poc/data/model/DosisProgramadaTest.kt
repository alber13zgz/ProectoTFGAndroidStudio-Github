package com.alberto.medp2p_poc.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DosisProgramadaTest {

    private val h = 3_600_000L
    private val inicio = 1_000 * h
    private val pauta = PautaMedicaV2(id = "p1", patientPeerId = "pac", doctorCreatorPeerId = "c1",
        medicacion = "Ibuprofeno", dosis = "400 mg", intervaloHoras = 8,
        fechaInicio = inicio, fechaFin = inicio + 72 * h)

    // Dos cuidadores que registran en momentos distintos de la misma franja obtienen el mismo identificador
    @Test
    fun mismaFranja_mismaDosis() {
        assertEquals(inicio + 8 * h, DosisProgramada.actual(pauta, inicio + 8 * h + 10 * 60_000))
        assertEquals(inicio + 8 * h, DosisProgramada.actual(pauta, inicio + 14 * h + 59 * 60_000))
    }

    // Se admite registrar hasta una hora antes de la hora prevista
    @Test
    fun tomaAdelantada_cuentaParaLaSiguienteDosis() {
        assertEquals(inicio + 8 * h, DosisProgramada.actual(pauta, inicio + 7 * h + 30 * 60_000))
        assertEquals(inicio, DosisProgramada.actual(pauta, inicio + 6 * h))
    }

    @Test
    fun fueraDeLaPauta_noHayDosis() {
        assertNull(DosisProgramada.actual(pauta, inicio - 2 * h))
        assertNull(DosisProgramada.actual(pauta, inicio + 73 * h))
    }

    // Antes, una pauta "cada 8 h" quedaba como hecha todo el día tras la primera toma
    @Test
    fun cadaDosisDelDiaEsDistinta() {
        val dosis = listOf(1, 9, 17).map { DosisProgramada.actual(pauta, inicio + it * h) }
        assertEquals(listOf(inicio, inicio + 8 * h, inicio + 16 * h), dosis)
    }
}
