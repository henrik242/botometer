package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome
import no.synth.botometer.speed.FixFreshness
import kotlin.math.roundToInt

/**
 * Hvor ille det er. Fargen bæres av konsekvensen, ikke av farten - og hver flate oversetter den
 * til sin egen palett, siden bilskjermen tegnes på svart og telefonen på hvitt.
 *
 * Et eget begrep, og ikke en `when` over [FineEstimate] hos hver flate: da får hver av dem sin
 * egen `else`-gren, og en femte utfallstype ville blitt grå og stum på alle tre uten at noe sa
 * fra.
 */
enum class Severity { LOST, UNKNOWN, NONE, FINE, POINTS, PROSECUTION }

/**
 * Ordene som beskriver en [SpeedWatch.Reading].
 *
 * [SpeedWatch] sørger for at de tre flatene regner på det samme. Det hindret dem ikke i å
 * *si* forskjellige ting om det: telefonen kuttet farten med `toInt()` mens bilen rundet med
 * `roundToInt()` (79,6 km/t ble 79 på telefonen og 80 i bilen), «Ukjent grense» het «Ukjent
 * fartsgrense» i bilen, beløpet ble formatert av to forskjellige rutiner, og telefonen droppet
 * tap av førerrett og usikkerhetsmerket helt - på flaten som nå er hovedspeedometeret.
 *
 * Her ligger de delene som må være like. Hver flate beholder sin egen layout: bilen har plass
 * til en linje til, telefonen har to felt, varselet har tittel og tekst.
 */
object ReadingText {

    fun severity(reading: SpeedWatch.Reading): Severity = when {
        reading.freshness == FixFreshness.LOST -> Severity.LOST
        else -> when (val estimate = reading.estimate) {
            is FineEstimate.UnknownLimit -> Severity.UNKNOWN
            is FineEstimate.NoOffence -> Severity.NONE
            is FineEstimate.SimplifiedFine ->
                if (estimate.points == 0) Severity.FINE else Severity.POINTS
            is FineEstimate.Prosecution -> Severity.PROSECUTION
        }
    }

    /**
     * «--» og ikke «0» når signalet er borte: null er en måling, og vi har ingen. Et nulltall
     * ville dessuten sett ut som at bilen står stille.
     *
     * Avrundet, ikke avkortet. `toInt()` kutter mot null og underrapporterer med opptil 1 km/t -
     * feil vei i en app om fartsovertredelser, og i tillegg regnes boten på den uavkortede
     * farten, så kortet kunne vise en fart som så lovlig ut ved siden av en bot som sa noe annet.
     */
    fun speed(reading: SpeedWatch.Reading): String =
        if (reading.freshness == FixFreshness.LOST) "--" else "${reading.speedKmt.roundToInt()}"

    /** Konsekvensen i tre ord eller færre. Det som står størst etter selve farten. */
    fun headline(reading: SpeedWatch.Reading): String = when (severity(reading)) {
        Severity.LOST -> "Ingen GPS"
        Severity.UNKNOWN -> "Ukjent fartsgrense"
        Severity.NONE -> "Ingen bot"
        Severity.FINE, Severity.POINTS ->
            "${nok((reading.estimate as FineEstimate.SimplifiedFine).amountNok)} kr"
        Severity.PROSECUTION -> "Anmeldelse"
    }

    /**
     * Hvor mange km/t over fartsgrensen bilen FAKTISK kjører.
     *
     * Ikke det samme som [FineEstimate.SimplifiedFine.overKmt], og det er hele poenget.
     * Politiet trekker fra et sikkerhetsfradrag før overtredelsen fastsettes - 3 km/t opp til
     * 100 - så 84 i en 80-sone gir grunnlag 81 og dermed 1 km/t over i BOTENS forstand. Appen
     * skrev det tallet på skjermen, og det er feil svar på spørsmålet den blir stilt: du kjører
     * 4 km/t for fort. Fradraget er en juridisk gunst, ikke en beskrivelse av farten din.
     *
     * Regnet fra den VISTE farten, ikke fra rådataene, så tallene på skjermen går opp: står det
     * «84 km/t · grense 80», skal det stå «4 km/t over». (Grensen er et heltall, så
     * `round(fart) - grense` og `round(fart - grense)` gir uansett samme svar.)
     */
    fun overKmt(reading: SpeedWatch.Reading): Int? =
        reading.limitKmt?.let { reading.speedKmt.roundToInt() - it }

    /**
     * Halen som forklarer beløpet: hvor mye over, prikker, førerrett, usikkerhet.
     *
     * Førerretten er ikke pynt. Den var med i bilen og i varselet, men ikke på telefonen - og
     * telefonen er flaten du ser på. 24 km/t over i en 50-sone sto som «10 400 kr · 24 km/t
     * over · 6 prikker» og fortiet det ene som faktisk endrer kvelden din.
     *
     * Tar en [SpeedWatch.Reading] og ikke bare en [FineEstimate], nettopp fordi «hvor mye over»
     * skal komme fra farten og grensen - se [overKmt] - og ikke fra bøtegrunnlaget.
     *
     * @param separator flatene har ulik luft: bilskjermen og varselet bruker « · », telefonen
     * setter mer luft rundt fordi teksten er mindre.
     */
    fun consequences(reading: SpeedWatch.Reading, separator: String = " · "): String = buildString {
        val estimate = reading.estimate
        // Faller tilbake på bøtegrunnlaget bare hvis grensen mangler - og da finnes det ingen
        // bot å forklare heller, så grenen er i praksis død.
        val over = overKmt(reading)
        when (estimate) {
            is FineEstimate.SimplifiedFine -> {
                append("${over ?: estimate.overKmt} km/t over")
                if (estimate.points > 0) append("$separator${estimate.points} prikker")
                append(licence(estimate.licence, separator))
                // Satsen gjelder bare motorveg, og vegtypen er ukjent. Et beløp som ser sikkert
                // ut men ikke er det, er verre enn et beløp med forbehold.
                if (estimate.uncertain) append(" (usikker)")
            }
            is FineEstimate.Prosecution -> {
                append("${over ?: estimate.overKmt} km/t over")
                append("${separator}over taket for forenklet forelegg")
                append(licence(estimate.licence, separator))
            }
            is FineEstimate.UnknownLimit, is FineEstimate.NoOffence -> Unit
        }
    }

    private fun licence(outcome: LicenceOutcome, separator: String): String = when (outcome) {
        LicenceOutcome.VURDERES -> "${separator}førerretten vurderes"
        LicenceOutcome.INNDRAS -> "${separator}tap av førerrett"
        LicenceOutcome.BEHOLDER -> ""
    }

    /**
     * «10 400», ikke «10400». Egen rutine og ikke [java.text.NumberFormat]: den grupperer med
     * hardt mellomrom i noen JDK-er og vanlig i andre, så to flater kunne formatere det samme
     * beløpet ulikt avhengig av hvor koden kjørte. Her er svaret det samme overalt, alltid.
     */
    fun nok(amount: Int): String =
        amount.toString().reversed().chunked(3).joinToString(" ").reversed()
}
