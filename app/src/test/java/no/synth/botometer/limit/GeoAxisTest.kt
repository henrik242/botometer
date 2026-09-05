package no.synth.botometer.limit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Geo.axisDelta] folder om aksen: motsatt rettet er samme veg. Det er skillet mellom en veg og
 * en pil, og det var forskjellen på 50 og 40 i praksis.
 */
class GeoAxisTest {

    private fun axis(a: Double, b: Double) = Geo.axisDelta(a, b)

    @Test fun `samme retning er null`() = assertEquals(0.0, axis(90.0, 90.0), 0.001)

    @Test fun `motsatt retning er ogsaa null`() {
        // Kjernen i feilen: 180° avvik er samme veg, ikke en annen.
        assertEquals(0.0, axis(90.0, 270.0), 0.001)
        assertEquals(0.0, axis(0.0, 180.0), 0.001)
    }

    @Test fun `det maalte tilfellet fra bilen`() {
        // EV18 lå på Δ173° og ble forkastet av et 45-graders filter. Foldet blir det 7°.
        assertEquals(7.0, axis(90.0, 263.0), 0.001)
    }

    @Test fun `tvers paa er nitti`() {
        assertEquals(90.0, axis(0.0, 90.0), 0.001)
        assertEquals(90.0, axis(0.0, 270.0), 0.001)
    }

    @Test fun `rundt null grader`() {
        assertEquals(20.0, axis(10.0, 350.0), 0.001)
        assertEquals(20.0, axis(350.0, 10.0), 0.001)
    }

    @Test fun `resultatet ligger alltid mellom null og nitti`() {
        var a = 0.0
        while (a < 360.0) {
            var b = 0.0
            while (b < 360.0) {
                val d = axis(a, b)
                assert(d in 0.0..90.0) { "axisDelta($a, $b) = $d er utenfor 0-90" }
                b += 7.0
            }
            a += 11.0
        }
    }
}
