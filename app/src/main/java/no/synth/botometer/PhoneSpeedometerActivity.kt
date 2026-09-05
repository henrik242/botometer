package no.synth.botometer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.limit.ManualLimit
import no.synth.botometer.limit.MatchConfidence
import no.synth.botometer.speed.FixFreshness
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.Tracking
import no.synth.botometer.speed.TrackingHolders

/**
 * Speedometeret på telefonskjermen, til bruk mens Google Maps eier bilskjermen.
 *
 * Bilverten kjører én navigasjonsapp om gangen, og Car App Library har ingen liten sideflate å
 * be om. Telefonen i holderen er den eneste flaten som faktisk kan stå ved siden av Maps, og den
 * krever verken Play-opplasting eller vertens velsignelse.
 *
 * Samme regnestykke og samme fargekoding som bilskjermen, men bevisst enklere: tre linjer, stor
 * skrift, ingen animasjon. Det skal kunne leses i periferien uten at blikket forlater veien.
 *
 * Her, og bare her, kan fartsgrensen settes for hånd. Telefonen er flaten som kan betjenes med
 * bilen i ro; på bilskjermen ville en rad med knapper vært en invitasjon til å fikle under
 * kjøring, og fartsgrensen der leses uansett fra det samme [ManualLimit].
 */
class PhoneSpeedometerActivity : ComponentActivity() {

