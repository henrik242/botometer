package no.synth.botometer.car

import android.Manifest
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.synth.botometer.R
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.LocationForegroundService

/**
 * Bruker NavigationTemplate fordi det er den ENESTE templaten i Car App Library som gir tilgang
 * til en tegneflate (Surface). Alle andre templates er ferdigdefinerte lister/paneler, og et
 * speedometer må tegnes fritt. Det betyr at appen må deklarere seg som navigasjonsapp - se README
 * for konsekvensene av det.
 *
 * Skjermen abonnerer ikke på GPS selv. LocationForegroundService eier abonnementet, fordi appen
 * ellers mister posisjon straks telefonskjermen slukkes.
 */
class SpeedometerScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    // Samme regnestykke som telefonskjermen og fartsvarslene bruker: fart inn, bot ut, med
    // GPS-friskhet, manuell fartsgrense og hysterese på ett sted. Tre kopier ville betydd at
    // appen kunne varsle om en bot den ikke viser.
    //
    // Leser satsene fra disk-cachen om den finnes, ellers asseten. Ingen nettverk i bilen -
    // satsene oppdateres fra telefon-appen.
    private val watch = SpeedWatch(carContext)

    private val renderer = SpeedometerRenderer(
        carContext, watch.calculator, ratesStale = watch.ratesStale
    )

    private var collectJob: Job? = null

    /**
     * Bilverten vil ha et ikon i handlingsstripa; en knapp med bare tittel avvises av enkelte
     * verter. Tittelen står ved siden av, for et kryss alene er ikke selvforklarende når det
     * ligger ved siden av app-ikonet.
     */
    private val exitIcon by lazy {
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_close)).build()
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        startTracking()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopTracking()
        // Må nullstilles, ellers tegner rendereren mot en død surface neste gang skjermen vises.
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    private fun startTracking() {
        if (!GpsSpeedSource.hasLocationPermission(carContext)) return

        LocationForegroundService.start(carContext)

        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            watch.readings().collect { renderer.update(it) }
        }
    }

    private fun stopTracking() {
        collectJob?.cancel()
        collectJob = null
        // Ingen grunn til å lese posisjon når speedometeret ikke vises.
        LocationForegroundService.stop(carContext)
    }

    override fun onGetTemplate(): Template {
        if (!GpsSpeedSource.hasLocationPermission(carContext)) {
            return MessageTemplate.Builder("Botometer trenger posisjonstilgang. Gi tilgang i appen på telefonen, så kom tilbake hit.")
                .setTitle("Mangler tilgang")
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Be om tilgang")
                        .setOnClickListener { requestPermission() }
                        .build()
                )
                .build()
        }

        return NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcon.APP_ICON)
                            .setOnClickListener {
                                CarToast.makeText(
                                    carContext,
                                    buildString {
                                        append("Satser pr. ${watch.calculator.version}")
                                        if (watch.ratesStale) append(" (UTDATERT - oppdater i telefon-appen)")
                                        append(". Anslag - ikke juridisk bindende. Fartsgrenser: Statens vegvesen (NLOD 2.0).")
                                    },
                                    CarToast.LENGTH_LONG,
                                ).show()
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Avslutt")
                            .setIcon(exitIcon)
                            .setOnClickListener { exit() }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * «Avslutt» betyr slutt, ikke «skjul»: posisjonssporingen stoppes og varselet forsvinner.
     *
     * Uten en slik knapp var eneste vei ut å starte en annen navigasjonsapp, og da lever
     * foreground-servicen videre - appen leser posisjon, og det vedvarende varselet står, uten at
     * noe på skjermen forklarer hvorfor. En app som leser GPS må kunne skrus av der den vises.
     *
     * [stopTracking] først: `finish()` river skjermen, og da er det ikke lenger opplagt at
     * livssyklusen rekker å rydde etter oss.
     */
    private fun exit() {
        stopTracking()
        finish()
    }

    private fun requestPermission() {
        carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
            if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startTracking()
                invalidate()
            }
        }
    }
}
