package no.synth.botometer.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import no.synth.botometer.MainActivity
import no.synth.botometer.R
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome

/**
 * Varsler når farten krysser inn i et nytt bøtenivå.
 *
 * Hvorfor varsel og ikke et lite panel ved siden av Maps: Car App Library har ingen slik flate.
 * NAVIGATION er den eneste kategorien som gir en tegneflate, og bilverten kjører én
 * navigasjonsapp om gangen - starter Maps, stopper Botometer. Et varsel er derimot ikke bundet
 * til hvem som eier skjermen, og et tall du ikke ser på gjør uansett ingen nytte.
 *
 * Derfor varsler vi bare ved *overgang* til et nytt nivå, ikke kontinuerlig. Et varsel som står
 * og maser blir borte i støyen, og Google er tydelig på at heads-up bare skal brukes når noe er
 * «drive-critical, time sensitive, and actionable». Overgangen fra 4 800 til 7 450 kroner er det.
 * At du fortsatt ligger 17 over er det ikke.
 *
 * NB: hysterese mangler fortsatt, som README-en sier. [MIN_INTERVAL_MS] er en grovere kur mot
 * samme problem: ligger du og vipper på en trinngrense, får du ett varsel, ikke ti.
 */
class SpeedingAlerts(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var lastBand: String? = null
    private var lastAlertAtMs = 0L

    fun onReading(reading: SpeedWatch.Reading) {
        val band = bandOf(reading.estimate)

        if (band == null) {
            // Tilbake i lovlig fart, eller ukjent fartsgrense. Varselet skal ikke bli stående og
            // påstå noe som ikke lenger gjelder.
            if (lastBand != null) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            lastBand = null
            return
        }

        if (band == lastBand) return
        if (now() - lastAlertAtMs < MIN_INTERVAL_MS) return

        lastBand = band
        lastAlertAtMs = now()
        post(reading.estimate)
    }

    /** null = ingenting å varsle om. Ellers en nøkkel som endrer seg når nivået endrer seg. */
    private fun bandOf(estimate: FineEstimate): String? = when (estimate) {
        is FineEstimate.NoOffence, is FineEstimate.UnknownLimit -> null
        is FineEstimate.SimplifiedFine -> estimate.band
        is FineEstimate.Prosecution -> "anmeldelse"
    }

    private fun post(estimate: FineEstimate) {
        createChannel()

        val title = when (estimate) {
            is FineEstimate.SimplifiedFine -> "${nok(estimate.amountNok)} kr"
            is FineEstimate.Prosecution -> "Anmeldelse"
            else -> return
        }

        val text = when (estimate) {
            is FineEstimate.SimplifiedFine -> buildString {
                append("${estimate.overKmt} km/t over")
                if (estimate.points > 0) append(" · ${estimate.points} prikker")
                when (estimate.licence) {
                    LicenceOutcome.VURDERES -> append(" · førerretten vurderes")
                    LicenceOutcome.INNDRAS -> append(" · førerretten inndras")
                    LicenceOutcome.BEHOLDER -> Unit
                }
                if (estimate.uncertain) append(" (usikker)")
            }
            is FineEstimate.Prosecution ->
                "${estimate.overKmt} km/t over · over taket for forenklet forelegg"
            else -> return
        }

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            // Uten CarAppExtender vises varselet bare på telefonen. Verten viser ikke vanlige
            // varsler på bilskjermen i det hele tatt.
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_stat_speed)
                    .build()
            )
            .build()

        // Varselstillatelsen er brukerens; mangler den, skal ingenting krasje.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // IMPORTANCE_HIGH er det som gjør varselet til en heads-up, både på telefonen og i bilen.
        // Det er en bevisst forskjell fra posisjonsvarselet, som er IMPORTANCE_LOW nettopp for å
        // ikke bli sett.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun nok(amount: Int) = amount.toString()
        .reversed().chunked(3).joinToString(" ").reversed()

    companion object {
        private const val CHANNEL_ID = "speeding"
        private const val NOTIFICATION_ID = 2

        /** Grovkornet vern mot maset ved en trinngrense, i påvente av ekte hysterese. */
        private const val MIN_INTERVAL_MS = 20_000L
    }
}
