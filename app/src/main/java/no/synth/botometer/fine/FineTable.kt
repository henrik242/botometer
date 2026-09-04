package no.synth.botometer.fine

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Satsene er ikke tilgjengelige via noe åpent API (Lovdata sin API er kommersiell), så de
 * ligger som en versjonert asset. Filen har en `versjon` som vises i UI, slik at det er
 * synlig for brukeren om appen kjører på utdaterte satser.
 */
@Serializable
data class FineTable(
    val versjon: String,
    val hjemmel: String,
    val kilde: String = "",
    val toleranse: Tolerance,
    val grupper: List<LimitGroup>,
) {
    fun groupFor(speedLimitKmt: Int): LimitGroup? =
        grupper.firstOrNull { speedLimitKmt >= it.fartsgrenseMin && speedLimitKmt <= it.fartsgrenseMaks }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromAssets(context: Context, name: String = "botesatser.json"): FineTable =
            fromJson(context.assets.open(name).use { it.readBytes().decodeToString() })

        fun fromJson(raw: String): FineTable = json.decodeFromString(raw)
    }
}

@Serializable
data class Tolerance(
    val fradragKmt: Int,
    val fradragProsentOverKmt: Int,
    val fradragProsent: Double,
    val merknad: String = "",
) {
    /**
     * Politiet trekker fra et sikkerhetsfradrag i målt fart før overskridelsen fastsettes:
     * fast fradrag i km/t opp til en terskel, prosentvis over. Uten dette ville appen
     * systematisk overdrive boten i grenseland.
     */
    fun deduct(measuredKmt: Double): Double =
        if (measuredKmt <= fradragProsentOverKmt) measuredKmt - fradragKmt
        else measuredKmt * (1.0 - fradragProsent / 100.0)
}

@Serializable
data class LimitGroup(
    val id: String,
    val navn: String,
    val fartsgrenseMin: Int,
    val fartsgrenseMaks: Int,
    val trinn: List<FineBand>,
) {
    /**
     * 36-40 km/t over gjelder bare motorveg. Vet vi at vegen IKKE er motorveg, faller trinnet
     * bort og overtredelsen havner over taket for forenklet forelegg (null = anmeldelse).
     * Er motorveg-status ukjent bruker vi trinnet, men FineCalculator markerer det som usikkert.
     */
    fun bandFor(overKmt: Int, motorway: Boolean?): FineBand? {
        val candidates = trinn.filter { overKmt >= it.overMin && (it.overMaks == null || overKmt <= it.overMaks) }
        return if (motorway == false) candidates.firstOrNull { !it.kunMotorveg }
        else candidates.firstOrNull()
    }

    val ceilingBand: FineBand get() = trinn.last()
}

@Serializable
data class FineBand(
    val overMin: Int,
    val overMaks: Int? = null,
    val bot: Int? = null,
    val prikker: Int = 0,
    @SerialName("forerrett") val licence: LicenceOutcome = LicenceOutcome.BEHOLDER,
    val anmeldelse: Boolean = false,
    val kunMotorveg: Boolean = false,
) {
    val label: String get() = if (overMaks == null) "$overMin+ km/t over" else "$overMin-$overMaks km/t over"
}

@Serializable
enum class LicenceOutcome { BEHOLDER, VURDERES, INNDRAS }
