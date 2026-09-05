package no.synth.botometer.alert

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import no.synth.botometer.BotometerApp
import no.synth.botometer.R
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.FineHysteresis
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.limit.LimitMatch
import no.synth.botometer.limit.ManualLimit
import no.synth.botometer.speed.FixFreshness
import no.synth.botometer.speed.SpeedFeed
import no.synth.botometer.speed.SpeedFix

/**
 * Fart inn, bot ut. Ett regnestykke, delt av alle tre flatene: bilskjermen, telefonskjermen ved
 * siden av Maps, og fartsvarslene. Var det tre kopier, ville appen kunne varsle om en bot den
 * ikke viser.
 *
 * Fartsgrensene kommer fra det delte repoet, så de tre flatene deler rute-cache i stedet for å
 * hente de samme rutene hver for seg.
 */
class SpeedWatch(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    private val app = context.applicationContext as BotometerApp

    // Lastes her, ikke delt: da slår «Oppdater satser» gjennom uten omstart av prosessen.
    private val tableStatus = FineTableRepository(
        app, app.getString(R.string.satser_url)
    ).load()

    val calculator = FineCalculator(tableStatus.table)
    val ratesStale: Boolean get() = tableStatus.stale

    private val hysteresis = FineHysteresis()
    private var lastMatch: LimitMatch? = null

    data class Reading(
        val fix: SpeedFix,
        val match: LimitMatch?,
        val estimate: FineEstimate,
        val freshness: FixFreshness,
        /** Farten som gjelder, altså 0 når fixet er for gammelt til å si noe om fart. */
        val speedKmt: Double,
        /** Grensen som er lagt til grunn - manuell om brukeren har satt en. */
        val limitKmt: Int?,
        val manualLimit: Boolean,
    )

    fun readings(): Flow<Reading> =
        merge(SpeedFeed.fixes, heartbeat()).filterNotNull().map { reading(it) }

    /**
     * Pulsen som gjør at skjermen oppdaterer seg selv om GPS-en har sluttet å levere.
     *
     * [SpeedFeed] er en StateFlow, og den emitterer bare når verdien endrer seg. Uten en puls
     * sluttet skjermen å tegne i det øyeblikket signalet forsvant, og det siste bildet - full
     * fart, voksende bot - ble stående som om det fortsatt gjaldt.
     */
    private fun heartbeat(): Flow<SpeedFix?> = flow {
        while (true) {
            delay(HEARTBEAT_MS)
            emit(SpeedFeed.fixes.value)
        }
    }

    private suspend fun reading(fix: SpeedFix): Reading {
        val freshness = FixFreshness.ofAge(elapsedRealtime() - fix.elapsedRealtimeMs)
        val lost = freshness == FixFreshness.LOST

        // Ingen fart vi tror på, og ingen grunn til å matche mot en posisjon vi ikke tror på
        // heller. Fartsgrensen beholder vi: den gjelder fortsatt inne i tunnelen.
        val speed = if (lost) 0.0 else fix.speedKmt
        val match = if (lost) {
            lastMatch?.copy(stale = true)
        } else {
            app.speedLimits.limitAt(
                fix.position,
                fix.headingDeg,
                fix.speedKmt,
                fix.accuracyMeters.toDouble(),
            )
        }
        lastMatch = match

        val manual = ManualLimit.kmt.value
        val limit = manual ?: match?.limitKmt
        // En manuell grense sier ingenting om vegtype, så motorvegsatsen forblir usikker der.
        val motorway = if (manual != null) null else match?.motorway

        return Reading(
            fix = fix,
            match = match,
            estimate = hysteresis.stabilize(limit, calculator.estimate(speed, limit, motorway)),
            freshness = freshness,
            speedKmt = speed,
            limitKmt = limit,
            manualLimit = manual != null,
        )
    }

    private companion object {
        /** Samme takt som GPS-en leverer i, så et tapt signal merkes innen ett sekund. */
        const val HEARTBEAT_MS = 1_000L
    }
}
