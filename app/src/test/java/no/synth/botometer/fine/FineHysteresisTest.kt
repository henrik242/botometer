package no.synth.botometer.fine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * GPS-farten vaker et par tideler. Ligger du på nøyaktig 16 km/t over, hopper trinnet mellom
 * 15 og 16 flere ganger i sekundet - og beløpet mellom 5 950 og 8 650 kroner. Det er tallet som
 * trekker blikket på en bilskjerm.
 *
 * Regelen er usymmetrisk med vilje: opp med én gang, ned først etter at det har holdt seg. Et
 * beløp som er for lavt lyver om konsekvensen; et som er for høyt er bare gammelt.
 */
class FineHysteresisTest {

    private var clock = 0L
    private val hysteresis = FineHysteresis(holdMs = 1_500L) { clock }

    private fun fine(amount: Int, band: String) = FineEstimate.SimplifiedFine(
        amountNok = amount,
        points = 2,
        licence = LicenceOutcome.BEHOLDER,
        overKmt = 16,
        band = band,
    )

    private val lav = fine(5_950, "11-15 km/t over")
    private val hoy = fine(8_650, "16-20 km/t over")

    @Test
    fun `foerste avlesning vises som den er`() {
        assertSame(lav, hysteresis.stabilize(50, lav))
    }

    @Test
    fun `oppover slipper gjennom med en gang`() {
        hysteresis.stabilize(50, lav)
        assertSame("en dyrere bot skal aldri holdes tilbake", hoy, hysteresis.stabilize(50, hoy))
    }

    @Test
    fun `nedover holdes igjen til det har holdt seg`() {
        hysteresis.stabilize(50, hoy)

        // Ventetiden løper fra FØRSTE lave avlesning, ikke fra da det høye trinnet ble vist:
        // det er hvor lenge det har ligget lavt som avgjør, ikke hvor lenge du har kjørt.
        clock += 5_000
        val droppedAt = clock
        assertSame("nettopp falt", hoy, hysteresis.stabilize(50, lav))

        clock = droppedAt + 1_400
        assertSame("fortsatt for tidlig", hoy, hysteresis.stabilize(50, lav))

        clock = droppedAt + 1_500
        assertSame("nå har det holdt seg", lav, hysteresis.stabilize(50, lav))
    }

    @Test
    fun `vipping paa en trinngrense gir ett stabilt tall`() {
        hysteresis.stabilize(50, hoy)

        // Vaker fram og tilbake i takt med GPS-en, uten å ligge under lenge nok.
        repeat(10) {
            clock += 200
            assertSame(hoy, hysteresis.stabilize(50, lav))
            clock += 200
            assertSame(hoy, hysteresis.stabilize(50, hoy))
        }
    }

    @Test
    fun `en ny fartsgrense nullstiller alt`() {
        hysteresis.stabilize(50, hoy)

        // Du kjørte inn i en 80-sone og har ikke lenger noen bot. Å holde igjen det gamle
        // trinnet ville vist en bot for en fartsgrense du nettopp forlot.
        assertSame(
            FineEstimate.NoOffence,
            hysteresis.stabilize(80, FineEstimate.NoOffence),
        )
    }

    @Test
    fun `anmeldelse slaar ethvert beloep og vises straks`() {
        hysteresis.stabilize(90, hoy)
        val anmeldelse = FineEstimate.Prosecution(3, 41, LicenceOutcome.INNDRAS)
        assertSame(anmeldelse, hysteresis.stabilize(90, anmeldelse))
    }

    @Test
    fun `nedturen fra anmeldelse maa ogsaa holde seg`() {
        val anmeldelse = FineEstimate.Prosecution(3, 41, LicenceOutcome.INNDRAS)
        hysteresis.stabilize(90, anmeldelse)

        clock += 500
        val droppedAt = clock
        assertSame(anmeldelse, hysteresis.stabilize(90, hoy))

        clock = droppedAt + 1_500
        assertEquals(hoy, hysteresis.stabilize(90, hoy))
    }
}
