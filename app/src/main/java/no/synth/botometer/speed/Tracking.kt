package no.synth.botometer.speed

import android.content.Context
import no.synth.botometer.Diagnostics
import no.synth.botometer.Settings

/**
 * Hvem som vil ha posisjonssporing. Ren telling, uten Android rundt seg, så regelen kan testes
 * for det den er.
 */
class TrackingHolders {

    /**
     * CAR er bilØKTEN, ikke bilskjermen. Det er hele poenget: verten stopper skjermen vår i det
     * Google Maps tar over bilskjermen, men økta lever videre - og det er da varslene er den
     * eneste flaten som er igjen.
     *
     * CONNECTION er selve tilkoblingen til bilen, uavhengig av om noen skjerm vises noe sted.
     * Den finnes fordi CAR og PHONE begge er SKJERMER, og en skjerm kan slukke: er Maps oppe på
     * bilskjermen og telefonskjermen går i svart, holdt ingen av dem lenger - og appen sluttet å
     * varsle nøyaktig når den skulle varslet.
     */
    enum class Holder { CAR, PHONE, CONNECTION }

    private val holders = LinkedHashSet<Holder>()

    val active: Boolean get() = holders.isNotEmpty()

    /** Til diagnostikken. Uten den er «sporingen stoppet» et faktum uten en årsak. */
    val current: Set<Holder> get() = LinkedHashSet(holders)

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
 * Å telle skjermer var bare halve svaret. Begge holderne var fortsatt skjermer, og skjermer
 * slukker: kjører du med Maps på bilskjermen og telefonskjermen går i svart, slapp CAR aldri
 * (økta lever) - men var Botometer aldri åpnet i bilen den turen, finnes det ingen økt, og da
 * var telefonskjermen det eneste som holdt. Den gikk i svart, tellingen ble tom, og varslene
 * døde i det de var det eneste appen hadde igjen. Igjen.
 *
 * Derfor holder også selve BILFORBINDELSEN ([TrackingHolders.Holder.CONNECTION], satt fra
 * [no.synth.botometer.BotometerApp]): er telefonen koblet til bilen, kjører sporingen, uansett
 * hva som vises hvor.
 */
object Tracking {

    private val holders = TrackingHolders()

    /**
     * Konteksten huskes ved start, så stoppen ikke trenger en. En `CarContext` er ikke noe å ta
     * vare på lenger enn nødvendig, og ved opprydding er den ikke alltid trygg å røre.
     */
    private var appContext: Context? = null

    /**
     * Hvordan tjenesten bes om. Bare en søm for testene - en nektet start kan ikke fremkalles
     * i Robolectric, og regelen under er for dyr til å stå udekket.
     */
    internal var starter: (Context) -> Boolean = LocationForegroundService::start

    /**
     * Hovedbryteren sjekkes her, i det ene punktet alle flatene går gjennom, og ikke i hver av
     * dem. Ligger sjekken hos kallerne, er «av» bare av på de stedene noen husket den - og en
     * bryter som slår av det meste er verre enn ingen bryter: da tror brukeren at appen er av
     * mens den leser posisjon.
     */
    @Synchronized
    fun acquire(context: Context, holder: TrackingHolders.Holder) {
        if (!Settings(context).enabled) {
            Diagnostics.tracking("$holder ba om sporing, men appen er slått av", holders.current)
            return
        }

        // HOLD FØRST, start etterpå - og hold selv om starten blir nektet.
        //
        // En holder er en HENSIKT: «denne flaten vil ha sporing». Om systemet lot oss be om
        // tjenesten akkurat nå er et helt annet spørsmål, og fra Android 12 er svaret nei for
        // enhver forgrunnstjeneste startet fra bakgrunnen - altså nettopp når bilen kobler seg
        // til mens appen ligger bak. Å la den nektede starten spise holderen ga denne kjeden:
        //
        //   1. Bilen kobler til i bakgrunnen  → CONNECTION nektes → ingen holder registrert
        //   2. Du åpner telefon-appen         → PHONE i forgrunnen → starten går → {PHONE}
        //   3. Telefonskjermen slukker        → PHONE slippes, SISTE holder → tjenesten stoppes
        //
        // Fartsvarslene døde altså i det telefonskjermen gikk i svart, med bilen tilkoblet.
        // CONNECTION-holderens jobb er å NEKTE en slik stopp, ikke å starte tjenesten; å binde
        // den til en start som avslås nøyaktig når den trengs, var å fjerne den igjen.
        appContext = context.applicationContext
        holders.add(holder)

        // Kalles alltid, ikke bare for den første holderen: blir tjenesten drept av systemet,
        // er tellingen vår fortsatt full, og en betinget start ville aldri startet den igjen.
        // `onStartCommand` tåler å bli kalt om igjen. Neste holder som kommer i forgrunnen
        // reparerer dermed en start som ble nektet tidligere.
        val started = starter(context)
        Diagnostics.tracking(
            if (started) "$holder tok sporingen"
            else "$holder tok sporingen, men systemet nektet oss å starte tjenesten",
            holders.current,
        )
    }

    /**
     * Om noe faktisk sporer nå. Bilskjermen trenger å vite det: stoppes sporingen et annet sted
     * - «Avslutt» på telefonen - står den ellers igjen med siste frame, og et tall som ikke
     * lenger gjelder ser ut som et tall som gjelder.
     */
    val isActive: Boolean
        @Synchronized get() = holders.active

    @Synchronized
    fun release(holder: TrackingHolders.Holder) {
        val last = holders.remove(holder)
        Diagnostics.tracking(
            "$holder slapp sporingen" + if (last) " - siste holder, stopper" else "",
            holders.current,
        )
        if (last) stopService()
    }

    /** «Avslutt» på en av skjermene: slutt for alle. */
    @Synchronized
    fun stopAll() {
        val wasActive = holders.clear()
        Diagnostics.tracking(
            if (wasActive) "avslutt - stopper for alle" else "avslutt, men ingenting var i gang",
            holders.current,
        )
        if (wasActive) stopService()
    }

    private fun stopService() {
        appContext?.let { LocationForegroundService.stop(it) }
        appContext = null
    }
}
