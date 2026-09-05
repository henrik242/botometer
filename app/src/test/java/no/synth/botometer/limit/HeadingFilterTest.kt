package no.synth.botometer.limit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * En veg er en linje, ikke en pil.
 *
 * NVDB digitaliserer en veg i én retning, og hvilken det er har ingenting med kjøreretningen din
 * å gjøre. Kjører du motsatt vei, er kursavviket 180° selv om du ligger midt i vegbanen - og
 * kursfilteret forkastet da riktig veg.
 *
 * Målt i bil: EV18 lå 5 meter unna med Δ173° og ble forkastet, mens en kommunal veg 15 meter unna
 * med Δ27° vant. Appen viste 40 der det var 50.
 */
class HeadingFilterTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    /** Vi kjører østover. */
    private val heading = 90.0
    private val position = LatLon(59.900000, 10.600000)

    /**
     * Hovedvegen, 5 m nord for oss, digitalisert VESTOVER - altså motsatt av kjøreretningen.
     * Uten aksefolding gir den Δ180°.
     */
    private val hovedveg =
        """{"id":1,"egenskaper":[{"id":2021,"verdi":50}],""" +
            """"geometri":{"wkt":"LINESTRING(10.6010 59.900045, 10.5990 59.900045)"},""" +
            """"lokasjon":{"vegsystemreferanser":[{"kortform":"EV18"}]}}"""

    /** Sideveg, tre ganger så langt unna, men digitalisert samme vei som vi kjører. */
    private val sideveg =
        """{"id":2,"egenskaper":[{"id":2021,"verdi":40}],""" +
            """"geometri":{"wkt":"LINESTRING(10.5990 59.899865, 10.6010 59.899865)"},""" +
            """"lokasjon":{"vegsystemreferanser":[{"kortform":"KV14376"}]}}"""

    @Before fun start() {
        server = MockWebServer()
        // Alle ruteoppslag svarer likt: både ruta vi står i og den som forhåndslastes.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                """{"objekter":[$hovedveg,$sideveg],"metadata":{}}"""
            )
        }
        server.start()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun repository() = SpeedLimitRepository(
        nvdb = NvdbClient(
            clientName = "botometer-test",
            baseUrl = server.url("/").toString().trimEnd('/'),
        ),
        scope = scope,
    )

    /** Første oppslag setter i gang nedlastingen; treffet kommer når ruta er lastet. */
    private suspend fun matchAt(repo: SpeedLimitRepository): LimitMatch =
        withTimeout(10_000) {
            var match: LimitMatch? = null
            while (match == null) {
                match = repo.limitAt(position, heading, speedKmt = 60.0)?.takeIf { !it.stale }
                if (match == null) delay(20)
            }
            match
        }

    @Test
    fun `motsatt digitalisert hovedveg vinner over naerliggende sideveg`() = runBlocking {
        val match = matchAt(repository())

        assertEquals(
            "hovedvegen ligger nærmest; at NVDB har tegnet den motsatt vei er irrelevant",
            50,
            match.limitKmt,
        )
        assertEquals("EV18", match.roadRef)
    }

    @Test
    fun `avstanden som rapporteres er til den vegen som faktisk vant`() = runBlocking {
        val match = matchAt(repository())

        // 5 m, ikke 15. Rapporterer vi feil avstand, ser et riktig treff mistenkelig ut.
        assertEquals(5.0, match.distanceMeters, 1.5)
    }
}
