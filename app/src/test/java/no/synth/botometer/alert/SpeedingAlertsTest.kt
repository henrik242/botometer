package no.synth.botometer.alert

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome
import no.synth.botometer.limit.LatLon
import no.synth.botometer.limit.LimitMatch
import no.synth.botometer.speed.SpeedFix
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Varselet skal komme ved *overgang* til et nytt bøtenivå, ikke ved hver GPS-oppdatering.
 *
 * Et varsel per sekund er ikke et varsel, det er støy - og Google er tydelig på at heads-up bare
 * skal brukes til noe som er «drive-critical, time sensitive, and actionable». At du fortsatt
 * ligger 17 km/t over er ingen av delene; at beløpet nettopp gikk fra 4 800 til 7 450 er det.
 */
@RunWith(RobolectricTestRunner::class)
class SpeedingAlertsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val notifications
        get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private var clock = 0L
    private fun alerts() = SpeedingAlerts(context) { clock }

    private fun reading(estimate: FineEstimate) = SpeedWatch.Reading(
        fix = SpeedFix(
            speedKmt = 97.0,
            position = LatLon(59.74, 11.80),
            headingDeg = 90.0,
            accuracyMeters = 5f,
            source = SpeedFix.Source.GPS,
        ),
        match = LimitMatch(limitKmt = 80, roadRef = "EV6", distanceMeters = 4.0),
        estimate = estimate,
    )

    private fun fine(amount: Int, band: String) = FineEstimate.SimplifiedFine(
        amountNok = amount,
        points = 2,
        licence = LicenceOutcome.BEHOLDER,
        overKmt = 17,
        band = band,
    )

    @Test
    fun `lovlig fart varsler ikke`() {
        alerts().onReading(reading(FineEstimate.NoOffence))
        assertEquals(0, notifications.size)
    }

    @Test
    fun `ukjent fartsgrense varsler ikke`() {
        // Vi nekter å gjette på beløpet ellers; da skal vi ikke mase om det heller.
        alerts().onReading(reading(FineEstimate.UnknownLimit))
        assertEquals(0, notifications.size)
    }

    @Test
    fun `foerste overtredelse varsler`() {
        alerts().onReading(reading(fine(7450, "16-20")))
        assertEquals(1, notifications.size)
    }

    @Test
    fun `samme nivaa varsler bare en gang`() {
        val a = alerts()
        repeat(10) {
            clock += 1_000
            a.onReading(reading(fine(7450, "16-20")))
        }
        assertEquals("ett varsel per nivå, ikke per GPS-fix", 1, notifications.size)
    }

    @Test
    fun `nytt nivaa varsler paa nytt`() {
        val a = alerts()
        a.onReading(reading(fine(7450, "16-20")))
        clock += 60_000
        a.onReading(reading(fine(10_650, "21-25")))
        assertEquals(2, notifications.size)
    }

    @Test
    fun `et nivaaskifte for tett paa forrige varsel undertrykkes`() {
        // Uten hysterese vipper trinnet fram og tilbake på en grense. Da er ti varsler verre
        // enn ingen.
        val a = alerts()
        a.onReading(reading(fine(7450, "16-20")))
        clock += 1_000
        a.onReading(reading(fine(10_650, "21-25")))
        assertEquals(1, notifications.size)
    }

    @Test
    fun `varselet trekkes tilbake naar farten er lovlig igjen`() {
        val a = alerts()
        a.onReading(reading(fine(7450, "16-20")))
        assertEquals(1, notifications.size)

        clock += 60_000
        a.onReading(reading(FineEstimate.NoOffence))
        assertEquals("et varsel som ikke lenger gjelder skal ikke bli stående", 0, notifications.size)
    }
}
