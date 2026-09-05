package no.synth.botometer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
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
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.LocationForegroundService

/**
 * Speedometeret på telefonskjermen, til bruk mens Google Maps eier bilskjermen.
 *
 * Bilverten kjører én navigasjonsapp om gangen, og Car App Library har ingen liten sideflate å
 * be om. Telefonen i holderen er den eneste flaten som faktisk kan stå ved siden av Maps, og den
 * krever verken Play-opplasting eller vertens velsignelse.
 *
 * Samme regnestykke og samme fargekoding som bilskjermen, men bevisst enklere: tre linjer, stor
 * skrift, ingen animasjon. Det skal kunne leses i periferien uten at blikket forlater veien.
 */
class PhoneSpeedometerActivity : ComponentActivity() {

    // Nullable, ikke lateinit: konstruksjonen leser satsfila fra disk, og det skal
    // ikke skje på hovedtråden mens skjermen skal tegnes.
    private var watch: SpeedWatch? = null
    private lateinit var speed: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skjermen skal ikke slukke midt i en kjøretur. Den slås av igjen når aktiviteten
        // forlates, siden flagget følger vinduet.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speed = big(textSize = 96f)
        headline = big(textSize = 56f)
        detail = big(textSize = 18f, color = Color.rgb(170, 170, 175))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 12, 14))
            addView(speed)
            addView(headline)
            addView(detail)
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
        if (GpsSpeedSource.hasLocationPermission(this)) LocationForegroundService.start(this)
    }

    override fun onStop() {
        super.onStop()
        LocationForegroundService.stop(this)
    }

    private fun big(textSize: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.textSize = textSize
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun render(reading: SpeedWatch.Reading?) {
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

        val limit = reading.match?.limitKmt
        speed.text = "${reading.fix.speedKmt.toInt()}"

        val accent = when (val e = reading.estimate) {
            is FineEstimate.NoOffence -> Color.rgb(60, 190, 100)
            is FineEstimate.UnknownLimit -> Color.rgb(150, 150, 150)
            is FineEstimate.SimplifiedFine ->
                if (e.points == 0) Color.rgb(230, 195, 60) else Color.rgb(240, 140, 40)
            is FineEstimate.Prosecution -> Color.rgb(225, 55, 55)
        }

        headline.text = when (val e = reading.estimate) {
            is FineEstimate.UnknownLimit -> "Ukjent grense"
            is FineEstimate.NoOffence -> "Ingen bot"
            is FineEstimate.SimplifiedFine -> "${nok(e.amountNok)} kr"
            is FineEstimate.Prosecution -> "Anmeldelse"
        }
        headline.setTextColor(accent)

        detail.text = buildString {
            append("km/t")
            if (limit != null) append("  ·  grense $limit")
            // Gamle data skal se gamle ut, ikke bare være det.
            if (reading.match?.stale == true) append(" (gammel)")
            when (val e = reading.estimate) {
                is FineEstimate.SimplifiedFine -> {
                    append("  ·  ${e.overKmt} km/t over")
                    if (e.points > 0) append("  ·  ${e.points} prikker")
                }
                is FineEstimate.Prosecution -> append("  ·  ${e.overKmt} km/t over")
                else -> Unit
            }
            if (watch?.ratesStale == true) append("  ·  SATSER UTDATERT")
        }
    }

    private fun nok(amount: Int) = amount.toString()
        .reversed().chunked(3).joinToString(" ").reversed()
}
