package no.synth.botometer

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.botometer.alert.ReadingText
import no.synth.botometer.alert.Severity
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.alert.SpeedingAlerts
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.fine.TableStatus
import no.synth.botometer.limit.ManualLimit
import no.synth.botometer.limit.MatchConfidence
import no.synth.botometer.speed.FixFreshness
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.SpeedFeed
import no.synth.botometer.speed.Tracking
import no.synth.botometer.speed.TrackingHolders

/**
 * Telefon-delen er viktigere når appen sideloades enn når den kommer fra Play: den er både
 * oppsettveiviser, oppdateringskanal for satsene, innstillingsside og eneste diagnostikkflate.
 * Brukeren er sin egen support, og har ingen Play Console å lese crashlogger i.
 *
 * Skjermen var lenge én stor tekstblokk med sju knapper under. Alt sto der, men det viktigste -
 * «virker det?» - måtte leses ut av fem skjermhøyder diagnostikk, og knapper for problemer du
 * ikke hadde sto side om side med dem du trengte. Nå er rekkefølgen: svaret først, så det som
 * må fikses, så det du kan gjøre, og diagnostikken bak én knapp. Kopieringen tar hele rapporten
 * uansett om den vises.
 *
 * **Og speedometeret er her.** Det lå lenge i en egen aktivitet med en knapp for å komme dit, og
 * det var en skjerm for mye: den viste de samme tallene fra det samme [SpeedWatch], og det du
 * kommer til appen for skal ikke ligge bak en knapp. Nå starter denne skjermen sporingen mens
 * den er synlig, holder skjermen våken, og lar fartsgrensen settes for hånd - alt telefonen
 * skal kunne, på den ene flaten telefonen har.
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: BotometerApp
    private lateinit var tables: FineTableRepository
    private lateinit var settings: Settings

    // Leses fra ressursene, ikke hardkodet: values-night/ gir dem mørk variant, og uten det var
    // skjermen hvit også om natta - en lyskaster i kupeen foran frontruta.
    private val primary by lazy { ContextCompat.getColor(this, R.color.text) }
    private val secondary by lazy { ContextCompat.getColor(this, R.color.text_secondary) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.muted) }

    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var speedCard: LinearLayout
    private lateinit var speedValue: TextView
    private lateinit var speedFine: TextView
    private lateinit var speedDetail: TextView
    private lateinit var manualRow: LinearLayout
    private lateinit var problems: TextView
    private lateinit var diagnostics: TextView
    private lateinit var diagnosticsToggle: Button
    private lateinit var fixPermissions: Button
    private lateinit var fixBackground: Button
    private lateinit var clearCrash: Button

    /** Hele rapporten, også når den er skjult: «Kopier diagnostikk» skal alltid ta med alt. */
    private var report: String = ""

    /**
     * Siste avlesning, så [render] kan tegne kortet på nytt uten å vente på neste fix. Uten den
     * ble hovedbryteren stående og vise fart i et sekund etter at den var slått av.
     */
    private var lastReading: SpeedWatch.Reading? = null

    /**
     * Tilbake-knappen trykket sist, på monoton klokke. 0 = ikke trykket i denne runden.
     */
    private var lastBackPressAt = 0L

    /**
     * «Trykk to ganger for å avslutte», slik alle andre gjør det.
     *
     * Ett trykk kan ikke avslutte her, for avslutt betyr slutt: [Tracking.stopAll] stopper
     * posisjonssporingen og fjerner det vedvarende varselet, også bilens holdere. Et uhell med
     * tommelen skal ikke slå av fartsvarslene midt i en kjøretur.
     *
     * Det første trykket gjør derfor ingenting annet enn å si fra. Vil du bare legge appen bort
     * uten å stoppe sporingen, er det hjem-knappen - da fortsetter farten og varslene, som de
     * skal når du bytter til Maps på telefonen.
     */
    private val doubleBackToExit = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPressAt < DOUBLE_BACK_WINDOW_MS) {
                exit()
                return
            }
            lastBackPressAt = now
            Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Slutt, ikke «skjul». Samme betydning som «Avslutt» på bilskjermen, og samme grunn: en app
     * som leser GPS må kunne skrus av der den vises, og et vedvarende posisjonsvarsel som blir
     * stående etter at du forlot appen er noe brukeren ikke har bedt om.
     *
     * Hovedbryteren røres ikke. Neste gang du åpner appen sporer den igjen - dette er slutt på
     * denne turen, ikke en innstilling.
     */
    private fun exit() {
        Tracking.stopAll()
        doubleBackToExit.isEnabled = false
        finish()
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Sporingen skal starte i det tilgangen kommer, ikke ved neste oppstart av appen.
        startTracking()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as BotometerApp
        tables = app.createFineTableRepository()
        settings = Settings(this)

        headline = TextView(this).apply { textSize = 26f }
        subline = TextView(this).apply {
            textSize = 14f
            setTextColor(secondary)
        }
        problems = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        // Selve speedometeret. Stort nok til å leses i periferien fra førersetet, uten
        // animasjon: det skal kunne sees i et øyekast, ikke studeres.
        speedValue = TextView(this).apply {
            textSize = 64f
            setTypeface(null, Typeface.BOLD)
        }
        speedFine = TextView(this).apply { textSize = 26f }
        speedDetail = TextView(this).apply {
            textSize = 13f
            setTextColor(secondary)
        }
        manualRow = buildManualRow()
        speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(speedValue)
            addView(speedFine)
            addView(speedDetail)
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(manualRow)
                }
            )
        }

        diagnostics = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)   // så brukeren kan lime rapporten inn i en issue
            visibility = View.GONE
        }

        // Knappene som bare gir mening når noe er galt. De skjules ellers - en knapp for et
        // problem du ikke har er støy som gjør de ekte knappene vanskeligere å finne.
        fixPermissions = Button(this).apply {
            text = getString(R.string.grant_permissions)
            setOnClickListener { askPermissions() }
        }
        fixBackground = Button(this).apply {
            text = getString(R.string.always_allow_location)
            setOnClickListener { showBackgroundDisclosure() }
        }
        clearCrash = Button(this).apply {
            text = getString(R.string.clear_crash_log)
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                render()
            }
        }

        diagnosticsToggle = Button(this).apply {
            setOnClickListener {
                diagnostics.visibility =
                    if (diagnostics.visibility == View.GONE) View.VISIBLE else View.GONE
                renderDiagnosticsToggle()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(headline)
            addView(subline)
            addView(speedCard)

            addView(problems)
            addView(fixPermissions)
            addView(fixBackground)
            addView(clearCrash)

            addView(heading(getString(R.string.settings_heading)))
            // Hovedbryteren først. Den står over de to andre - er den av, betyr de ingenting -
            // og en av-bryter som ligger nederst er en av-bryter man ikke finner.
            addView(
                toggle(
                    R.string.setting_enabled,
                    R.string.setting_enabled_help,
                    settings.enabled,
                ) { enabled ->
                    settings.enabled = enabled
                    // Bryteren skal virke i det den vippes, ikke ved neste oppstart. Av betyr
                    // av for alle flatene, også bil-økta: den som slår av appen på telefonen
                    // mens den står og sporer for bilskjermen, mener den som står og sporer.
                    if (enabled) {
                        startTracking()
                        // Holderen for bilforbindelsen ble avvist mens appen var av, og
                        // CarConnection leverer bare ved endring - uten dette var appen uten
                        // den resten av turen.
                        (application as BotometerApp).applyCarConnectionHolder()
                    } else {
                        Tracking.stopAll()
                    }
                    updateKeepScreenOn()
                    lastReading = null
                }
            )
            addView(
                toggle(
                    R.string.setting_phone_alerts,
                    R.string.setting_phone_alerts_help,
                    settings.phoneAlerts,
                ) { settings.phoneAlerts = it }
            )
            addView(
                toggle(
                    R.string.setting_car_alerts,
                    R.string.setting_car_alerts_help,
                    settings.carAlerts,
                ) { settings.carAlerts = it }
            )

            addView(gap())
            addView(
                row(
                    Button(this@MainActivity).apply {
                        text = getString(R.string.send_test_alert)
                        setOnClickListener {
                            SpeedingAlerts(this@MainActivity).sendTestAlert()
                            render()
                        }
                    },
                    Button(this@MainActivity).apply {
                        text = getString(R.string.refresh_rates)
                        setOnClickListener { refreshRates(this) }
                    },
                )
            )
            addView(
                row(
                    diagnosticsToggle,
                    Button(this@MainActivity).apply {
                        text = getString(R.string.copy_diagnostics)
                        setOnClickListener { copyDiagnostics() }
                    },
                )
            )
            // Versjonslinja hører til her, ikke øverst. Den er diagnostikk - `copyDiagnostics`
            // setter den allerede først i rapporten - og øverst kostet den den første linja du
            // leser når du åpner appen på et rødt lys.
            addView(versionLine())
            addView(diagnostics)
        }

        // Fra targetSdk 36 tegner appen alltid under status- og navigasjonsfeltet;
        // windowOptOutEdgeToEdgeEnforcement er avviklet, så det finnes ikke lenger en vei ut.
        // Uten dette havner «Botometer <versjon>» bak klokka og knappene bak navigasjonslinja.
        // Systemets innrykk legges oppå vårt eget, ikke i stedet for det.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(dp(16) + bars.left, dp(20) + bars.top, dp(16) + bars.right, dp(20) + bars.bottom)
            insets
        }

        setContentView(ScrollView(this).apply { addView(content) })

        // Sier om telefonen i det hele tatt tror den er koblet til en bil. Er den ikke det,
        // vises et fartsvarsel bare på telefonen - og det er hele forklaringen.
        //
        // Leses fra [BotometerApp], som allerede abonnerer prosessvidt. Et eget abonnement her
        // ville vært det samme svaret hentet to ganger, og det ene som døde med aktiviteten.
        runCatching {
            CarConnection(this).type.observe(this) { render() }
        }.onFailure { Diagnostics.carConnectionFailed(it.message ?: it.javaClass.simpleName) }

        onBackPressedDispatcher.addCallback(this, doubleBackToExit)

        observeSpeed()
        // Bare første gang, og bare ved en ekte oppstart. Dialogen sto tidligere over en skjerm
        // der det eneste synlige var et byggnummer, hver eneste gang appen ble åpnet - før
        // brukeren hadde lest et ord om hva Botometer er. Etterpå er det knappen som spør.
        if (savedInstanceState == null && !settings.hasAskedForPermissions) askPermissions()
        refreshIfStale()
    }

    /**
     * Kortet leser den DELTE strømmen - samme regnestykke, samme eksemplar, som bilskjermen og
     * fartsvarslene. Selve sporingen slås på i [onStart] og av i [onStop], så skjermen både
     * viser farten og er grunnen til at den finnes, så lenge den er framme.
     *
     * Regnestykket kjørte før i `lifecycleScope`, altså på hovedtråden, og kart-matchet hvert
     * fix på nytt for denne skjermen alene. Nå kommer avlesningen ferdig regnet.
     */
    private fun observeSpeed() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.readings.collect {
                    lastReading = it
                    renderSpeed(it)
                }
            }
        }
        // Stopper sporingen, slutter fixene å komme, og kortet ville blitt stående med et tall
        // som ikke lenger gjelder. `null` er signalet om at tjenesten la seg ned.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SpeedFeed.fixes.collect {
                    if (it == null) {
                        lastReading = null
                        renderSpeed(null)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startTracking()
        updateKeepScreenOn()
    }

    /**
     * Slipper bare telefonens egen holder. Er bil-økta i gang, sporer den videre - ellers ville
     * et bytte til Maps på telefonen slått av fartsvarslene i bilen.
     */
    override fun onStop() {
        super.onStop()
        Tracking.release(TrackingHolders.Holder.PHONE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startTracking() {
        if (!GpsSpeedSource.hasLocationPermission(this)) return
        // [Tracking] sjekker hovedbryteren selv, så den trenger ikke sjekkes her også.
        Tracking.acquire(this, TrackingHolders.Holder.PHONE)
    }

    /**
     * Skjermen skal ikke slukke midt i en kjøretur - men bare når den faktisk viser noe. Er
     * appen slått av, er dette en innstillingsside, og en innstillingsside har ingen grunn til
     * å holde telefonen våken.
     */
    private fun updateKeepScreenOn() {
        if (settings.enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * «Auto» først, så de skiltede grensene. NVDB dekker ikke alt - private veger, ny veg,
     * strekninger der fartsgrenseobjektet mangler - og der viste appen «Ukjent grense» og
     * sluttet å regne. Et anslag brukeren selv står for er bedre enn ingenting.
     *
     * Bare telefonen kan sette den. På bilskjermen ville en rad med knapper vært en invitasjon
     * til å fikle under kjøring, og grensen leses uansett fra det samme [ManualLimit] der.
     */
    private fun buildManualRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(manualButton(null))
        ManualLimit.choices.forEach { addView(manualButton(it)) }
    }

    /**
     * `minWidth = 0` strippet plattformens minstebredde og ga treffområder på rundt 40dp -
     * under retningslinjenes 48dp, truffet med én hånd i en bil i bevegelse. Nå settes 48dp
     * eksplisitt i stedet for å fjerne grensen.
     */
    private fun manualButton(kmt: Int?) = Button(this).apply {
        text = kmt?.toString() ?: getString(R.string.manual_limit_auto)
        textSize = 14f
        minWidth = dp(48)
        minimumWidth = dp(48)
        setOnClickListener {
            ManualLimit.set(kmt)
            highlightManual()
        }
    }

    /** Den valgte grensen må være synlig, ellers vet du ikke hva tallene på skjermen bygger på. */
    private fun highlightManual() {
        val active = ManualLimit.kmt.value
        for (i in 0 until manualRow.childCount) {
            val button = manualRow.getChildAt(i) as? Button ?: continue
            val selected = if (active == null) {
                button.text.toString() == getString(R.string.manual_limit_auto)
            } else {
                button.text.toString() == active.toString()
            }
            // Farge alene bar valget, på 2,94:1 mot bakgrunnen. Hvilken grense som gjelder
            // endrer betydningen av hvert tall over den, så den skal ikke være så vidt synlig.
            button.setTextColor(if (selected) primary else muted)
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * @param reading null når det ikke finnes noe fix å vise - appen er slått av, tilgangen
     * mangler, eller GPS-en har ikke svart ennå. De tre er ikke det samme, og kortet skal si
     * hvilken av dem det er: «--» uten forklaring er en app som ser ødelagt ut.
     */
    private fun renderSpeed(reading: SpeedWatch.Reading?) {
        highlightManual()
        manualRow.visibility = if (settings.enabled) View.VISIBLE else View.GONE

        if (reading == null) {
            speedValue.setTextColor(muted)
            speedValue.text = "--"
            speedFine.setTextColor(muted)
            speedFine.text = when {
                !settings.enabled -> "Slått av"
                // «Ingen posisjon» leste som «GPS-en har ikke fix», altså nabotilstanden én
                // linje ned. To forskjellige feil kan ikke ha nesten samme ord.
                !GpsSpeedSource.hasLocationPermission(this) -> "Mangler tilgang"
                else -> "Venter på GPS"
            }
            speedDetail.text = when {
                !settings.enabled -> "Slå på Botometer under Innstillinger lenger ned"
                !GpsSpeedSource.hasLocationPermission(this) ->
                    "Gi Botometer tilgang til posisjon for å måle fart"
                else -> "Sporingen går – første posisjon kan ta noen sekunder"
            }
            return
        }

        val accent = accentFor(ReadingText.severity(reading))

        speedValue.setTextColor(accent)
        speedValue.text = ReadingText.speed(reading)

        speedFine.setTextColor(accent)
        speedFine.text = ReadingText.headline(reading)

        speedDetail.text = buildString {
            when (reading.freshness) {
                // Signalet først: alt annet på kortet bygger på et fix.
                FixFreshness.LOST -> {
                    append("Ingen GPS")
                    reading.limitKmt?.let { append(" · fartsgrensen $it km/t gjelder fortsatt") }
                }
                FixFreshness.STALE -> append("Mistet GPS-signalet – venter")
                FixFreshness.FRESH -> {
                    append("km/t")
                    reading.limitKmt?.let { append("  ·  grense $it") }
                    if (reading.manualLimit) append(" (manuell)")
                    // Gamle data skal se gamle ut, ikke bare være det.
                    if (reading.match?.stale == true) append(" (gammel)")
                    if (!reading.manualLimit && reading.match?.confidence == MatchConfidence.LOW) {
                        append(" (usikkert vegvalg)")
                    }
                    ReadingText.consequences(reading, separator = "  ·  ")
                        .takeIf { it.isNotEmpty() }
                        ?.let { append("  ·  $it") }
                }
            }
        }
    }

    /**
     * Telefonpaletten. Mørk tekst på lys bunn, så den kan ikke være bilskjermens: de fargene er
     * valgt mot svart og lyser opp. Alle ligger på minst 4,5:1 mot hvitt - den gamle gule på
     * 190,150,20 lå på 2,78:1, altså det minst lesbare på skjermen, og den markerte nettopp den
     * tilstanden du fortsatt kan gjøre noe med.
     */
    private fun accentFor(severity: Severity): Int = when (severity) {
        Severity.LOST, Severity.UNKNOWN -> ContextCompat.getColor(this, R.color.muted)
        Severity.NONE -> ContextCompat.getColor(this, R.color.ok)
        Severity.FINE -> ContextCompat.getColor(this, R.color.fine)
        Severity.POINTS -> ContextCompat.getColor(this, R.color.points)
        Severity.PROSECUTION -> ContextCompat.getColor(this, R.color.prosecution)
    }

    override fun onResume() {
        super.onResume()
        // Et halvferdig dobbelttrykk skal ikke ligge og vente. Kommer du tilbake til appen
        // senere, er neste tilbake-trykk det første igjen.
        lastBackPressAt = 0L
        render()
    }

    // ---- oppbygging ------------------------------------------------------------------------

    private fun versionLine() = TextView(this).apply {
        textSize = 12f
        setTextColor(secondary)
        setTextIsSelectable(true)
        // versionCode er antall commits. Er den lavere enn commiten du tror du tester, ligger
        // det en gammel APK på telefonen og alt annet er målt på feil kode.
        text = "Botometer ${BuildConfig.VERSION_NAME} · versionCode ${BuildConfig.VERSION_CODE}"
    }

    private fun heading(title: String) = TextView(this).apply {
        textSize = 13f
        setTextColor(secondary)
        setPadding(0, dp(16), 0, 0)
        text = title
    }

    private fun gap() = TextView(this).apply { textSize = 6f; text = "" }

    /**
     * `setPadding` tar PIKSLER. Alle avstandene var hardkodede pikselverdier, så sidemargen var
     * 16dp på en 3x-telefon og 12dp på en 4x - teksten krøp mot kanten nettopp på skjermene med
     * flest piksler å ta av. Tekststørrelsene var aldri rammet; `setTextSize(float)` er sp.
     */
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Bryter med forklaring under. Forklaringen er ikke pynt: alle tre koster noe. */
    private fun toggle(
        titleRes: Int,
        helpRes: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            Switch(this@MainActivity).apply {
                text = getString(titleRes)
                textSize = 16f
                isChecked = initial
                setOnCheckedChangeListener { _, checked ->
                    onChange(checked)
                    render()
                }
            }
        )
        addView(
            TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(secondary)
                text = getString(helpRes)
            }
        )
    }

    /** To knapper ved siden av hverandre. Sju knapper i én kolonne er en liste, ikke et valg. */
    private fun row(left: Button, right: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ---- innhold ---------------------------------------------------------------------------

    private fun render() {
        val t = tables.load()
        val crash = CrashLog.read(this)
        val location = GpsSpeedSource.hasLocationPermission(this)
        val background = GpsSpeedSource.hasBackgroundLocationPermission(this)
        val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()

        // Ett svar på «virker det?», øverst, i klartekst. Rangert etter hva som stopper mest.
        val trouble = buildList {
            if (!location && permissionsAreStuck()) {
                add("Posisjonstilgang er avslått – gi den i Innstillinger")
            } else if (!location) {
                add("Posisjonstilgang mangler – appen viser ingen fart")
            }
            if (location && !background) add("«Tillat alltid» mangler - bilskjermen får ingen fart")
            if (!notifications) add("Varsler er slått av - ingen fartsvarsler noe sted")
            if (t.stale) add("Bøtesatsene er over ett år gamle")
            if (crash != null) add("Appen har krasjet - detaljer i diagnostikken")
        }

        // Tre tilstander, ikke to. En app du selv har slått av «trenger» ikke oppsett, og å bli
        // irettesatt i oransje for sitt eget valg er å bli fortalt at man gjorde noe galt.
        headline.text = when {
            !settings.enabled -> "Slått av"
            trouble.isEmpty() -> "Klar"
            else -> "Trenger oppsett"
        }
        headline.setTextColor(
            when {
                !settings.enabled -> muted
                trouble.isEmpty() -> ContextCompat.getColor(this, R.color.ok)
                else -> ContextCompat.getColor(this, R.color.points)
            }
        )
        // Satsversjonen står her uansett, også når noe er galt: den er svaret på «hvilke tall
        // regner appen med nå», og et automatisk oppdatert sett skal være synlig med én gang -
        // ikke gjemt bak diagnostikken.
        subline.text = when {
            !settings.enabled -> "Ingen sporing og ingen varsler · satser ${t.table.versjon}"
            trouble.isEmpty() -> "Satser ${t.table.versjon} · fartsgrenser fra Vegvesenet (NVDB)"
            // «1 ting å ordne» er ikke norsk.
            trouble.size == 1 -> "Én ting å ordne · satser ${t.table.versjon}"
            else -> "${trouble.size} ting å ordne · satser ${t.table.versjon}"
        }

        problems.text = trouble.joinToString("\n") { "⚠ $it" }
        problems.visibility = if (trouble.isEmpty()) View.GONE else View.VISIBLE

        // Fikseknappene følger problemene, ikke omvendt.
        fixPermissions.visibility = if (!location || !notifications) View.VISIBLE else View.GONE
        fixPermissions.text = getString(
            if (permissionsAreStuck()) R.string.open_app_settings else R.string.grant_permissions
        )
        fixBackground.visibility = if (location && !background) View.VISIBLE else View.GONE
        clearCrash.visibility = if (crash != null) View.VISIBLE else View.GONE

        renderSpeed(lastReading)

        report = buildReport(t, crash, location, background, notifications)
        diagnostics.text = report
        renderDiagnosticsToggle()
    }

    private fun renderDiagnosticsToggle() {
        diagnosticsToggle.text = getString(
            if (diagnostics.visibility == View.GONE) R.string.show_diagnostics
            else R.string.hide_diagnostics
        )
    }

    /**
     * Hele rapporten, uavhengig av hva som vises på skjermen. Den er skrevet for å limes inn et
     * annet sted, så den gjentar gjerne det som allerede står øverst.
     */
    private fun buildReport(
        t: TableStatus,
        crash: String?,
        location: Boolean,
        background: Boolean,
        notifications: Boolean,
    ): String = buildString {
        appendLine("Botometer ${BuildConfig.VERSION_NAME}")
        appendLine("versionCode ${BuildConfig.VERSION_CODE}")
        appendLine()

        // Øverst, ikke nederst: har appen krasjet, er det det viktigste i rapporten.
        crash?.let {
            appendLine("⚠ SISTE KRASJ")
            appendLine(it.take(CrashLog.MAX_SHOWN))
            appendLine()
        }

        appendLine("INNSTILLINGER")
        append(settings.summary())
        appendLine()

        appendLine("ANDROID AUTO")
        append(CarSetupDiagnostics.summary(this@MainActivity))
        appendLine()

        appendLine("BØTESATSER")
        appendLine("Versjon: ${t.table.versjon}")
        appendLine("Kilde: " + if (t.origin == TableStatus.Origin.DOWNLOADED) "hentet fra nett" else "innebygd i APK-en")
        t.ageDays?.let { appendLine("Alder: $it dager") }
        if (t.stale) {
            appendLine("⚠ Over ett år gamle og kan være justert siden. Trykk «Oppdater satser».")
        }
        appendLine(t.table.hjemmel)
        appendLine()

        appendLine("TILGANGER")
        appendLine("Posisjon: " + if (location) "gitt" else "MANGLER - appen viser ingen fart")
        // «Gitt» er ikke nok i seg selv: er den gitt som «mens appen er i bruk», får ikke
        // bilskjermen fart, og feilen viser seg først i bilen.
        if (!background) {
            appendLine("Bakgrunn: MANGLER - bilskjermen får ingen fart")
            appendLine("  Trykk «${getString(R.string.always_allow_location)}» og velg")
            appendLine("  «Tillat alltid». Fra Android 11 finnes ikke det valget i")
            appendLine("  tillatelsesdialogen, bare på innstillingssiden.")
        } else {
            appendLine("Bakgrunn: gitt")
        }
        appendLine("Varsler: " + if (notifications) "gitt" else "MANGLER")
        appendLine()

        appendLine("FARTSVARSLER I BILEN")
        append(CarNotificationDiagnostics.summary(this@MainActivity, app.carConnectionType))
        appendLine()

        appendLine("DIAGNOSTIKK")
        append(Diagnostics.summary())
        appendLine()

        appendLine("Fartsgrenser: Statens vegvesen, NVDB (NLOD 2.0).")
        appendLine("Beløpene er anslag. Politiet legger målt fart minus sikkerhetsfradrag til grunn, og appen kjenner ikke variable eller midlertidig skiltede fartsgrenser.")
    }

    // ---- handlinger ------------------------------------------------------------------------

    /**
     * Hele rapporten, ordrett, med versjon øverst. Uten versjonen er en innliming ubrukelig:
     * halvparten av spørsmålene om en feil er «hvilken kode kjørte du».
     */
    private fun copyDiagnostics() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, "Fant ikke utklippstavla", Toast.LENGTH_LONG).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Botometer-diagnostikk", report))
        // Fra Android 13 viser systemet sin egen bekreftelse på kopiering. To bekreftelser er
        // en for mye.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Diagnostikk kopiert", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRates(button: View) {
        button.isEnabled = false
        lifecycleScope.launch {
            val result = tables.refresh()
            button.isEnabled = true
            result.fold(
                onSuccess = {
                    // Uten dette regner de tre flatene videre på den gamle tabellen til
                    // prosessen starter på nytt - og fartsvarslene gjorde det ut hele turen.
                    withContext(Dispatchers.IO) { app.speedWatch.reloadRates() }
                    Toast.makeText(this@MainActivity, "Satser: versjon ${it.table.versjon}", Toast.LENGTH_LONG).show()
                },
                onFailure = { Toast.makeText(this@MainActivity, "Kunne ikke hente satser: ${it.message}", Toast.LENGTH_LONG).show() },
            )
            render()
        }
    }

    /**
     * Knappen alene er en for svak oppdateringskanal: den forutsetter at brukeren vet at satsene
     * er ferskvare, og åpner telefon-appen for å sjekke. Her hentes de i stedet automatisk når
     * tabellen faktisk er utdatert - ikke ved hver oppstart, så en fersk tabell koster ingen
     * nettverkstrafikk.
     *
     * Feil håndteres stille. Brukeren har ikke bedt om noe, og advarselen i statusteksten står
     * uansett til satsene er oppdatert.
     */
    private fun refreshIfStale() {
        // Hovedbryteren er av, og den betyr av: da bruker appen heller ikke nett av seg selv.
        // «Oppdater satser» virker fortsatt - det er noe brukeren ber om der og da.
        if (!settings.enabled) return
        if (!tables.load().stale) return
        lifecycleScope.launch {
            if (tables.refresh().isSuccess) {
                withContext(Dispatchers.IO) { app.speedWatch.reloadRates() }
            }
            render()
        }
    }

    /**
     * Play krever en tydelig forklaring i appen før brukeren sendes til «Tillat alltid», og at
     * den sier hva posisjonen brukes til når appen ikke er i bruk. Innstillingssiden alene sier
     * ingenting om det.
     */
    private fun showBackgroundDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_disclosure_title)
            .setMessage(R.string.background_disclosure_text)
            .setPositiveButton(R.string.background_disclosure_continue) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.background_disclosure_cancel, null)
            .show()
    }

    /**
     * Fra Android 11 mangler «Tillat alltid» i tillatelsesdialogen; den finnes bare på appens
     * innstillingsside. En runtime-forespørsel her ville blitt avvist uten at brukeren så noe,
     * så vi sender dem dit i stedet. Samme side har varselinnstillingene.
     */
    private fun openAppSettings() {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Fant ikke innstillingssiden: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Etter to avslag er tillatelsen avslått permanent, og `requestPermissions` returnerer med
     * én gang uten å vise noe. «Gi tilganger» ble da en knapp som ikke gjorde noe i det hele
     * tatt, uten en eneste beskjed - og det er sluttilstanden for alle som avviste den første
     * dialogen i refleks. Er vi der, sendes brukeren til innstillingssiden i stedet.
     */
    private fun permissionsAreStuck(): Boolean =
        settings.hasAskedForPermissions &&
            !GpsSpeedSource.hasLocationPermission(this) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            )

    private fun askPermissions() {
        if (permissionsAreStuck()) {
            openAppSettings()
            return
        }
        settings.hasAskedForPermissions = true
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Fra API 33 skjules varselet til foreground-servicen uten denne. Servicen kjører
            // uansett, men et skjult varsel er dårlig gjennomsiktighet rundt posisjonssporing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermissions.launch(needed.toTypedArray())
    }

    private companion object {
        /** Kort nok til at to trykk er én handling, langt nok til å rekke å lese varselet. */
        const val DOUBLE_BACK_WINDOW_MS = 2_000L
    }
}
