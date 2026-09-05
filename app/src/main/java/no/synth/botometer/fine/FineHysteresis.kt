package no.synth.botometer.fine

/**
 * Demper flimringen på en trinngrense.
 *
 * GPS-farten vaker et par tideler rundt den sanne farten. Ligger du på nøyaktig 16 km/t over,
 * hopper beregningen mellom 15 og 16 flere ganger i sekundet - og beløpet mellom 5 950 og
 * 8 650 kroner. Det er tallet som trekker blikket på en bilskjerm, og et tall som hopper er
 * verre å lese enn et som ligger litt etter.
 *
 * Regelen er bevisst usymmetrisk: **oppover slipper alltid gjennom med én gang, nedover må
 * holde seg i [holdMs] først.** Et beløp som er for lavt er verre enn et som er for høyt - det
 * første lyver om konsekvensen, det andre er bare gammelt. Og siden bare den ene retningen
 * venter, kan trinnet ikke vippe fram og tilbake: du må ha ligget under grensa sammenhengende
 * før nedturen vises.
 *
 * Bytter fartsgrensen, nullstilles alt. Å holde igjen et trinn fra en sone du nettopp forlot
 * ville vist en bot for en fartsgrense som ikke gjelder lenger.
 */
class FineHysteresis(
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var shown: FineEstimate? = null
    private var shownLimit: Int? = null
    private var pendingBand: String? = null
    private var pendingSince = 0L

    fun stabilize(limitKmt: Int?, estimate: FineEstimate): FineEstimate {
        // Ny sone er ny virkelighet.
        if (limitKmt != shownLimit || shown == null) {
            shownLimit = limitKmt
            pendingBand = null
            shown = estimate
            return estimate
        }

        val current = shown!!
        if (rank(estimate) >= rank(current)) {
            pendingBand = null
            shown = estimate
            return estimate
        }

        val band = bandOf(estimate)
        if (band != pendingBand) {
            pendingBand = band
            pendingSince = now()
        }
        if (now() - pendingSince < holdMs) return current

        pendingBand = null
        shown = estimate
        return estimate
    }

    /** Hvor alvorlig utfallet er. Anmeldelse slår ethvert beløp. */
    private fun rank(estimate: FineEstimate): Int = when (estimate) {
        is FineEstimate.UnknownLimit, is FineEstimate.NoOffence -> 0
        is FineEstimate.SimplifiedFine -> estimate.amountNok
        is FineEstimate.Prosecution -> Int.MAX_VALUE
    }

    /** Nøkkel som endrer seg nøyaktig når trinnet gjør det. */
    private fun bandOf(estimate: FineEstimate): String = when (estimate) {
        is FineEstimate.UnknownLimit -> "ukjent"
        is FineEstimate.NoOffence -> "ingen"
        is FineEstimate.SimplifiedFine -> estimate.band
        is FineEstimate.Prosecution -> "anmeldelse"
    }

    companion object {
        /**
         * Langt nok til å dekke vaking i GPS-farten, kort nok til at nedturen etter en
         * nedbremsing ikke føles som en feil.
         */
        const val DEFAULT_HOLD_MS = 1_500L
    }
}