    // Nullable, ikke lateinit: konstruksjonen leser satsfila fra disk, og det skal
    // ikke skje på hovedtråden mens skjermen skal tegnes.
    private var watch: SpeedWatch? = null
    private lateinit var speed: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var manualRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skjermen skal ikke slukke midt i en kjøretur. Den slås av igjen når aktiviteten
        // forlates, siden flagget følger vinduet.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speed = big(textSize = 96f)
        headline = big(textSize = 56f)
        detail = big(textSize = 18f, color = Color.rgb(170, 170, 175))
        manualRow = buildManualRow()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 12, 14))
            addView(speed)
            addView(headline)
            addView(detail)
            addView(
                HorizontalScrollView(this@PhoneSpeedometerActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(manualRow)
                }
            )
            addView(exitButton())
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom)
            insets
        }

        setContentView(content)
        render(null)

        lifecycleScope.launch {
            // Slutter å lese posisjon når skjermen ikke vises. En app som leser GPS i bakgrunnen
            // uten grunn er akkurat det foreground-servicen er der for å unngå.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val w = watch ?: withContext(Dispatchers.IO) {
                    SpeedWatch(this@PhoneSpeedometerActivity)
                }.also { watch = it }
                w.readings().collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (GpsSpeedSource.hasLocationPermission(this)) {
            Tracking.acquire(this, TrackingHolders.Holder.PHONE)
        }
    }

    /**
     * Slipper bare telefonens egen holder. Er bilen i gang, fortsetter sporingen der - ellers
     * ville et bytte til Maps på telefonen slått av farten på bilskjermen.
     */
    override fun onStop() {
        super.onStop()
        Tracking.release(TrackingHolders.Holder.PHONE)
    }

    /**
     * «Auto» først, så de skiltede grensene. NVDB dekker ikke alt - private veger, ny veg,
     * strekninger der fartsgrenseobjektet mangler - og der viste appen «Ukjent grense» og
     * sluttet å regne. Et anslag brukeren selv står for er bedre enn ingenting.
     */
    private fun buildManualRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(manualButton(null))
        ManualLimit.choices.forEach { addView(manualButton(it)) }
    }

    private fun manualButton(kmt: Int?) = Button(this).apply {
        text = kmt?.toString() ?: getString(R.string.manual_limit_auto)
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
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
            val label = button.text.toString()
            val selected = if (active == null) {
                label == getString(R.string.manual_limit_auto)
            } else {
                label == active.toString()
            }
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(130, 130, 135))
        }
    }

    /**
     * «Avslutt» betyr slutt, ikke «gå tilbake»: posisjonssporingen stoppes og varselet forsvinner.
     *
     * Tilbake-knappen gjør riktignok det samme via [onStop], men det er ikke synlig noe sted, og
     * en app som leser GPS og viser et vedvarende varsel må kunne skrus av der den vises - ikke
     * bare forlates og håpes på.
     */
    private fun exitButton() = Button(this).apply {
        text = getString(R.string.exit)
        textSize = 14f
        setOnClickListener { exit() }
    }

    private fun exit() {
        // Slutt for alle, ikke bare for denne skjermen: «Avslutt» betyr slutt.
        Tracking.stopAll()
        finish()
    }

    private fun big(textSize: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.textSize = textSize
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun render(reading: SpeedWatch.Reading?) {
        highlightManual()

        if (reading == null) {
            speed.text = "--"
            headline.text = if (GpsSpeedSource.hasLocationPermission(this)) {
                "Venter på GPS"
            } else {
                "Mangler posisjonstilgang"
            }
            headline.setTextColor(Color.rgb(150, 150, 150))
            detail.text = ""
            return
        }

        val lost = reading.freshness == FixFreshness.LOST
        val estimate = reading.estimate

        // «--» og ikke «0»: null er en måling, og når signalet er borte har vi ingen. Et nulltall
        // ville dessuten sett ut som at bilen står stille.
        speed.text = if (lost) "--" else "${reading.speedKmt.toInt()}"

        val accent = when {
            lost -> Color.rgb(150, 150, 150)
            estimate is FineEstimate.NoOffence -> Color.rgb(60, 190, 100)
            estimate is FineEstimate.UnknownLimit -> Color.rgb(150, 150, 150)
            estimate is FineEstimate.SimplifiedFine ->
                if (estimate.points == 0) Color.rgb(230, 195, 60) else Color.rgb(240, 140, 40)
            estimate is FineEstimate.Prosecution -> Color.rgb(225, 55, 55)
            else -> Color.rgb(150, 150, 150)
        }

        headline.text = when {
            lost -> "Ingen GPS"
            estimate is FineEstimate.UnknownLimit -> "Ukjent grense"
            estimate is FineEstimate.NoOffence -> "Ingen bot"
            estimate is FineEstimate.SimplifiedFine -> "${nok(estimate.amountNok)} kr"
            estimate is FineEstimate.Prosecution -> "Anmeldelse"
            else -> ""
        }
        headline.setTextColor(accent)

        detail.text = buildString {
            when (reading.freshness) {
                // Signalet først: alt annet på skjermen bygger på et fix.
                FixFreshness.LOST -> {
                    append("Ingen GPS")
                    reading.limitKmt?.let { append(" · fartsgrensen $it km/t gjelder fortsatt") }
                }
                FixFreshness.STALE -> append("Mistet GPS-signalet - venter")
                FixFreshness.FRESH -> {
                    append("km/t")
                    reading.limitKmt?.let { append("  ·  grense $it") }
                    if (reading.manualLimit) append(" (manuell)")
                    // Gamle data skal se gamle ut, ikke bare være det.
                    if (reading.match?.stale == true) append(" (gammel)")
                    if (!reading.manualLimit && reading.match?.confidence == MatchConfidence.LOW) {
                        append(" (usikkert vegvalg)")
                    }
                    when (estimate) {
                        is FineEstimate.SimplifiedFine -> {
                            append("  ·  ${estimate.overKmt} km/t over")
                            if (estimate.points > 0) append("  ·  ${estimate.points} prikker")
                        }
                        is FineEstimate.Prosecution -> append("  ·  ${estimate.overKmt} km/t over")
                        else -> Unit
                    }
                }
            }
            if (watch?.ratesStale == true) append("  ·  SATSER UTDATERT")
        }
    }

    private fun nok(amount: Int) = amount.toString()
        .reversed().chunked(3).joinToString(" ").reversed()
}
