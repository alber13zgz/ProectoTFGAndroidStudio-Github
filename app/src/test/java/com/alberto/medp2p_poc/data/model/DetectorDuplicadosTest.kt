package com.alberto.medp2p_poc.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorDuplicadosTest {

    private val hora = 3_600_000L

    private fun toma(id: String, pauta: String, horas: Double, intervalo: Int = 8, autor: String = "C1") =
        TomaHistorial(id, pauta, "Ibuprofeno", "400 mg", intervalo, (horas * hora).toLong(),
            autor, autor, null, null, null, firmada = true)

    private fun duplicadas(tomas: List<TomaHistorial>) =
        DetectorDuplicados.marcar(tomas).filter { it.posibleDuplicado }.map { it.id }.toSet()

    // Escenario de la Prueba 6: dos cuidadores sin conexión dan la misma dosis
    @Test
    fun mismaDosisPorDosCuidadores_seMarcanAmbas() {
        val tomas = listOf(toma("a", "p1", 10.0, autor = "C1"), toma("b", "p1", 10.5, autor = "C2"))
        assertEquals(setOf("a", "b"), duplicadas(tomas))
    }

    @Test
    fun tomasRespetandoElIntervalo_noSonDuplicadas() {
        val tomas = listOf(toma("a", "p1", 0.0), toma("b", "p1", 8.0), toma("c", "p1", 16.0))
        assertEquals(emptySet<String>(), duplicadas(tomas))
    }

    // Adelantar una toma un par de horas no es una duplicidad
    @Test
    fun tomaAdelantada_noEsDuplicada() {
        val tomas = listOf(toma("a", "p1", 0.0), toma("b", "p1", 6.0))
        assertEquals(emptySet<String>(), duplicadas(tomas))
    }

    // Tomas cercanas de pautas distintas son medicamentos distintos
    @Test
    fun pautasDistintas_noSeComparan() {
        val tomas = listOf(toma("a", "p1", 10.0), toma("b", "p2", 10.1))
        assertEquals(emptySet<String>(), duplicadas(tomas))
    }

    // Regla exacta: misma dosis prevista = misma dosis, aunque se registre con horas de diferencia
    @Test
    fun mismaDosisProgramada_esDuplicadaAunqueEsteSeparada() {
        val dosis = 8 * hora
        val tomas = listOf(
            toma("a", "p1", 8.0).copy(dosisProgramada = dosis),
            toma("b", "p1", 12.0, autor = "C2").copy(dosisProgramada = dosis),
            toma("c", "p1", 16.0).copy(dosisProgramada = 16 * hora),
        )
        assertEquals(setOf("a", "b"), duplicadas(tomas))
    }
}
