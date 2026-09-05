package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.fine.LicenceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skiltet ser du selv. Poenget er 80-til-50-overgangen inn i et tettsted, der farten du allerede
 * har blir en bot i det du passerer skiltet - og der beskjeden både er tidskritisk og noe du kan
 * gjøre noe med, som er Googles egen målestokk for et heads-up-varsel.
 *
 * Er farten lovlig også der framme, sier vi ingenting. Varsler om noe som ikke koster deg noe er
 * akkurat den støyen som gjør at de ekte varslene ikke blir lest.
 */
class UpcomingLimitPolicyTest {

    private var clock = 0L
    private val policy = UpcomingLimitPolicy { clock }

    private val bot = FineEstimate.SimplifiedFine(
        amountNok = 3_350,
        points = 0,
        licence = LicenceOutcome.BEHOLDER,
        overKmt = 8,
        band = "6-10 km/t over",
    )

    @Test
    fun `ingen sone foran gir ingen beskjed`() {
        assertEquals(UpcomingLimitPolicy.Decision.Ignore, policy.next(null, null, null))
    }

    @Test
    fun `lovlig fart i sonen foran gir ingen beskjed`() {
        assertEquals(
            UpcomingLimitPolicy.Decision.Ignore,
            policy.next(50, 180, FineEstimate.NoOffence),
        )
    }

    @Test
    fun `en sone der farten din koster varsles`() {
        val d = policy.next(50, 180, bot)
        assertTrue(d is UpcomingLimitPolicy.Decision.Warn)
        d as UpcomingLimitPolicy.Decision.Warn
        assertEquals(50, d.limitKmt)
        assertEquals(180, d.meters)
    }

    @Test
    fun `samme sone varsles bare en gang mens du naermer deg`() {
        assertTrue(policy.next(50, 200, bot) is UpcomingLimitPolicy.Decision.Warn)

        repeat(10) {
            clock += 1_000
            assertEquals(
                "ett varsel per sone, ikke ett per GPS-fix",
                UpcomingLimitPolicy.Decision.Ignore,
                policy.next(50, 200 - it * 10, bot),
            )
        }
    }

    @Test
    fun `en ny sone etter at den forrige er passert varsles paa nytt`() {
        policy.next(50, 200, bot)
        policy.next(null, null, null)          // sonen er passert
        clock += UpcomingLimitPolicy.MIN_INTERVAL_MS

        assertTrue(policy.next(40, 150, bot) is UpcomingLimitPolicy.Decision.Warn)
    }

    @Test
    fun `to soneskifter tett paa hverandre gir bare ett varsel`() {
        policy.next(50, 200, bot)

        clock += 5_000
        assertEquals(
            "en bygate med skifter hvert kvartal skal ikke bli en varselkaskade",
            UpcomingLimitPolicy.Decision.Ignore,
            policy.next(30, 150, bot),
        )
    }
}
