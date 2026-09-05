package no.synth.botometer

import android.app.NotificationManager
import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationManagerCompat
import no.synth.botometer.alert.SpeedingAlerts

/**
 * Svarer på «hvorfor dukker fartsvarselet opp på telefonen, men ikke på bilskjermen?».
 *
 * At det vises ett sted og ikke det andre er en sterk innsnevring: da er varselet postet, det er
 * ikke blokkert, og appen gjør jobben sin. Det som gjenstår er tingene bilverten ser på og
 * telefonen ikke gjør - og ingen av dem sier fra når de er årsaken. Verten forkaster varselet i
 * stillhet, akkurat som den forkaster en app fra app-oversikten i stillhet.
 *
 * Kanalens viktighet er den viktigste, og den mest bedragerske: **den kan bare senkes, aldri
 * heves.** Er kanalen «speeding» først opprettet med en lavere viktighet - eller senket av
 * brukeren, eller av systemet fordi varslene ble avvist ofte nok - så gjør ikke koden vår noe med
 * det ved å opprette den på nytt med IMPORTANCE_HIGH. Den forespørselen ignoreres. Telefonen
 * viser varselet i skyggen likevel, så det ser ut som om alt virker.
 */
object CarNotificationDiagnostics {

    fun summary(context: Context, carConnectionType: Int?): String = buildString {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) {
            appendLine("✗ Varsler er AV for appen - ingenting vises noe sted")
            appendLine("  Slå dem på i innstillingene, så er dette utelukket.")
        } else {
            appendLine("✓ Varsler er på for appen")
        }

        appendChannel(context)
        appendLine(carConnection(carConnectionType))
        appendLine(Diagnostics.carSessionLine())
        append(Diagnostics.alertLines())

        // Hva som faktisk sendes. Er alt over grønt, er det dette som må holdes mot
        // vertens krav - og da er det greit å slippe å lese det ut av kildekoden.
        appendLine("· Sendes som: CarAppExtender, CATEGORY_NAVIGATION, PRIORITY_HIGH")
        appendLine("· Er alt over ✓ og varselet likevel ikke vises i bilen, er neste sted")
        appendLine("  Android Auto-innstillingene på hodeenheten (varsler/kjøremodus), og")
        appendLine("  deretter vertsloggen - se tools/bil-diagnostikk.sh i README.")
    }

    private fun StringBuilder.appendChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = runCatching { manager?.getNotificationChannel(SpeedingAlerts.CHANNEL_ID) }
            .getOrNull()

        if (channel == null) {
            appendLine("· Varselkanal «${SpeedingAlerts.CHANNEL_ID}»: ikke opprettet ennå")
            appendLine("  (den lages ved første fartsvarsel)")
            return
        }

        val importance = channel.importance
        if (importance >= NotificationManager.IMPORTANCE_HIGH) {
            appendLine("✓ Varselkanal «${SpeedingAlerts.CHANNEL_ID}»: ${importanceName(importance)}")
        } else {
            appendLine("✗ Varselkanal «${SpeedingAlerts.CHANNEL_ID}»: ${importanceName(importance)} - FOR LAV")
            appendLine("  Under HIGH gir ingen heads-up, og bilverten viser ikke varselet.")
            appendLine("  Telefonen viser det i skyggen likevel, så det ser ut som om alt virker.")
            appendLine("  Viktighet kan bare SENKES av appen, aldri heves: å opprette kanalen på")
            appendLine("  nytt hjelper ikke. Sett den til «Høy»/«Vis på skjermen» i appens")
            appendLine("  varselinnstillinger, eller avinstaller og installer på nytt.")
        }
        if (!channel.canBypassDnd() && importance >= NotificationManager.IMPORTANCE_HIGH) {
            appendLine("  (kanalen slipper ikke gjennom «Ikke forstyrr» - sjekk kjøremodus)")
        }
    }

    private fun carConnection(type: Int?): String = when (type) {
        null -> "· Bilforbindelse: ikke lest ennå"
        CarConnection.CONNECTION_TYPE_NOT_CONNECTED ->
            "✗ Bilforbindelse: IKKE tilkoblet - et varsel nå vises bare på telefonen"
        CarConnection.CONNECTION_TYPE_PROJECTION -> "✓ Bilforbindelse: Android Auto (projisert)"
        CarConnection.CONNECTION_TYPE_NATIVE -> "✓ Bilforbindelse: Automotive OS"
        else -> "· Bilforbindelse: ukjent type ($type)"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "NONE (blokkert)"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        NotificationManager.IMPORTANCE_MAX -> "MAX"
        else -> "ukjent ($importance)"
    }
}
