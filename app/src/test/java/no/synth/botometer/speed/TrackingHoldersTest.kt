package no.synth.botometer.speed

import no.synth.botometer.speed.TrackingHolders.Holder
import org.junit.Assert.assertEquals
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

    /**
     * Å telle skjermer var bare halve svaret: begge de gamle holderne VAR skjermer, og skjermer
     * slukker. Kjører du med Maps på bilskjermen uten å ha åpnet Botometer i bilen, finnes det
     * ingen bil-økt, og telefonskjermen er alene om å holde. Går den i svart - som den gjør av
     * seg selv mens telefonen projiserer - stoppet sporingen, og varslene døde i det de var det
     * eneste appen hadde igjen.
     */
    @Test
    fun `telefonskjermen som slukker stopper ikke sporingen naar bilen er tilkoblet`() {
        holders.add(Holder.CONNECTION)
        holders.add(Holder.PHONE)

        assertFalse("bilforbindelsen holder fortsatt", holders.remove(Holder.PHONE))
        assertTrue(holders.active)
    }

    @Test
    fun `frakobling stopper sporingen naar ingen skjerm vises`() {
        holders.add(Holder.CONNECTION)

        assertTrue("turen er over, og ingen ser på noe", holders.remove(Holder.CONNECTION))
        assertFalse(holders.active)
    }

    /** Til diagnostikken: «sporingen stoppet» uten hvem som slapp er et faktum uten en årsak. */
    @Test
    fun `holderne kan leses ut`() {
        holders.add(Holder.CONNECTION)
        holders.add(Holder.PHONE)

        assertEquals(setOf(Holder.CONNECTION, Holder.PHONE), holders.current)
        holders.remove(Holder.PHONE)
        assertEquals(setOf(Holder.CONNECTION), holders.current)
    }
}
