package no.synth.botometer.fine

import kotlin.math.floor

sealed interface FineEstimate {
    /** Ingen kjent fartsgrense - vi nekter å gjette. */
    data object UnknownLimit : FineEstimate

    data object NoOffence : FineEstimate

    data class SimplifiedFine(
        val amountNok: Int,
        val points: Int,
        val licence: LicenceOutcome,
        val overKmt: Int,
        val band: String,
        val uncertain: Boolean = false,
        val uncertaintyReason: String? = null,
    ) : FineEstimate

    /** Over taket for forenklet forelegg: vanlig forelegg / anmeldelse. */
    data class Prosecution(
        val points: Int,
        val overKmt: Int,
        val licence: LicenceOutcome,
    ) : FineEstimate
}

class FineCalculator(private val table: FineTable) {

    val version: String get() = table.versjon
    val legalBasis: String get() = table.hjemmel

    /**
     * @param speedKmt fart fra GPS/bil (antatt lik faktisk fart)
     * @param limitKmt gjeldende fartsgrense, eller null om vi ikke vet
     * @param motorway om vegen er motorveg; null = ukjent
     */
    fun estimate(speedKmt: Double, limitKmt: Int?, motorway: Boolean? = null): FineEstimate {
        if (limitKmt == null || limitKmt <= 0) return FineEstimate.UnknownLimit

        // Grunnlaget for boten er målt fart minus sikkerhetsfradrag, avrundet ned.
        val basis = floor(table.toleranse.deduct(speedKmt)).toInt()
        val over = basis - limitKmt
        if (over < 1) return FineEstimate.NoOffence

        val group = table.groupFor(limitKmt) ?: return FineEstimate.UnknownLimit
        // Ingen treff betyr at overtredelsen ligger over taket for forenklet forelegg for denne
        // kombinasjonen av sone og vegtype - f.eks. 37 km/t over i en 90-sone som ikke er motorveg.
        val band = group.bandFor(over, motorway) ?: group.ceilingBand

        if (band.anmeldelse || band.bot == null) {
            return FineEstimate.Prosecution(band.prikker, over, band.licence)
        }

        val uncertain = band.kunMotorveg && motorway == null
        return FineEstimate.SimplifiedFine(
            amountNok = band.bot,
            points = band.prikker,
            licence = band.licence,
            overKmt = over,
            band = band.label,
            uncertain = uncertain,
            uncertaintyReason = if (uncertain) "Satsen gjelder bare motorveg - vegtype ukjent" else null,
        )
    }

    /** Hvor mange km/t til neste (dyrere) trinn - brukes til «marginen din» i UI. */
    fun kmtToNextBand(speedKmt: Double, limitKmt: Int?, motorway: Boolean? = null): Int? {
        if (limitKmt == null) return null
        val group = table.groupFor(limitKmt) ?: return null
        val basis = floor(table.toleranse.deduct(speedKmt)).toInt()
        val over = basis - limitKmt
        val next = group.trinn.firstOrNull { it.overMin > over } ?: return null
        val kmtAtNext = limitKmt + next.overMin
        // Inverter fradraget grovt: fradraget er lite, så én iterasjon holder.
        val needed = kmtAtNext + (speedKmt - basis)
        return (needed - speedKmt).toInt().coerceAtLeast(0)
    }
}
