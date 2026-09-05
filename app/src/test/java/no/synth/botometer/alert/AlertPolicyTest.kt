package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Varselet skal komme ved *overgang* til et nytt bøtenivå, ikke ved hver GPS-oppdatering.
 *
 * Et varsel per sekund er ikke et varsel, det er støy - og Google forbeholder heads-up for noe
 * som er «drive-critical, time sensitive, and actionable». At du fortsatt ligger 17 km/t over er
 * ingen av delene; at beløpet nettopp gikk fra 4 800 til 7 450 er det.
 */
class AlertPolicyTest {

    private var clock = 0L
    private val policy = AlertPolicy { clock }

    private fun fine(amount: Int, band: String) = FineEstimate.SimplifiedFine(
        amountNok = amount,
        points = 2,
        licence = LicenceOutcome.BEHOLDER,
        overKmt = 17,
        band = band,
    )

    private fun letIntervalPass() { clock += AlertPolicy.MIN_INTERVAL_MS }

    @Test
    fun `lovlig fart varsler ikke`() {
        assertEquals(AlertPolicy.Decision.Ignore, policy.next(FineEstimate.NoOffence))
    }

    @Test
    fun `ukjent fartsgrense varsler ikke`() {
        // Vi nekter å gjette på beløpet ellers; da skal vi ikke mase om det heller.
        assertEquals(AlertPolicy.Decision.Ignore, policy.next(FineEstimate.UnknownLimit))
    }

    @Test
    fun `foerste overtredelse varsler, uten oppvarming`() {
        // Ingen ventetid først: det finnes ikke noe forrige varsel å ligge for tett på.
        assertTrue(policy.next(fine(7450, "16-20")) is AlertPolicy.Decision.Alert)
    }

    @Test
    fun `samme nivaa varsler bare en gang`() {
        assertTrue(policy.next(fine(7450, "16-20")) is AlertPolicy.Decision.Alert)

        repeat(10) {
            clock += 1_000
            assertEquals(
                "ett varsel per nivå, ikke per GPS-fix",
                AlertPolicy.Decision.Ignore,
                policy.next(fine(7450, "16-20")),
            )
        }
    }

    @Test
    fun `nytt nivaa varsler paa nytt`() {
        policy.next(fine(7450, "16-20"))
        letIntervalPass()
        assertTrue(policy.next(fine(10_650, "21-25")) is AlertPolicy.Decision.Alert)
    }

    @Test
    fun `et nivaaskifte for tett paa forrige varsel undertrykkes`() {
        policy.next(fine(7450, "16-20"))

        clock += 1_000
        assertEquals(
            "uten hysterese vipper trinnet på grensa; ti varsler er verre enn ingen",
            AlertPolicy.Decision.Ignore,
            policy.next(fine(10_650, "21-25")),
        )
    }

    @Test
    fun `varselet trekkes tilbake naar farten er lovlig igjen`() {
        policy.next(fine(7450, "16-20"))

        assertEquals(AlertPolicy.Decision.Withdraw, policy.next(FineEstimate.NoOffence))
    }

    @Test
    fun `ingenting aa trekke tilbake naar det aldri ble varslet`() {
        assertEquals(AlertPolicy.Decision.Ignore, policy.next(FineEstimate.NoOffence))
        assertEquals(AlertPolicy.Decision.Ignore, policy.next(FineEstimate.NoOffence))
    }

    @Test
    fun `anmeldelse er sitt eget nivaa`() {
        policy.next(fine(10_650, "21-25"))
        letIntervalPass()

        val d = policy.next(FineEstimate.Prosecution(6, 40, LicenceOutcome.INNDRAS))
        assertTrue("over taket er et annet nivå enn dyreste bot", d is AlertPolicy.Decision.Alert)
    }
}
