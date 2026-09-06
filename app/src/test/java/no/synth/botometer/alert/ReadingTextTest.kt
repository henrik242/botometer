package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De tre flatene regner på det samme - [SpeedWatch] sørger for det - men de *sa* forskjellige
 * ting om det, og ingenting fanget det opp.
 *
 * Testene her låser nettopp de tingene som hadde rukket å drive fra hverandre.
 */
class ReadingTextTest {

    @Test
    fun `farten rundes, ikke kuttes`() {
        // 79,6 km/t ble «79» på telefonen (toInt) og «80» i bilen (roundToInt). Avkorting
        // underrapporterer alltid, med opptil 1 km/t, i en app om fartsovertredelser - og boten
        // ved siden av regnes på den uavkortede farten, så kortet kunne vise en fart som så
        // lovlig ut ved siden av et beløp som sa noe annet.
        assertEquals("80", ReadingText.speed(reading(79.6)))
        assertEquals("79", ReadingText.speed(reading(79.4)))
    }

    @Test
    fun `belop grupperes likt overalt`() {
        assertEquals("10 400", ReadingText.nok(10_400))
        assertEquals("950", ReadingText.nok(950))
        assertEquals("1 250", ReadingText.nok(1250))
    }

    @Test
    fun `tap av forerrett staar i konsekvensene`() {
        // Telefonen droppet dette helt - på flaten som nå er hovedspeedometeret. 24 km/t over i
        // en 50-sone sto som «10 400 kr · 24 km/t over · 6 prikker» og fortiet det ene som
        // faktisk endrer kvelden din.
        val text = ReadingText.consequences(
            FineEstimate.SimplifiedFine(
                amountNok = 10_400,
                points = 6,
                licence = LicenceOutcome.INNDRAS,
                overKmt = 24,
                band = "21-25",
            )
        )
        assertTrue(text, text.contains("24 km/t over"))
        assertTrue(text, text.contains("6 prikker"))
        assertTrue(text, text.contains("tap av førerrett"))
    }

    @Test
    fun `en usikker sats merkes som usikker`() {
        // Satsen gjelder bare motorveg og vegtypen er ukjent. Bilen skrev «(usikker)»,
        // telefonen viste beløpet som om det var sikkert.
        val text = ReadingText.consequences(
            FineEstimate.SimplifiedFine(
                amountNok = 12_800,
                points = 0,
                licence = LicenceOutcome.BEHOLDER,
                overKmt = 38,
                band = "36-40",
                uncertain = true,
            )
        )
        assertTrue(text, text.contains("(usikker)"))
    }

    @Test
    fun `en beholdt forerrett nevnes ikke`() {
        val text = ReadingText.consequences(
            FineEstimate.SimplifiedFine(
                amountNok = 1250,
                points = 0,
                licence = LicenceOutcome.BEHOLDER,
                overKmt = 1,
                band = "1-5",
            )
        )
        assertEquals("1 km/t over", text)
    }

    @Test
    fun `en anmeldelse sier bade hvor mye over og hva det betyr`() {
        val text = ReadingText.consequences(
            FineEstimate.Prosecution(points = 8, overKmt = 45, licence = LicenceOutcome.INNDRAS)
        )
        assertTrue(text, text.contains("45 km/t over"))
        assertTrue(text, text.contains("over taket for forenklet forelegg"))
        assertTrue(text, text.contains("tap av førerrett"))
    }

    @Test
    fun `alvorlighet skiller bot med og uten prikker`() {
        assertEquals(Severity.FINE, ReadingText.severity(reading(60.0, fine(points = 0))))
        assertEquals(Severity.POINTS, ReadingText.severity(reading(60.0, fine(points = 3))))
        assertEquals(Severity.NONE, ReadingText.severity(reading(60.0, FineEstimate.NoOffence)))
        assertEquals(
            Severity.UNKNOWN,
            ReadingText.severity(reading(60.0, FineEstimate.UnknownLimit)),
        )
    }

    /**
     * Er signalet borte, er det ingen konsekvens å farge - uansett hva siste beregning sa.
     * Grått er det ærlige svaret, og «--» er farten.
     */
    @Test
    fun `et dodt signal slaar gjennom alt annet`() {
        val dead = reading(90.0, fine(points = 3), lost = true)
        assertEquals(Severity.LOST, ReadingText.severity(dead))
        assertEquals("--", ReadingText.speed(dead))
        assertEquals("Ingen GPS", ReadingText.headline(dead))
    }

    private fun fine(points: Int) = FineEstimate.SimplifiedFine(
        amountNok = 4800,
        points = points,
        licence = LicenceOutcome.BEHOLDER,
        overKmt = 12,
        band = "11-15",
    )

    private fun reading(
        speedKmt: Double,
        estimate: FineEstimate = FineEstimate.NoOffence,
        lost: Boolean = false,
    ) = SpeedWatch.Reading(
        fix = fix(speedKmt),
        match = null,
        estimate = estimate,
        freshness = if (lost) no.synth.botometer.speed.FixFreshness.LOST
        else no.synth.botometer.speed.FixFreshness.FRESH,
        speedKmt = speedKmt,
        limitKmt = 60,
        manualLimit = false,
    )

    private fun fix(speedKmt: Double) = no.synth.botometer.speed.SpeedFix(
        speedKmt = speedKmt,
        position = no.synth.botometer.limit.LatLon(59.9, 10.7),
        headingDeg = null,
        accuracyMeters = 5f,
        elapsedRealtimeMs = 0L,
    )
}
