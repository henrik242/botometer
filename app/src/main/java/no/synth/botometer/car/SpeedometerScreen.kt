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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import no.synth.botometer.R
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.limit.NvdbClient
import no.synth.botometer.limit.SpeedLimitRepository
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.LocationForegroundService
import no.synth.botometer.speed.SpeedFeed

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

    // Leser fra disk-cachen om den finnes, ellers asseten. Ingen nettverk i bilen - satsene
    // oppdateres fra telefon-appen.
    private val tableStatus = FineTableRepository(
        carContext, carContext.getString(R.string.satser_url)
    ).load()

    private val calculator = FineCalculator(tableStatus.table)
    private val renderer = SpeedometerRenderer(carContext, calculator, ratesStale = tableStatus.stale)

    private val repo = SpeedLimitRepository(
        nvdb = NvdbClient(clientName = carContext.getString(R.string.nvdb_client_id)),
        scope = lifecycleScope,
    )

    private var collectJob: Job? = null

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
            SpeedFeed.fixes.filterNotNull().collect { fix ->
                val match = repo.limitAt(fix.position, fix.headingDeg, fix.speedKmt)
                renderer.update(fix, match)
            }
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
                                        append("Satser pr. ${calculator.version}")
                                        if (tableStatus.stale) append(" (UTDATERT - oppdater i telefon-appen)")
                                        append(". Anslag - ikke juridisk bindende. Fartsgrenser: Statens vegvesen (NLOD 2.0).")
                                    },
                                    CarToast.LENGTH_LONG,
                                ).show()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
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
