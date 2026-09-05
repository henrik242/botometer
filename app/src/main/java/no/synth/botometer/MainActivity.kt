package no.synth.botometer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.fine.TableStatus
import no.synth.botometer.speed.GpsSpeedSource

/**
 * Telefon-delen er viktigere når appen sideloades enn når den kommer fra Play: den er både
 * oppsettveiviser, oppdateringskanal for satsene og eneste diagnostikkflate. Brukeren er sin
 * egen support, og har ingen Play Console å lese crashlogger i.
 */
class MainActivity : ComponentActivity() {

    private lateinit var tables: FineTableRepository
    private lateinit var status: TextView

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

        status = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)   // så brukeren kan lime diagnostikken inn i en issue
        }

        val refresh = Button(this).apply {
            text = getString(R.string.refresh_rates)
            setOnClickListener { refreshRates(this) }
        }

        // Teksten er selectable, men å markere fem skjermhøyder med diagnostikk på en telefon
        // er en øvelse ingen orker å gjøre riktig. Én knapp, hele rapporten, klar til å limes
        // inn i en issue.
        val copy = Button(this).apply {
            text = getString(R.string.copy_diagnostics)
            setOnClickListener { copyDiagnostics() }
        }

        val alwaysAllow = Button(this).apply {
            text = getString(R.string.always_allow_location)
            setOnClickListener { openLocationSettings() }
        }

        val clearCrash = Button(this).apply {
            text = getString(R.string.clear_crash_log)
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                render()
            }
        }

        val speedometer = Button(this).apply {
            text = getString(R.string.open_phone_speedometer)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PhoneSpeedometerActivity::class.java))
            }
        }

        val permissions = Button(this).apply {
            text = getString(R.string.grant_permissions)
            setOnClickListener { askPermissions() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(copy)
            addView(alwaysAllow)
            addView(clearCrash)
            addView(speedometer)
            addView(refresh)
            addView(permissions)
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

    private fun render() {
        val t = tables.load()
        status.text = buildString {
            // versionCode er antall commits. Er den lavere enn commiten du tror du tester,
            // ligger det en gammel APK på telefonen og alt under er målt på feil kode.
            appendLine("Botometer ${BuildConfig.VERSION_NAME}")
            appendLine("versionCode ${BuildConfig.VERSION_CODE}")
            appendLine()

            // Øverst, ikke nederst: har appen krasjet, er det det viktigste på skjermen.
            CrashLog.read(this@MainActivity)?.let { crash ->
                appendLine("⚠ SISTE KRASJ")
                appendLine(crash.take(CrashLog.MAX_SHOWN))
                appendLine()
            }

            appendLine("ANDROID AUTO")
            append(CarSetupDiagnostics.summary(this@MainActivity))
            appendLine()

            appendLine("BØTESATSER")
            appendLine("Versjon: ${t.table.versjon}")
            appendLine("Kilde: " + if (t.origin == TableStatus.Origin.DOWNLOADED) "hentet fra nett" else "innebygd i APK-en")
            t.ageDays?.let { appendLine("Alder: $it dager") }
            if (t.stale) {
                appendLine()
                appendLine("⚠ Satsene er over ett år gamle og kan være justert siden. Trykk «Oppdater satser», eller bygg appen på nytt fra siste commit.")
            }
            appendLine()
            appendLine(t.table.hjemmel)
            appendLine()

            appendLine("TILGANGER")
            appendLine("Posisjon: " + if (GpsSpeedSource.hasLocationPermission(this@MainActivity)) "gitt" else "MANGLER - appen viser ingen fart")
            // «Gitt» er ikke nok i seg selv: er den gitt som «mens appen er i bruk», får ikke
            // bilskjermen fart, og feilen viser seg først i bilen.
            if (!GpsSpeedSource.hasBackgroundLocationPermission(this@MainActivity)) {
                appendLine("Bakgrunn: MANGLER - bilskjermen får ingen fart")
                appendLine("  Trykk «${getString(R.string.always_allow_location)}» og velg")
                appendLine("  «Tillat alltid». Fra Android 11 finnes ikke det valget i")
                appendLine("  tillatelsesdialogen, bare på innstillingssiden.")
            } else {
                appendLine("Bakgrunn: gitt")
            }
            appendLine("Varsler: " +
                if (NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) "gitt"
                else "MANGLER - se «FARTSVARSLER I BILEN» under")
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
    }

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
        clipboard.setPrimaryClip(ClipData.newPlainText("Botometer-diagnostikk", status.text))
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
     * så vi sender dem dit i stedet.
     */
    private fun openLocationSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
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
