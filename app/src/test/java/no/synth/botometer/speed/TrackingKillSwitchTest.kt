package no.synth.botometer.speed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
     * Slås bryteren av mens noe sporer, skal sporingen stoppe der og da - ikke ved neste
     * oppstart. Det er da brukeren ser etter at det vedvarende varselet forsvinner.
     */
    @Test
    fun `avslag mens sporingen gaar stopper tjenesten`() {
        Settings(context).enabled = true
        Tracking.acquire(context, TrackingHolders.Holder.PHONE)
        shadowOf(context).clearStartedServices()

        Settings(context).enabled = false
        Tracking.stopAll()

        assertEquals(
            LocationForegroundService::class.java.name,
            shadowOf(context).nextStoppedService?.component?.className,
        )
    }
}
