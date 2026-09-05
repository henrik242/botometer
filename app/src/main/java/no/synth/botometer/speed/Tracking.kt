package no.synth.botometer.speed

import android.content.Context

/**
 * Hvem som vil ha posisjonssporing. Ren telling, uten Android rundt seg, så regelen kan testes
 * for det den er.
 */
class TrackingHolders {

    /**
     * CAR er bilØKTEN, ikke bilskjermen. Det er hele poenget: verten stopper skjermen vår i det
     * Google Maps tar over bilskjermen, men økta lever videre - og det er da varslene er den
     * eneste flaten som er igjen.
     */
    enum class Holder { CAR, PHONE }

    private val holders = LinkedHashSet<Holder>()

    val active: Boolean get() = holders.isNotEmpty()

    fun add(holder: Holder) {
        holders += holder
    }

    /** @return true når det ikke er noen igjen som vil ha sporing, altså «stopp nå». */
    fun remove(holder: Holder): Boolean {
        if (!holders.remove(holder)) return false
        return holders.isEmpty()
    }

    /** «Avslutt»: slutt for alle, uansett hvem som holder. @return true om noe var i gang. */
    fun clear(): Boolean {
        val wasActive = active
        holders.clear()
        return wasActive
    }
}

/**
 * Bindeleddet mellom flatene og [LocationForegroundService].
 *
 * Sporingen fulgte tidligere skjermene: `SpeedometerScreen.onStop` stoppet tjenesten. Verten
 * stopper skjermen vår i det Google Maps tar over bilskjermen, så GPS-en - og med den
 * fartsvarslene - døde nøyaktig når varselet var den eneste flaten som var igjen. README-en
 * påsto at tjenesten «lever videre når Maps overtar skjermen»; det gjorde den ikke.
 *
 * Nå teller vi hvem som vil ha den. Bilen holder på sporingen så lenge ØKTA lever - altså til
 * appen avsluttes i bilen eller telefonen kobles fra - ikke bare så lenge skjermen vår er den
 * som vises. Telefonen holder på den mens speedometeret er synlig, som før, så tilbake-knappen
 * på telefonen fortsatt slår av GPS-en når det er den eneste flaten i bruk.
 */
object Tracking {

    private val holders = TrackingHolders()

    /**
     * Konteksten huskes ved start, så stoppen ikke trenger en. En `CarContext` er ikke noe å ta
     * vare på lenger enn nødvendig, og ved opprydding er den ikke alltid trygg å røre.
     */
    private var appContext: Context? = null

    @Synchronized
    fun acquire(context: Context, holder: TrackingHolders.Holder) {
        appContext = context.applicationContext
        holders.add(holder)
        // Alltid, ikke bare når den første holderen kommer: blir tjenesten drept av systemet,
        // er tellingen vår fortsatt full, og da ville en betinget start aldri startet den igjen.
        // `onStartCommand` tåler å bli kalt om igjen.
        LocationForegroundService.start(context)
    }

    @Synchronized
    fun release(holder: TrackingHolders.Holder) {
        if (holders.remove(holder)) stopService()
    }

    /** «Avslutt» på en av skjermene: slutt for alle. */
    @Synchronized
    fun stopAll() {
        if (holders.clear()) stopService()
    }

    private fun stopService() {
        appContext?.let { LocationForegroundService.stop(it) }
        appContext = null
    }
}
