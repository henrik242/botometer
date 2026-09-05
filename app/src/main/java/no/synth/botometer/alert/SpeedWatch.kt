package no.synth.botometer.alert

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import no.synth.botometer.BotometerApp
import no.synth.botometer.R
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.limit.LimitMatch
import no.synth.botometer.speed.SpeedFeed
import no.synth.botometer.speed.SpeedFix

/**
 * Fart inn, bot ut. Samme regnestykke som bilskjermen gjør, tilgjengelig for de to andre
 * flatene: telefonskjermen ved siden av Maps, og fartsvarslene.
 *
 * Fartsgrensene kommer fra det delte repoet, så de tre flatene deler rute-cache i stedet for å
 * hente de samme rutene hver for seg.
 */
class SpeedWatch(context: Context) {

    private val app = context.applicationContext as BotometerApp

    // Lastes her, ikke delt: da slår «Oppdater satser» gjennom uten omstart av prosessen.
    private val tableStatus = FineTableRepository(
        app, app.getString(R.string.satser_url)
    ).load()

    val calculator = FineCalculator(tableStatus.table)
    val ratesStale: Boolean get() = tableStatus.stale

    data class Reading(
        val fix: SpeedFix,
        val match: LimitMatch?,
        val estimate: FineEstimate,
    )

    fun readings(): Flow<Reading> = SpeedFeed.fixes.filterNotNull().map { fix ->
        val match = app.speedLimits.limitAt(fix.position, fix.headingDeg, fix.speedKmt)
        Reading(fix, match, calculator.estimate(fix.speedKmt, match?.limitKmt))
    }
}
