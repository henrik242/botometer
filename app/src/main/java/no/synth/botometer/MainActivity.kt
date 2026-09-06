package no.synth.botometer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import no.synth.botometer.alert.SpeedingAlerts
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.fine.TableStatus
import no.synth.botometer.speed.GpsSpeedSource

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
 */
class MainActivity : ComponentActivity() {

    private lateinit var tables: FineTableRepository
    private lateinit var settings: Settings

    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var problems: TextView
    private lateinit var diagnostics: TextView
    private lateinit var diagnosticsToggle: Button
    private lateinit var fixPermissions: Button
    private lateinit var fixBackground: Button
    private lateinit var clearCrash: Button

    /** Hele rapporten, også når den er skjult: «Kopier diagnostikk» skal alltid ta med alt. */
    private var report: String = ""

    /**
     * Leses asynkront fra Android Auto. null til første svar kommer - og det skillet er verdt å
     * vise: «ikke lest ennå» og «ikke tilkoblet» er to helt forskjellige svar på hvorfor
     * bilskjermen ikke viser noe.
     */
    private var carConnectionType: Int? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tables = (application as BotometerApp).createFineTableRepository()
        settings = Settings(this)

        headline = TextView(this).apply { textSize = 26f }
        subline = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(120, 120, 125))
        }
        problems = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        diagnostics = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)   // så brukeren kan lime rapporten inn i en issue
            visibility = View.GONE
        }

        // Det man faktisk kommer hit for. Full bredde, øverst blant knappene.
        val speedometer = Button(this).apply {
            text = getString(R.string.open_phone_speedometer)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PhoneSpeedometerActivity::class.java))
            }
        }

        // Knappene som bare gir mening når noe er galt. De skjules ellers - en knapp for et
        // problem du ikke har er støy som gjør de ekte knappene vanskeligere å finne.
        fixPermissions = Button(this).apply {
            text = getString(R.string.grant_permissions)
            setOnClickListener { askPermissions() }
        }
        fixBackground = Button(this).apply {
            text = getString(R.string.always_allow_location)
            setOnClickListener { openAppSettings() }
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

            addView(versionLine())
            addView(headline)
            addView(subline)

            addView(problems)
            addView(fixPermissions)
            addView(fixBackground)
            addView(clearCrash)

            addView(gap())
            addView(speedometer)

            addView(heading(getString(R.string.settings_heading)))
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
            addView(
                toggle(
                    R.string.setting_background,
                    R.string.setting_background_help,
                    settings.backgroundTracking,
                ) { settings.backgroundTracking = it }
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
            view.setPadding(48 + bars.left, 64 + bars.top, 48 + bars.right, 64 + bars.bottom)
            insets
        }

        setContentView(ScrollView(this).apply { addView(content) })

        // Sier om telefonen i det hele tatt tror den er koblet til en bil. Er den ikke det,
        // vises et fartsvarsel bare på telefonen - og det er hele forklaringen.
        runCatching {
            CarConnection(this).type.observe(this) { type ->
                carConnectionType = type
                render()
            }
        }

        askPermissions()
        refreshIfStale()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---- oppbygging ------------------------------------------------------------------------

    private fun versionLine() = TextView(this).apply {
        textSize = 12f
        setTextColor(Color.rgb(120, 120, 125))
        setTextIsSelectable(true)
        // versionCode er antall commits. Er den lavere enn commiten du tror du tester, ligger
        // det en gammel APK på telefonen og alt annet er målt på feil kode.
        text = "Botometer ${BuildConfig.VERSION_NAME} · versionCode ${BuildConfig.VERSION_CODE}"
    }

    private fun heading(title: String) = TextView(this).apply {
        textSize = 13f
        setTextColor(Color.rgb(120, 120, 125))
        setPadding(0, 48, 0, 0)
        text = title
    }

    private fun gap() = TextView(this).apply { textSize = 6f; text = "" }

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
                setTextColor(Color.rgb(120, 120, 125))
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
            if (!location) add("Posisjonstilgang mangler - appen viser ingen fart")
            if (location && !background) add("«Tillat alltid» mangler - bilskjermen får ingen fart")
            if (!notifications) add("Varsler er slått av - ingen fartsvarsler noe sted")
            if (t.stale) add("Bøtesatsene er over ett år gamle")
            if (crash != null) add("Appen har krasjet - detaljer i diagnostikken")
        }

        headline.text = if (trouble.isEmpty()) "Klar" else "Trenger oppsett"
        headline.setTextColor(
            if (trouble.isEmpty()) Color.rgb(30, 140, 70) else Color.rgb(190, 110, 20)
        )
        subline.text = if (trouble.isEmpty()) {
            "Satser ${t.table.versjon} · fartsgrenser fra NVDB"
        } else {
            "${trouble.size} ting å ordne"
        }

        problems.text = trouble.joinToString("\n") { "⚠ $it" }
        problems.visibility = if (trouble.isEmpty()) View.GONE else View.VISIBLE

        // Fikseknappene følger problemene, ikke omvendt.
        fixPermissions.visibility = if (!location || !notifications) View.VISIBLE else View.GONE
        fixBackground.visibility = if (location && !background) View.VISIBLE else View.GONE
        clearCrash.visibility = if (crash != null) View.VISIBLE else View.GONE

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
        append(CarNotificationDiagnostics.summary(this@MainActivity, carConnectionType))
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
                onSuccess = { Toast.makeText(this@MainActivity, "Satser: versjon ${it.table.versjon}", Toast.LENGTH_LONG).show() },
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
        if (!tables.load().stale) return
        lifecycleScope.launch {
            tables.refresh()
            render()
        }
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

    private fun askPermissions() {
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
}
