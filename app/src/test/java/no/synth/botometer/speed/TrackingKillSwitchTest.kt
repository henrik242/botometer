package no.synth.botometer.speed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Hovedbryteren er den ene innstillingen som må være umulig å omgå. En bryter som slår av det
 * meste er verre enn ingen bryter: da tror brukeren at appen er av mens den leser posisjon.
 *
 * [Tracking] er det ene punktet alle flatene går gjennom for å starte sporing, og derfor stedet
 * sjekken hører hjemme. Lå den hos kallerne, ville «av» bare vært av de stedene noen husket den.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingKillSwitchTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** [Tracking] er et objekt, og statisk tilstand overlever mellom tester i samme lastere. */
    @After fun cleanup() = Tracking.stopAll()

    @Test
    fun `avslaatt app starter ikke posisjonssporing`() {
        Settings(context).enabled = false

        Tracking.acquire(context, TrackingHolders.Holder.PHONE)

        assertNull(
            "en avslått app skal ikke be om posisjon i det hele tatt",
            shadowOf(context).nextStartedService,
        )
    }

    @Test
    fun `paaslaatt app starter posisjonssporing`() {
        Settings(context).enabled = true

        Tracking.acquire(context, TrackingHolders.Holder.PHONE)

        assertEquals(
            LocationForegroundService::class.java.name,
            shadowOf(context).nextStartedService?.component?.className,
        )
    }

    /**
     * «Avslutt» stopper for alle, uansett hvem som holdt.
     *
     * Testen het en gang «avslag mens sporingen går stopper tjenesten» og satte
     * `Settings.enabled = false` før den kalte `stopAll()` selv. Fjernet man den linja, besto
     * den identisk: den testet `stopAll`, ikke bryteren. Nå heter den det den gjør, og
     * bryterens egen kobling testes der den finnes - i telefon-appen.
     */
    @Test
    fun `avslutt stopper tjenesten uansett hvem som holdt`() {
        Settings(context).enabled = true
        Tracking.acquire(context, TrackingHolders.Holder.CAR)
        Tracking.acquire(context, TrackingHolders.Holder.PHONE)
        shadowOf(context).clearStartedServices()

        Tracking.stopAll()

        assertEquals(
            LocationForegroundService::class.java.name,
            shadowOf(context).nextStoppedService?.component?.className,
        )
    }

    /**
     * Selve grunnen til at holdere telles. Bytter du til Maps på telefonen mens bilen kjører,
     * slipper PHONE - og gjorde det en ubetinget stopp, døde fartsvarslene i bilen i samme
     * øyeblikk. Endrer man `if (last)` til en ubetinget stopp, består alle de andre testene.
     */
    @Test
    fun `telefonen som slipper stopper ikke sporingen naar bilen holder`() {
        Settings(context).enabled = true
        Tracking.acquire(context, TrackingHolders.Holder.CAR)
        Tracking.acquire(context, TrackingHolders.Holder.PHONE)
        shadowOf(context).clearStartedServices()

        Tracking.release(TrackingHolders.Holder.PHONE)

        assertNull(
            "bilen holder fortsatt, tjenesten skal ikke ha blitt stoppet",
            shadowOf(context).nextStoppedService,
        )
        assertTrue(Tracking.isActive)
    }

    @Test
    fun `siste holder som slipper stopper tjenesten`() {
        Settings(context).enabled = true
        Tracking.acquire(context, TrackingHolders.Holder.PHONE)

        Tracking.release(TrackingHolders.Holder.PHONE)

        assertEquals(
            LocationForegroundService::class.java.name,
            shadowOf(context).nextStoppedService?.component?.className,
        )
        assertFalse(Tracking.isActive)
    }

    /**
     * Tjenesten bes om på nytt selv når tellingen alt er full. Blir den drept av systemet, er
     * holderne våre fortsatt der, og en betinget start ville aldri startet den igjen.
     */
    @Test
    fun `en ny holder ber om tjenesten paa nytt selv om noen alt holder`() {
        Settings(context).enabled = true
        Tracking.acquire(context, TrackingHolders.Holder.CAR)
        shadowOf(context).clearStartedServices()

        Tracking.acquire(context, TrackingHolders.Holder.PHONE)

        assertEquals(
            LocationForegroundService::class.java.name,
            shadowOf(context).nextStartedService?.component?.className,
        )
    }
}
