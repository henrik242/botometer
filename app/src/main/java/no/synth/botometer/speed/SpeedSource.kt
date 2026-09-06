package no.synth.botometer.speed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.synth.botometer.limit.LatLon

data class SpeedFix(
    val speedKmt: Double,
    val position: LatLon,
    val headingDeg: Double?,
    val accuracyMeters: Float,
    /**
     * Da fixet ble målt, på monoton klokke ([android.os.SystemClock.elapsedRealtime]).
     *
     * Uten denne kan ingen se at farten på skjermen er død. [SpeedFeed] holder siste fix, og et
     * tall som ikke oppdateres ser ut som et tall som ikke endrer seg: i en tunnel ble 97 km/t
     * stående med et voksende bøtebeløp til bilen kom ut igjen. Se [FixFreshness].
     *
     * Monoton og ikke veggklokke: veggklokka kan hoppe, og et hopp bakover ville gjort et
     * gammelt fix ferskt igjen.
     */
    val elapsedRealtimeMs: Long,
) {
    /**
     * Et fix uten fartsmåling - typisk en nettverksposisjon - rapporterer 0, og det er en
     * antakelse, ikke en måling. Feltet som sa hvilken av delene det var, ble skrevet og aldri
     * lest: appen viste antakelsen som om den var målt. Enten skal skillet brukes, eller så
     * skal det ikke stå her og love noe. Det står ikke her lenger.
     */
    companion object
}

/**
 * Hvorfor GPS og ikke bilens speedometer: `CarInfo.addSpeedListener` finnes, men på Android Auto
 * (projected) krever den `com.google.android.gms.permission.CAR_SPEED`, og de fleste hovedenheter
 * rapporterer STATUS_UNAVAILABLE. GPS-fart er dessuten den «sanne» farten - bilens speedometer
 * viser bevisst litt for høyt (ECE R39 tillater overrapportering, ikke underrapportering), og
 * det er den sanne farten politiet måler.
 *
 * Det fantes en `CarSpeedSource` her som leste bilsensoren for å vise avviket. Ingenting
 * instansierte den noen gang, og den var den eneste grunnen til at appen ba om CAR_SPEED - en
 * tillatelse for en funksjon som ikke fantes. Begge deler er borte.
 */
class GpsSpeedSource(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun fixes(intervalMs: Long = 1000L): Flow<SpeedFix> = callbackFlow {
        if (!hasLocationPermission(context)) {
            close(SecurityException("Mangler ACCESS_FINE_LOCATION"))
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // hasSpeed(): Location.getSpeed() er 0 både når du står stille og når fixet ikke
                // har fart i det hele tatt, og de to betyr ikke det samme.
                trySend(
                    SpeedFix(
                        speedKmt = if (loc.hasSpeed()) (loc.speed * 3.6).toDouble() else 0.0,
                        position = LatLon(loc.latitude, loc.longitude),
                        headingDeg = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else null,
                        accuracyMeters = loc.accuracy,
                        elapsedRealtimeMs = loc.elapsedRealtimeNanos / 1_000_000L,
                    )
                )
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Uten denne får ikke bilskjermen fart: en location-tjeneste kan bare startes mens appen
         * er i forgrunnen når posisjonstilgangen er «mens appen er i bruk», og bilskjermen
         * teller ikke som forgrunn.
         *
         * Før Android 10 fantes ikke skillet, og da er den alltid oppfylt.
         */
        fun hasBackgroundLocationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
}
