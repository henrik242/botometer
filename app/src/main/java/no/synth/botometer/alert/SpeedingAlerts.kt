package no.synth.botometer.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import no.synth.botometer.Diagnostics
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
 * Selve regelen for NÅR det varsles ligger i [AlertPolicy], uten Android rundt seg, slik at
 * den kan testes for det den er.
 */
class SpeedingAlerts(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val policy = AlertPolicy(now)

    fun onReading(reading: SpeedWatch.Reading) {
        when (val decision = policy.next(reading.estimate)) {
            is AlertPolicy.Decision.Ignore -> Unit
            is AlertPolicy.Decision.Withdraw ->
                runCatching { CarNotificationManager.from(context).cancel(NOTIFICATION_ID) }
            is AlertPolicy.Decision.Alert -> post(decision.estimate)
        }
    }

    private fun post(estimate: FineEstimate) {
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

        notify(NOTIFICATION_ID, title, text)
    }

    private fun notify(id: Int, title: String, text: String) {
        createChannel()

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
            // INGEN CATEGORY_NAVIGATION. Den er grunnen til at varselet aldri kom fram i bilen.
            //
            // Verten behandler et navigasjonsvarsel fra en navigasjonsapp som en sving-for-sving-
            // melding, og de har en egen regel: «will not be displayed if the navigation app is
            // not the currently active navigation app, or if the app is already displaying
            // routing information in the navigation template».
            //
            // Botometer er alltid i én av de to tilstandene. Eier Maps skjermen, er vi ikke den
            // aktive navigasjonsappen. Eier vi skjermen, tegner vi i NavigationTemplate. Begge
            // grenene undertrykker varselet, så det kunne aldri vises - mens telefonen viste det
            // hver gang, siden regelen er vertens og ikke systemets.
            //
            // Dette er ikke et sving-for-sving-varsel. Det skal vises nettopp når vi IKKE eier
            // skjermen, og da skal det ikke utgi seg for å være noe annet.
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
                    // Bilskjermens egen viktighet, uavhengig av kanalen på telefonen. Uten den
                    // arves kanalens, og da er det én ting til som kan være årsaken uten å si fra.
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )

        // CarNotificationManager, ikke NotificationManagerCompat. Dokumentasjonen til
        // CarAppExtender sier det rett ut: «Post the notification with the
        // CarNotificationManager.notify(...) methods. Do not use the NotificationManager.notify
        // (...), nor the NotificationManagerCompat.notify(...) methods.»
        //
        // På projisert Android Auto gjør de to i praksis det samme når varselet allerede er
        // utvidet, så dette alene var ikke feilen - men på Automotive OS er det det som gjør at
        // varselet i det hele tatt havner riktig, og det er den dokumenterte veien.
        //
        // Varselstillatelsen er brukerens; mangler den, skal ingenting krasje. Men feilen skal
        // ikke forsvinne heller: et varsel som ble kastet i stillhet ser ut som et varsel som
        // ble sendt, og da leter du etter feilen alle andre steder enn der den er.
        runCatching {
            CarNotificationManager.from(context).notify(id, notification)
        }.onSuccess {
            Diagnostics.alertPosted(title, text, Diagnostics.carSessionIsActive)
        }.onFailure {
            Diagnostics.alertFailed(it.message ?: it.javaClass.simpleName)
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

    /**
     * Samme varsel som en ekte overtredelse, sendt på kommando.
     *
     * Uten dette krevde hver runde med feilsøking at noen kjørte for fort med bilen tilkoblet,
     * og så husket hva som skjedde. Nå er det ett trykk fra førersetet med tenningen på.
     */
    fun sendTestAlert() {
        notify(NOTIFICATION_ID, "${nok(1250)} kr", "1 km/t over · testvarsel")
    }

    private fun nok(amount: Int) = amount.toString()
        .reversed().chunked(3).joinToString(" ").reversed()

    companion object {
        /**
         * Offentlig fordi diagnostikken må kunne lese kanalens FAKTISKE viktighet fra systemet.
         * Vår egen `createChannel` sier bare hva vi ba om - og viktighet kan bare senkes, aldri
         * heves, så det vi ba om er ikke nødvendigvis det vi fikk.
         */
        const val CHANNEL_ID = "speeding"
        private const val NOTIFICATION_ID = 2
    }
}
