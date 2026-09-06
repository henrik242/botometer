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
import no.synth.botometer.Settings
import no.synth.botometer.fine.FineEstimate

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
    private val settings = Settings(context)

    fun onReading(reading: SpeedWatch.Reading) {
        when (val decision = policy.next(reading.estimate)) {
            is AlertPolicy.Decision.Ignore -> Unit
            is AlertPolicy.Decision.Withdraw ->
                // Ikke stille: et varsel som ble stående viser en bot du ikke lenger tjener opp,
                // og det er en synlig løgn på skjermen.
                runCatching { CarNotificationManager.from(context).cancel(NOTIFICATION_ID) }
                    .onFailure { Diagnostics.alertFailed("kunne ikke trekke tilbake: ${it.message}") }
            // Hele avlesningen, ikke bare anslaget: «hvor mye over» skal regnes av farten og
            // grensen, ikke av bøtegrunnlaget. Se ReadingText.overKmt.
            is AlertPolicy.Decision.Alert -> post(reading)
        }
    }

    private fun post(reading: SpeedWatch.Reading) {
        val estimate = reading.estimate
        // «Bot: 4 800 kr» og ikke bare beløpet: i den sammenslåtte varselskyggen er et tall
        // alene uten kontekst, og de fem tegnene overlever avkortingen.
        val title = when (estimate) {
            is FineEstimate.SimplifiedFine -> "Bot: ${ReadingText.nok(estimate.amountNok)} kr"
            is FineEstimate.Prosecution -> "Anmeldelse"
            else -> return
        }

        val text = when (estimate) {
            is FineEstimate.SimplifiedFine, is FineEstimate.Prosecution ->
                ReadingText.consequences(reading)
            else -> return
        }

        notify(NOTIFICATION_ID, title, text)
    }

    private fun notify(id: Int, title: String, text: String) {
        val phone = settings.phoneAlerts
        val car = settings.carAlerts
        if (!phone && !car) {
            // Ingenting å gjøre - men det skal STÅ at det er derfor. Et varsel som ikke kom
            // fordi brukeren slo det av ser ellers helt likt ut som et varsel som ikke virket.
            Diagnostics.alertSuppressed("både telefon- og bilvarsler er slått av")
            return
        }

        // Er telefonvarslene av, postes det på en stille kanal i stedet for ikke å postes:
        // verten leser varslene fra telefonens varselstrøm, så et varsel i bilen forutsetter et
        // varsel på telefonen. Stille kanal = ingen heads-up der, men fortsatt et i bilen.
        // Viktighet kan ikke endres etter at en kanal er laget, så det må være to av dem.
        val channel = if (phone) CHANNEL_ID else CHANNEL_ID_QUIET
        createChannel(
            channel,
            if (phone) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW,
            if (phone) R.string.channel_alerts else R.string.channel_alerts_quiet,
        )

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            // INGEN CATEGORY_NAVIGATION.
            //
            // Verten har en egen regel for sving-for-sving-varsler: «will not be displayed if
            // the navigation app is not the currently active navigation app, or if the app is
            // already displaying routing information in the navigation template». Botometer er
            // alltid i én av de to tilstandene - eier Maps skjermen, er vi ikke den aktive
            // navigasjonsappen; eier vi skjermen, tegner vi i NavigationTemplate.
            //
            // Vær ærlig om beviset: CarAppExtender-dokumentasjonen sier at et TBT-varsel krever
            // BÅDE `setOngoing(true)` OG kategorien, og vi satte aldri `setOngoing`. Etter den
            // ordlyden var varselet vårt strengt tatt ikke et TBT-varsel, og regelen gjaldt
            // ikke. Kategorien er altså ikke bevist å være årsaken - den er fjernet fordi dette
            // uansett IKKE er et sving-for-sving-varsel, og et varsel skal ikke utgi seg for å
            // være noe annet enn det er. Kommer varselet fortsatt ikke fram, er det et annet
            // spor: kanalens viktighet, eller vertens egne varselinnstillinger.
            .setPriority(
                if (phone) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
            )
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)

        // Uten CarAppExtender vises varselet bare på telefonen. Verten viser ikke vanlige
        // varsler på bilskjermen i det hele tatt - så «av» her er selve mekanismen, ikke en
        // innstilling vi ber verten om å respektere.
        if (car) {
            notification.extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_stat_speed)
                    // Bilskjermens egen viktighet, uavhengig av kanalen på telefonen. Det er den
                    // som gjør at en stille telefonkanal fortsatt gir heads-up i bilen.
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
        }

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
            // CarNotificationManager legger PÅ en tom CarAppExtender hvis varselet ikke har en
            // («updateForCar»). Skal bilvarselet være av, må vi derfor gå utenom den - ellers
            // ville avslåingen ikke virke i det hele tatt.
            if (car) {
                CarNotificationManager.from(context).notify(id, notification)
            } else {
                NotificationManagerCompat.from(context).notify(id, notification.build())
            }
        }.onSuccess {
            // «Ikke kastet» er ikke «vist». Er varsler avslått for appen, gjør notify()
            // ingenting og kaster ingenting, og rapporten sa «varsel sendt» om noe ingen så.
            Diagnostics.alertPosted(
                title,
                text,
                carActive = Diagnostics.carSessionIsActive,
                delivered = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
        }.onFailure {
            Diagnostics.alertFailed(it.message ?: it.javaClass.simpleName)
        }
    }

    private fun createChannel(id: String, importance: Int, nameRes: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // IMPORTANCE_HIGH er det som gjør varselet til en heads-up på telefonen. Det er en
        // bevisst forskjell fra posisjonsvarselet, som er IMPORTANCE_LOW nettopp for å ikke bli
        // sett.
        val channel = NotificationChannel(id, context.getString(nameRes), importance)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Samme varsel som en ekte overtredelse, sendt på kommando.
     *
     * Uten dette krevde hver runde med feilsøking at noen kjørte for fort med bilen tilkoblet,
     * og så husket hva som skjedde. Nå er det ett trykk fra førersetet med tenningen på.
     */
    fun sendTestAlert() {
        notify(NOTIFICATION_ID, "Testvarsel: ${ReadingText.nok(1250)} kr", "Slik ser et fartsvarsel ut")
    }

    companion object {
        /**
         * Offentlig fordi diagnostikken må kunne lese kanalens FAKTISKE viktighet fra systemet.
         * Vår egen `createChannel` sier bare hva vi ba om - og viktighet kan bare senkes, aldri
         * heves, så det vi ba om er ikke nødvendigvis det vi fikk.
         */
        const val CHANNEL_ID = "speeding"

        /** Samme varsel, uten heads-up på telefonen. Viktighet kan ikke endres etter opprettelse. */
        const val CHANNEL_ID_QUIET = "speeding_stille"
        private const val NOTIFICATION_ID = 2
    }
}
