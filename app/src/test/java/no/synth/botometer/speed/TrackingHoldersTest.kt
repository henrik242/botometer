package no.synth.botometer.speed

import no.synth.botometer.speed.TrackingHolders.Holder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fartsvarslene virket ikke når Google Maps fylte bilskjermen - altså i det ene tilfellet de
 * finnes for.
 *
 * Sporingen fulgte skjermene: `SpeedometerScreen.onStop` stoppet posisjonstjenesten, og verten
 * stopper skjermen vår i det en annen navigasjonsapp tar over. Da døde GPS-en, og med den
 * varslene, i samme øyeblikk som varselet ble den eneste flaten appen hadde igjen.
 *
 * Regelen her er derfor at bilen holder på sporingen så lenge ØKTA lever, ikke så lenge skjermen
 * vår vises.
 */
class TrackingHoldersTest {

    private val holders = TrackingHolders()

    @Test
    fun `ingen holdere til aa begynne med`() {
        assertFalse(holders.active)
    }

    @Test
    fun `siste holder som slipper stopper sporingen`() {
        holders.add(Holder.PHONE)
        assertTrue("ingen igjen, da skal den stoppe", holders.remove(Holder.PHONE))
        assertFalse(holders.active)
    }

    @Test
    fun `telefonen som lukkes stopper ikke bilens sporing`() {
        // Nettopp dette: bytter du til Maps på telefonen mens bilskjermen kjører, skal ikke
        // farten i bilen dø av det.
        holders.add(Holder.CAR)
        holders.add(Holder.PHONE)

        assertFalse("bilen holder fortsatt", holders.remove(Holder.PHONE))
        assertTrue(holders.active)
    }

    @Test
    fun `bilen som slipper stopper ikke telefonens sporing`() {
        holders.add(Holder.CAR)
        holders.add(Holder.PHONE)

        assertFalse(holders.remove(Holder.CAR))
        assertTrue(holders.active)
    }

    @Test
    fun `aa slippe en holder to ganger stopper ikke noe`() {
        // Livssyklusene overlapper: onStop kan komme etter at «Avslutt» allerede har ryddet.
        holders.add(Holder.CAR)
        holders.add(Holder.PHONE)
        assertFalse(holders.remove(Holder.PHONE))
        assertFalse("allerede sluppet, ingen ny stopp", holders.remove(Holder.PHONE))
        assertTrue("bilen holder fortsatt, og skal ikke ha blitt stoppet", holders.active)
    }

    @Test
    fun `aa slippe en holder som aldri holdt stopper ikke noe`() {
        holders.add(Holder.CAR)
        assertFalse(holders.remove(Holder.PHONE))
        assertTrue(holders.active)
    }

    @Test
    fun `samme holder to ganger teller fortsatt som en`() {
        holders.add(Holder.CAR)
        holders.add(Holder.CAR)
        assertTrue("ellers ville skjermen måttet slippe like mange ganger", holders.remove(Holder.CAR))
    }

    @Test
    fun `avslutt stopper uansett hvem som holder`() {
        holders.add(Holder.CAR)
        holders.add(Holder.PHONE)

        assertTrue(holders.clear())
        assertFalse(holders.active)
    }

    @Test
    fun `avslutt naar ingenting er i gang stopper ingenting`() {
        assertFalse("ikke send en stopp til en tjeneste som ikke kjører", holders.clear())
    }
}
