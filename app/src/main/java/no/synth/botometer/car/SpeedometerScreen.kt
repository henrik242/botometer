package no.synth.botometer.car

import android.Manifest
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
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
import no.synth.botometer.BotometerApp
import no.synth.botometer.Diagnostics
import no.synth.botometer.R
import no.synth.botometer.Settings
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.SpeedFeed
import no.synth.botometer.speed.Tracking
import no.synth.botometer.speed.TrackingHolders

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
    // GPS-friskhet, manuell fartsgrense og hysterese på ett sted - og nå faktisk SAMME
    // eksemplar, ikke bare samme klasse. Tre kopier betydde at appen kunne varsle om en bot den
    // ikke viste.
    //
    // Satsene leses fra disk-cachen om den finnes, ellers asseten. Ingen nettverk i bilen -
    // satsene oppdateres fra telefon-appen, og slår gjennom her uten omstart.
    private val app = carContext.applicationContext as BotometerApp

    private val renderer = SpeedometerRenderer(
        calculator = { app.speedWatch.calculator },
        ratesStale = { app.speedWatch.ratesStale },
        darkMode = { carContext.isDarkMode },
    )

    private var collectJob: Job? = null
    private var feedJob: Job? = null

    /**
     * Satt når fixene slutter å komme OG ingen holder på sporingen lenger - altså «Avslutt» et
     * annet sted, typisk telefonen. Et flagg og ikke et oppslag i [Tracking] fra
     * [onGetTemplate]: verten kan be om templaten før [onStart] har rukket å ta holderen, og da
     * ville skjermen meldt «stoppet» i det den startet.
     */
    private var trackingStopped = false

    private val settings = Settings(carContext)

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
        surfaceCallback(renderer)
        startTracking()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopDrawing()
        // Må nullstilles, ellers tegner rendereren mot en død surface neste gang skjermen vises.
        surfaceCallback(null)
    }

    /**
     * `setSurfaceCallback` er et kall over til verten, og dokumentasjonen lover både
     * SecurityException og HostException. [onStop] kjører nettopp mens verten rives ned - ved
     * frakobling eller om verten krasjer - og en død binder der ville tatt appen med seg under
     * opprydding. Det er ingenting å redde i den situasjonen uansett.
     */
    private fun surfaceCallback(callback: SurfaceCallback?) {
        runCatching {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        }.onFailure { Diagnostics.carSurfaceFailed(it.message ?: it.javaClass.simpleName) }
    }

    private fun startTracking() {
        if (!GpsSpeedSource.hasLocationPermission(carContext)) return

        // Holderen er CAR fordi det er ØKTA som eier sporingen, ikke denne skjermen.
        // [BotometerSession] slipper den igjen når økta dør.
        trackingStopped = false
        Tracking.acquire(carContext, TrackingHolders.Holder.CAR)

        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            app.readings.collect { renderer.update(it) }
        }

        // Slutter fixene å komme - hovedbryteren slått av på telefonen, eller «Avslutt» der -
        // står speedometeret igjen med siste frame. Et tall som ikke lenger gjelder ser ut som
        // et tall som gjelder, så vi ber om templaten på nytt og lar den si hva som skjedde.
        feedJob?.cancel()
        feedJob = lifecycleScope.launch {
            var wasStopped = trackingStopped
            SpeedFeed.fixes.collect { fix ->
                // Begge retninger. Bare null-kanten var halve jobben: slo brukeren hovedbryteren
                // av og på igjen, sto bilskjermen på «Avslått» mens fixene strømmet inn bak den,
                // helt til verten tilfeldigvis ba om templaten på nytt.
                val stopped = fix == null && !Tracking.isActive
                trackingStopped = stopped
                if (stopped != wasStopped) {
                    wasStopped = stopped
                    invalidate()
                }
            }
        }
    }

    /**
     * Bare tegningen. Sporingen holdes av økta, ikke av denne skjermen.
     *
     * Verten stopper skjermen vår i det Google Maps tar over bilskjermen. Slapp vi sporingen
     * her, døde fartsvarslene i samme øyeblikk som de ble den eneste flaten appen hadde igjen.
     * [BotometerSession] slipper CAR-holderen når økta dør, og hovedbryteren i telefon-appen
     * slår av alt sammen for den som ikke vil at appen leser posisjon i det hele tatt.
     */
    private fun stopDrawing() {
        collectJob?.cancel()
        collectJob = null
        feedJob?.cancel()
        feedJob = null
    }

    override fun onGetTemplate(): Template {
        // Hovedbryteren er slått av på telefonen. Da skal bilskjermen si det, ikke stå med et
        // tomt speedometer som ser ut som en app uten GPS-dekning.
        if (!settings.enabled) {
            return MessageTemplate.Builder(
                "Botometer er slått av. Slå den på igjen i appen på telefonen."
            )
                .setTitle("Avslått")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        // Sporingen er stoppet et annet sted. Å stå med et frosset speedometer ville vært å
        // vise en fart bilen ikke har - og «Start på nytt» er kortere enn å finne telefonen.
        if (trackingStopped) {
            return MessageTemplate.Builder(
                "Sporingen er stoppet. Botometer ble avsluttet på telefonen."
            )
                .setTitle("Stoppet")
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Start på nytt")
                        .setOnClickListener {
                            startTracking()
                            invalidate()
                        }
                        .build()
                )
                .build()
        }

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
                                        append("Satser pr. ${app.speedWatch.calculator.version}")
                                        if (app.speedWatch.ratesStale) append(" (UTDATERT - oppdater i telefon-appen)")
                                        append(". Anslag - ikke juridisk bindende. Fartsgrenser: Statens vegvesen (NLOD 2.0).")
                                    },
                                    CarToast.LENGTH_LONG,
                                ).show()
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.exit))
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
     * [Tracking.stopAll] og ikke bare et slipp av vår egen holder: trykker du «Avslutt», mener
     * du slutt - også om telefonskjermen står og holder på sporingen et sted.
     */
    private fun exit() {
        stopDrawing()
        Tracking.stopAll()
        // `finish()` og ikke `carContext.finishCarApp()` var en stille no-op. Screen.finish()
        // går til ScreenManager.remove, og den sier det rett ut: «If this screen is the only
        // Screen in the stack, it will not be removed.» Denne skjermen ER den eneste - økta
        // returnerer den fra onCreateScreen og vi pusher aldri noe over den.
        //
        // Resultatet var at «Avslutt» stoppet sporingen og lot skjermen stå igjen med siste
        // frame: en fart og et beløp som ikke lenger gjaldt, uten noen vei ut. onStop kjørte
        // heller aldri, så surface-callbacken ble aldri koblet fra.
        carContext.finishCarApp()
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
