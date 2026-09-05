package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate

/**
 * Avgjør når det er verdt å si fra om en LAVERE fartsgrense like foran.
 *
 * Skiltet ser du selv - det er ikke poenget. Poenget er 80-til-50-overgangen inn i et tettsted,
 * der farten du allerede har blir en bot i det du passerer skiltet. Da er beskjeden både
 * tidskritisk og noe du kan gjøre noe med, som er Googles egen målestokk for et heads-up-varsel.
 *
 * Er farten din lovlig også i den nye sonen, sier vi ingenting. Et varsel om noe som ikke koster
 * deg noe er akkurat den støyen som gjør at de ekte varslene ikke blir lest.
 *
 * Skilt fra [AlertPolicy] med vilje: de to varsler om hver sin ting, har hver sin tilstand og
 * hvert sitt varsel på skjermen. Slått sammen ville den ene overskrevet den andre.
 */
class UpcomingLimitPolicy(private val now: () -> Long = System::currentTimeMillis) {

    sealed interface Decision {
        data class Warn(
            val limitKmt: Int,
            val meters: Int,
            val estimate: FineEstimate,
        ) : Decision

        data object Ignore : Decision
    }

    private var warnedLimit: Int? = null
    private var lastWarnAtMs: Long? = null

    fun next(limitKmt: Int?, meters: Int?, estimateThere: FineEstimate?): Decision {
        // Ingen lavere sone foran lenger: neste gang det dukker opp en, er den ny igjen.
        if (limitKmt == null || meters == null || estimateThere == null) {
            warnedLimit = null
            return Decision.Ignore
        }

        // Lovlig der framme også. Ingen grunn til å si fra.
        if (estimateThere is FineEstimate.NoOffence || estimateThere is FineEstimate.UnknownLimit) {
            warnedLimit = null
            return Decision.Ignore
        }

        // Én beskjed per sone, ikke én per GPS-fix mens du nærmer deg den.
        if (limitKmt == warnedLimit) return Decision.Ignore

        val since = lastWarnAtMs
        if (since != null && now() - since < MIN_INTERVAL_MS) return Decision.Ignore

        warnedLimit = limitKmt
        lastWarnAtMs = now()
        return Decision.Warn(limitKmt, meters, estimateThere)
    }

    companion object {
        /** To soneskifter på under et halvt minutt er en bygate, og da er varslene bare mas. */
        const val MIN_INTERVAL_MS = 30_000L
    }
}
