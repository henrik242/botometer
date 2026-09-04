package no.synth.botometer

import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.FineTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testene fungerer som dokumentasjon på hva satstabellen skal gi. Når satsene oppdateres skal
 * disse forventningene endres bevisst - ikke bare tilpasses til det koden tilfeldigvis gjør.
 */
class FineCalculatorTest {

    private val calc = FineCalculator(
        FineTable.fromJson(File("src/main/assets/botesatser.json").readText())
    )

    @Test
    fun `60 i 50-sone gir 3350 kroner`() {
        // Målt 63, fradrag 3 -> grunnlag 60 -> 10 km/t over.
        val r = calc.estimate(speedKmt = 63.0, limitKmt = 50) as FineEstimate.SimplifiedFine
        assertEquals(3350, r.amountNok)
        assertEquals(0, r.points)
    }

    @Test
    fun `100 i 80-sone gir 7450 kroner og to prikker`() {
        // Målt 100 (fast fradrag gjelder t.o.m. 100) -> grunnlag 97 -> 17 km/t over.
        val r = calc.estimate(speedKmt = 100.0, limitKmt = 80) as FineEstimate.SimplifiedFine
        assertEquals(7450, r.amountNok)
        assertEquals(2, r.points)
        assertEquals(17, r.overKmt)
    }

    @Test
    fun `26 over i 50-sone gaar til anmeldelse`() {
        val r = calc.estimate(speedKmt = 79.0, limitKmt = 50)
        assertTrue(r is FineEstimate.Prosecution)
    }

    @Test
    fun `sikkerhetsfradraget bruker prosent over 100 km per time`() {
        // Målt 120 -> 3 % fradrag -> 116,4 -> grunnlag 116 -> 6 km/t over i 110-sone.
        val r = calc.estimate(speedKmt = 120.0, limitKmt = 110) as FineEstimate.SimplifiedFine
        assertEquals(3350, r.amountNok)
    }

    @Test
    fun `36-40 over i 90-sone markeres som usikker naar motorveg er ukjent`() {
        val r = calc.estimate(speedKmt = 131.0, limitKmt = 90, motorway = null) as FineEstimate.SimplifiedFine
        assertEquals(16700, r.amountNok)
        assertTrue(r.uncertain)
    }

    @Test
    fun `36-40 over paa vanlig veg med 90-grense er anmeldelse`() {
        val r = calc.estimate(speedKmt = 131.0, limitKmt = 90, motorway = false)
        assertTrue(r is FineEstimate.Prosecution)
    }

    @Test
    fun `ukjent fartsgrense gir ikke gjetting`() {
        assertTrue(calc.estimate(90.0, null) is FineEstimate.UnknownLimit)
    }
}
