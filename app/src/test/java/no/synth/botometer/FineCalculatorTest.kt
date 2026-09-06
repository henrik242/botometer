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

    /**
     * Satstabellen er data, uten en kompilator bak seg. Fire beløp var festet; resten kunne
     * endres i stillhet. Et forskjøvet `overMin`, en tapt `prikker` eller en feilskrevet
     * `forerrett` ved neste satsoppdatering endrer nøyaktig det tallet appen finnes for å vise,
     * og ingenting ville feilet.
     *
     * Her sjekkes HVERT trinn i HVER gruppe, på begge kantene av intervallet.
     */
    @Test
    fun `hvert trinn i tabellen gir sitt eget belop`() {
        val table = FineTable.fromJson(File("src/main/assets/botesatser.json").readText())
        var checked = 0

        for (group in table.grupper) {
            // Gruppa «inntil60» starter på 0, og 0 er ingen fartsgrense - `estimate` svarer
            // UnknownLimit på den, med rette. Velg en ekte skiltet grense inne i intervallet.
            val limit = group.fartsgrenseMin.coerceAtLeast(30).coerceAtMost(group.fartsgrenseMaks)
            for (band in group.trinn) {
                // Motorvegtrinnene gjelder bare motorveg; be om det eksplisitt, ellers faller
                // de bort og vi ville testet nabotrinnet.
                val motorway = if (band.kunMotorveg) true else null
                val edges = listOfNotNull(band.overMin, band.overMaks)

                for (over in edges) {
                    // Inverter sikkerhetsfradraget: grunnlaget skal lande nøyaktig på `over`.
                    val speed = speedGiving(table, limit + over)
                    val actual = calc.estimate(speed, limit, motorway)

                    if (band.anmeldelse || band.bot == null) {
                        assertTrue(
                            "${group.id} $over over skulle vært anmeldelse, ble $actual",
                            actual is FineEstimate.Prosecution,
                        )
                    } else {
                        val fine = actual as? FineEstimate.SimplifiedFine
                        assertTrue("${group.id} $over over ga $actual", fine != null)
                        assertEquals("${group.id} ${band.label} belop", band.bot, fine!!.amountNok)
                        assertEquals("${group.id} ${band.label} prikker", band.prikker, fine.points)
                        assertEquals("${group.id} ${band.label} forerrett", band.licence, fine.licence)
                        assertEquals("${group.id} ${band.label} over", over, fine.overKmt)
                    }
                    checked++
                }
            }
        }
        assertTrue("tabellen ser tom ut - testen sjekket $checked trinn", checked >= 20)
    }

    /**
     * Sikkerhetsfradraget spiser de første km/t-ene. Appen skal ikke rope ulv på 53 i en
     * 50-sone; gjorde den det, ville alle andre varsler blitt støy.
     */
    @Test
    fun `tre km over malt fart er ingen bot`() {
        assertEquals(FineEstimate.NoOffence, calc.estimate(speedKmt = 53.0, limitKmt = 50))
    }

    /** En fartsgrense ingen gruppe dekker skal gi «vet ikke», ikke en gjetning. */
    @Test
    fun `en fartsgrense ingen gruppe dekker gir ikke gjetning`() {
        assertEquals(FineEstimate.UnknownLimit, calc.estimate(speedKmt = 200.0, limitKmt = 140))
        assertEquals(FineEstimate.UnknownLimit, calc.estimate(speedKmt = 60.0, limitKmt = 0))
        assertEquals(FineEstimate.UnknownLimit, calc.estimate(speedKmt = 60.0, limitKmt = null))
    }

    /** «X km/t til første bot» på bilskjermen. Et anslag, men det skal ikke være tull. */
    @Test
    fun `marginen til neste trinn er null naar det ikke finnes et`() {
        assertEquals(null, calc.kmtToNextBand(speedKmt = 60.0, limitKmt = null))
        val margin = calc.kmtToNextBand(speedKmt = 50.0, limitKmt = 50)
        assertTrue("marginen var $margin", margin != null && margin in 0..20)
    }

    /**
     * Finner en målt fart som gir nøyaktig `basis` etter sikkerhetsfradraget. Fradraget er lite
     * og monotont, så en kort søking er både enklere og sikrere enn å invertere formelen.
     */
    private fun speedGiving(table: FineTable, basis: Int): Double {
        var speed = basis.toDouble()
        repeat(200) {
            if (kotlin.math.floor(table.toleranse.deduct(speed)).toInt() >= basis) return speed
            speed += 0.5
        }
        return speed
    }
}
