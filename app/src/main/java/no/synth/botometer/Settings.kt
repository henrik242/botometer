package no.synth.botometer

import android.content.Context

/**
 * Brukervalgene som skal overleve en omstart.
 *
 * Bevisst forskjellig fra [no.synth.botometer.limit.ManualLimit], som nullstilles ved hver tur:
 * en manuell fartsgrense er et anslag for akkurat denne strekningen, mens «ikke mas på meg på
 * telefonen» er en beslutning om hvordan appen skal oppføre seg.
 *
 * Standard er på for alle tre. En app som er avslått ut av esken er en app som ser ødelagt ut.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Heads-up på telefonen.
     *
     * Av betyr ikke «ingenting postes»: bilverten leser varslene fra telefonens varselstrøm, så
     * et varsel i bilen forutsetter et varsel på telefonen. Av gir derfor en stille kanal - ingen
     * heads-up, bare en linje i skyggen - mens bilen fortsatt får sitt. Det er så stille det går
     * an å bli uten å skru av bilvarselet også.
     */
    var phoneAlerts: Boolean
        get() = prefs.getBoolean(KEY_PHONE_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_ALERTS, value).apply()

    /**
     * Varsel på bilskjermen. Av betyr at varselet postes uten [androidx.car.app.notification.CarAppExtender],
     * og uten den viser verten ingenting - det er selve mekanismen, ikke en innstilling vi ber
     * verten om å respektere.
     */
    var carAlerts: Boolean
        get() = prefs.getBoolean(KEY_CAR_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_CAR_ALERTS, value).apply()

    /**
     * Hovedbryteren. Av betyr av: ingen posisjonssporing, ingen foreground-tjeneste, ingen
     * speedometer noe sted, ingen fartsvarsler og ingen oppslag mot NVDB.
     *
     * Dette het en gang «sporing i bakgrunnen» og betydde bare om bil-økta fikk holde på GPS-en
     * mens Maps eide bilskjermen. Det var et valg mellom to grader av på, og en bryter som lar
     * appen lese posisjon uansett stilling er ikke en av-bryter. En app som leser GPS må kunne
     * skrus helt av, ett sted, uten at brukeren må gjette hvilke deler som fortsatt går.
     *
     * Derfor også en NY nøkkel og ikke den gamle: den som slo av «sporing i bakgrunnen» ba ikke
     * om at hele appen skulle bli mørk, og skal ikke oppdage det etter en oppdatering.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Om vi har bedt om tilganger før.
     *
     * Fra Android 11 er en tillatelse som er avslått to ganger avslått permanent, og
     * `requestPermissions` returnerer da med én gang uten å vise noe. Uten å vite at vi har
     * spurt før, kan appen ikke skille «brukeren har ikke sett dialogen ennå» fra «dialogen
     * kommer aldri mer» - og «Gi tilganger» ble en knapp som ikke gjorde noe.
     */
    var hasAskedForPermissions: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED, value).apply()

    /** Til diagnostikkrapporten. En avslått bryter er den første forklaringen å utelukke. */
    fun summary(): String = buildString {
        // Hovedbryteren først: er den av, forklarer den alt annet i rapporten.
        appendLine("· Botometer: " + on(enabled))
        appendLine("· Varsler på telefonen: " + on(phoneAlerts))
        appendLine("· Varsler i bilen: " + on(carAlerts))
    }

    private fun on(value: Boolean) = if (value) "på" else "AV"

    private companion object {
        const val NAME = "botometer"
        const val KEY_PHONE_ALERTS = "phoneAlerts"
        const val KEY_CAR_ALERTS = "carAlerts"
        const val KEY_ENABLED = "enabled"
        const val KEY_ASKED = "hasAskedForPermissions"
    }
}
