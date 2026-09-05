package no.synth.botometer.speed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.Speed
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
    val source: Source,
) {
    enum class Source { GPS, CAR }
}

/**
 * Hvorfor GPS og ikke bilens speedometer: `CarInfo.addSpeedListener` finnes, men på Android Auto
 * (projected) krever den `com.google.android.gms.permission.CAR_SPEED`, og de fleste hovedenheter
 * rapporterer STATUS_UNAVAILABLE. GPS-fart er dessuten den «sanne» farten - bilens speedometer
 * viser bevisst litt for høyt (ECE R39 tillater overrapportering, ikke underrapportering), og
 * det er den sanne farten politiet måler. Bilsensoren brukes derfor bare til å vise avviket.
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
                trySend(
                    SpeedFix(
                        speedKmt = (loc.speed * 3.6).toDouble(),
                        position = LatLon(loc.latitude, loc.longitude),
                        headingDeg = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else null,
                        accuracyMeters = loc.accuracy,
                        source = SpeedFix.Source.GPS,
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

/**
 * Valgfritt: bilens rapporterte fart, kun for å vise speedometeravviket. Feiler stille når
 * hovedenheten ikke støtter det, som er det vanlige.
 */
class CarSpeedSource(private val carContext: CarContext) {
    fun speeds(): Flow<Double> = callbackFlow {
        val carInfo = try {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
        } catch (t: Throwable) {
            Log.i(TAG, "CarHardware utilgjengelig: ${t.message}")
            close(); return@callbackFlow
        }

        val listener = androidx.car.app.hardware.common.OnCarDataAvailableListener<Speed> { data ->
            val v = data.displaySpeedMetersPerSecond
            if (v.status == CarValue.STATUS_SUCCESS) v.value?.let { trySend(it * 3.6) }
        }

        try {
            carInfo.addSpeedListener(carContext.mainExecutor, listener)
        } catch (t: Throwable) {
            Log.i(TAG, "Ingen CAR_SPEED-tilgang: ${t.message}")
            close(); return@callbackFlow
        }
        awaitClose { runCatching { carInfo.removeSpeedListener(listener) } }
    }

    private companion object { const val TAG = "CarSpeedSource" }
}
