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
     * Om posisjonssporingen skal fortsette når ingen Botometer-skjerm vises.
     *
     * På: bil-økta holder på sporingen så lenge appen er åpnet i bilen, også mens Google Maps
     * eier skjermen. Det er nettopp det som får fartsvarslene til å virke der - uten det stopper
     * GPS-en i det verten stopper skjermen vår.
     *
     * Av: sporingen følger skjermene. Ingen fart og ingen varsler når Maps overtar. Det koster
     * funksjonen, men det er et ekte valg: appen leser da ikke posisjon uten at noe av den er
     * synlig.
     */
    var backgroundTracking: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    /** Til diagnostikkrapporten. En avslått bryter er den første forklaringen å utelukke. */
    fun summary(): String = buildString {
        appendLine("· Varsler på telefonen: " + on(phoneAlerts))
        appendLine("· Varsler i bilen: " + on(carAlerts))
        appendLine("· Sporing i bakgrunnen: " + on(backgroundTracking))
    }

    private fun on(value: Boolean) = if (value) "på" else "AV"

    private companion object {
        const val NAME = "botometer"
        const val KEY_PHONE_ALERTS = "phoneAlerts"
        const val KEY_CAR_ALERTS = "carAlerts"
        const val KEY_BACKGROUND = "backgroundTracking"
    }
}
