package no.synth.botometer.speed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import no.synth.botometer.Diagnostics
import no.synth.botometer.MainActivity
import no.synth.botometer.R
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.alert.SpeedingAlerts
import no.synth.botometer.limit.ManualLimit

/**
 * Eier GPS-abonnementet så lenge speedometeret vises i bilen.
 *
 * En foreground service av typen `location` er den sanksjonerte måten å lese posisjon uten at
 * appen er synlig på telefonen. Alternativet, ACCESS_BACKGROUND_LOCATION, er både strengere
 * regulert i Play og unødvendig her: brukeren ser aktivt på appen, den er bare på en annen skjerm.
 *
 * Å starte en foreground service fra en CarAppService er tillatt fordi bilverten er en
 * forgrunnsapp som er bundet til prosessen vår, og bindingen løfter oss til forgrunnsviktighet.
 * Dette er samme mønster som Googles egne navigasjonseksempler bruker.
 */
class LocationForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null
    private var alertJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground kan nekte, og gjør det som en SecurityException som tar hele appen med
        // seg. Fra Android 14 krever en location-tjeneste at appen er i en «eligible state»: er
        // posisjonstilgangen gitt som «mens appen er i bruk», er den en forgrunnstillatelse, og
        // da holder det ikke å ha den - appen må også være i forgrunnen i det øyeblikket.
        //
        // Bilskjermen er ikke det. Appen på telefonen er i bakgrunnen mens speedometeret vises i
        // bilen, og da faller starten igjennom.
        //
        // Et krasj er uansett feil svar. Tjenesten som ikke fikk lov skal legge seg ned og si
        // hvorfor, så telefon-appen kan vise det.
        val startedForeground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else 0,
            )
        }.onFailure { e ->
            Log.e(TAG, "Fikk ikke starte posisjonssporing: ${e.message}")
            Diagnostics.locationServiceFailed(e.message ?: e.javaClass.simpleName)
        }.isSuccess

        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        Diagnostics.locationServiceStarted()

        if (job == null) {
            job = scope.launch {
                GpsSpeedSource(this@LocationForegroundService).fixes()
                    .catch { Log.w(TAG, "GPS-strømmen stoppet: ${it.message}") }
                    .collect { SpeedFeed.publish(it) }
            }
        }

        // Varslene hører hjemme her, ikke på bilskjermen: servicen lever videre når Maps
        // overtar skjermen, og det er nettopp da et varsel er den eneste veien fram.
        if (alertJob == null) {
            val alerts = SpeedingAlerts(applicationContext)
            alertJob = scope.launch {
                SpeedWatch(this@LocationForegroundService).readings()
                    .catch { Log.w(TAG, "Fartsvarslene stoppet: ${it.message}") }
                    .collect { alerts.onReading(it) }
            }
        }

        // START_NOT_STICKY: mister vi tilgang eller blir drept, skal vi ikke gjenoppstå av oss selv.
        // Skjermen i bilen er den som bestemmer om vi skal kjøre.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job = null
        alertJob = null
        scope.cancel()
        SpeedFeed.clear()
        // Turen er over. En manuell fartsgrense som overlever til neste tur er en grense du har
        // glemt at du satte, og den ville regnet bot av feil tall i stillhet.
        ManualLimit.clear()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_location),
            NotificationManager.IMPORTANCE_LOW,   // ingen lyd, ingen heads-up
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    companion object {
        private const val TAG = "LocationFgService"
        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 1

        /**
         * Selve starten kan også nektes, med ForegroundServiceStartNotAllowedException, før
         * tjenesten i det hele tatt kjører. Samme resonnement: si fra, ikke krasj.
         */
        fun start(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "Fikk ikke be om posisjonssporing: ${it.message}")
                Diagnostics.locationServiceFailed(it.message ?: it.javaClass.simpleName)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }
}
