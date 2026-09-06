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
import no.synth.botometer.fine.TableStatus
import no.synth.botometer.limit.LimitMatch
import no.synth.botometer.limit.ManualLimit
import no.synth.botometer.speed.FixFreshness
import no.synth.botometer.speed.SpeedFeed
import no.synth.botometer.speed.SpeedFix

/**
 * Fart inn, bot ut. Ett regnestykke, delt av alle tre flatene: bilskjermen, telefonskjermen og
 * fartsvarslene.
 *
 * Klassen har alltid sagt dette. Den var det ikke: hver flate laget sitt eget eksemplar, og med
 * det sin egen [FineHysteresis], sin egen `lastMatch` og sitt eget øyeblikksbilde av satsfila.
 * Tre kopier av «ett regnestykke» kan drive fra hverandre - og de gjorde det:
 *
 * - «Oppdater satser» nådde aldri fartsvarslene. Tjenesten lagde sitt eksemplar én gang og
 *   beholdt den gamle tabellen ut turen, så appen kunne varsle om et beløp den ikke viste.
 * - Hysteresen hadde tre uavhengige holdevinduer og tre pulser i utakt, så varselet og de to
 *   skjermene kunne vise hvert sitt beløp i opptil et vindu under en nedbremsing.
 * - Hver avlesning kart-matchet posisjonen på nytt. Med puls og fix ble den samme posisjonen
 *   matchet seks ganger i sekundet, og `Diagnostics` viste hvilken flate som skrev sist -
 *   ikke «treffet».
 *
 * Derfor eier [no.synth.botometer.BotometerApp] nå ett eksemplar, og flatene deler strømmen.
 *
 * Fartsgrensene kommer fra det delte repoet, så flatene deler rute-cache i stedet for å hente
 * de samme rutene hver for seg.
 */
class SpeedWatch(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    private val app = context.applicationContext as BotometerApp

    private val rates = FineTableRepository(app, app.getString(R.string.satser_url))

    /**
     * Satsene kan byttes ut mens appen kjører, så de er ikke `val`.
     *
     * De ble lastet én gang per eksemplar, med kommentaren at det var slik «Oppdater satser»
     * slo gjennom uten omstart. Det var sant bare for den flaten som tilfeldigvis lagde et nytt
     * eksemplar etterpå - og fartsvarslenes eksemplar, som lages én gang per tjeneste, gjorde
     * aldri det. Nå er det ett eksemplar, og [reloadRates] er veien inn i det.
     */
    @Volatile
    private var tableStatus: TableStatus = rates.load()

    @Volatile
    var calculator: FineCalculator = FineCalculator(tableStatus.table)
        private set

    val ratesStale: Boolean get() = tableStatus.stale

    /** Kalles etter «Oppdater satser». Leser fra disk, så ikke fra hovedtråden. */
    fun reloadRates() {
        tableStatus = rates.load()
        calculator = FineCalculator(tableStatus.table)
    }

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
