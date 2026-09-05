package no.synth.botometer.speed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Den farligste feilen appen kunne gjøre var å ikke gjøre noe.
 *
 * [SpeedFeed] er en StateFlow, og en StateFlow emitterer bare når verdien endrer seg. Forsvant
 * GPS-en i en tunnel, sluttet fixene å komme - og skjermen ble stående med den siste farten og
 * det siste bøtebeløpet, uendret og uten et eneste tegn på at tallet var dødt. Det så ut som en
 * app som virket.
 */
class FixFreshnessTest {

    @Test
    fun `et ferskt fix er ferskt`() {
        assertEquals(FixFreshness.FRESH, FixFreshness.ofAge(0))
        assertEquals(FixFreshness.FRESH, FixFreshness.ofAge(4_999))
    }

    @Test
    fun `fem sekunder uten fix er fem tapte fix, ikke en hikke`() {
        assertEquals(FixFreshness.STALE, FixFreshness.ofAge(FixFreshness.STALE_AFTER_MS))
        assertEquals(FixFreshness.STALE, FixFreshness.ofAge(19_999))
    }

    @Test
    fun `etter tjue sekunder vet vi ingenting om farten`() {
        assertEquals(FixFreshness.LOST, FixFreshness.ofAge(FixFreshness.LOST_AFTER_MS))
        assertEquals(FixFreshness.LOST, FixFreshness.ofAge(10 * 60_000))
    }

    @Test
    fun `en klokke som hopper bakover gjoer ikke et gammelt fix ferskt`() {
        // Alderen måles på elapsedRealtime nettopp for at dette ikke skal kunne skje, men et
        // negativt tall skal uansett ikke bli til LOST eller noe annet rart.
        assertEquals(FixFreshness.FRESH, FixFreshness.ofAge(-1_000))
    }
}
