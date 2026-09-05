package no.synth.botometer.speed

/**
 * Hvor gammelt siste GPS-fix er, oversatt til hva skjermen har lov til å påstå.
 *
 * Uten dette står farten stille på skjermen når GPS-en forsvinner - og den ser like sikker ut
 * som før. [SpeedFeed] er en StateFlow, og en StateFlow emitterer bare når verdien endrer seg:
 * en fart som ikke oppdateres endrer seg ikke. I en tunnel ble 97 km/t og et voksende bøtebeløp
 * stående til bilen kom ut igjen, uten et eneste tegn på at tallet var dødt.
 *
 * Det er den farligste feilen appen kan gjøre, nettopp fordi den ikke ser ut som en feil.
 */
enum class FixFreshness {
    /** Fersk nok til å regne bot av. */
    FRESH,

    /** Signalet er borte, men farten er sannsynligvis fortsatt omtrent riktig. */
    STALE,

    /** For gammelt til å bety noe. Farten skal ikke lenger vises som om den gjelder. */
    LOST;

    companion object {
        /** Fused provider leverer 1 Hz. Fem sekunder er fem tapte fix, ikke en hikke. */
        const val STALE_AFTER_MS = 5_000L

        /**
         * I 80 km/t har du kjørt en snau halv kilometer på tjue sekunder. Da vet vi verken hvor
         * du er eller hvor fort du kjører, og et bøtebeløp er ren gjetning.
         */
        const val LOST_AFTER_MS = 20_000L

        /**
         * @param ageMs alder målt på monoton klokke ([android.os.SystemClock.elapsedRealtime]),
         * ikke veggklokke: et tidshopp bakover ville ellers gjort et gammelt fix ferskt igjen.
         */
        fun ofAge(ageMs: Long): FixFreshness = when {
            ageMs >= LOST_AFTER_MS -> LOST
            ageMs >= STALE_AFTER_MS -> STALE
            else -> FRESH
        }
    }
}
