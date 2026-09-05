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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Kart-matchingen, målt på det den faktisk skal svare på: hvor sikker er den, når har den lov
 * til å bytte fartsgrense, og hva gjør den med et dårlig GPS-fix.
 *
 * Repoet hadde ingen tester i det hele tatt - `now` var injiserbar for formålet, men
 * [NvdbClient] måtte kunne peke på en lokal server først. Det kan den, via `baseUrl`.
 *
 * Robolectric selv om ingenting her rører Android-UI: rute-cachen er en `android.util.LruCache`,
 * og med `isReturnDefaultValues` er den en stubb på ren JVM. `put` gjør da ingenting, ruta blir
 * aldri cachet, og testen venter i evighet på et treff som ikke kan komme.
 */
@RunWith(RobolectricTestRunner::class)
class SpeedLimitMatchingTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    /** Vi kjører østover, langs veger som går øst-vest. */
    private val heading = 90.0

    @Before fun start() {
        server = MockWebServer()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    /** Alle ruteoppslag svarer likt: både ruta vi står i og den som forhåndslastes. */
    private fun serve(vararg objekter: String) {
        val body = """{"objekter":[${objekter.joinToString(",")}],"metadata":{}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body)
        }
        server.start()
    }

    private fun repository() = SpeedLimitRepository(
        nvdb = NvdbClient(
            clientName = "botometer-test",
            baseUrl = server.url("/").toString().trimEnd('/'),
        ),
        scope = scope,
    )

    /** Én øst-vest-veg på gitt breddegrad, fra lon [west] til [east]. */
    private fun road(
        id: Int,
        limit: Int,
        lat: Double,
        west: Double = 10.5900,
        east: Double = 10.6100,
        ref: String = "KV$id",
    ) = """{"id":$id,"egenskaper":[{"id":2021,"verdi":$limit}],""" +
        """"geometri":{"wkt":"LINESTRING($west $lat, $east $lat)"},""" +
        """"lokasjon":{"vegsystemreferanser":[{"kortform":"$ref"}]}}"""

    /** Første oppslag setter i gang nedlastingen; treffet kommer når ruta er lastet. */
    private suspend fun firstMatch(
        repo: SpeedLimitRepository,
        position: LatLon,
        speedKmt: Double = 60.0,
        accuracyMeters: Double = 0.0,
    ): LimitMatch = withTimeout(10_000) {
        var match: LimitMatch? = null
        while (match == null) {
            match = repo.limitAt(position, heading, speedKmt, accuracyMeters)?.takeIf { !it.stale }
            if (match == null) delay(20)
        }
        match
    }

    // ---- GPS-nøyaktighet -------------------------------------------------------------------

    @Test
    fun `et haaploest fix matches ikke i det hele tatt`() = runBlocking {
        serve(road(1, 50, lat = 59.900000))
        val repo = repository()

        // ±80 m dekker hele krysset. Å matche på det er å trekke lodd blant vegene i det.
        val match = repo.limitAt(LatLon(59.900000, 10.600000), heading, 60.0, accuracyMeters = 80.0)

        assertNull("uten et forrige treff er «vet ikke» det ærlige svaret", match)
        assertEquals("og vi skal ikke engang laste ruta for det", 0, server.requestCount)
    }

    @Test
    fun `matchevinduet vokser med usikkerheten`() = runBlocking {
        // Vegen ligger 40 m unna. Med et fast vindu på 30 m er den utenfor rekkevidde, selv om
        // et fix på ±20 m godt kan ligge 40 m fra vegen det faktisk er på.
        serve(road(1, 70, lat = 59.90035993))
        val repo = repository()
        val position = LatLon(59.900000, 10.600000)

        val wide = firstMatch(repo, position, accuracyMeters = 20.0)
        assertEquals(70, wide.limitKmt)
        assertEquals(40.0, wide.distanceMeters, 1.5)

        // Samme posisjon, men nå uten slingringsmonn: ingen treff, og vi faller tilbake på det
        // forrige i stedet for å påstå at det ikke finnes fartsgrenser her.
        val narrow = repo.limitAt(position, heading, 60.0, accuracyMeters = 0.0)
        assertNotNull(narrow)
        assertTrue("30 m-vinduet skal bomme på en veg 40 m unna", narrow!!.stale)
    }

    @Test
    fun `et upresist fix kan ikke gi et sikkert treff`() = runBlocking {
        serve(road(1, 50, lat = 59.900000))
        val repo = repository()

        val match = firstMatch(repo, LatLon(59.900000, 10.600000), accuracyMeters = 30.0)

        assertEquals(50, match.limitKmt)
        assertEquals(
            "vi ligger midt i vegbanen, men vet det ikke sikkert nok",
            MatchConfidence.LOW,
            match.confidence,
        )
    }

    // ---- tillit ----------------------------------------------------------------------------

    @Test
    fun `en enslig veg rett under oss er et sikkert treff`() = runBlocking {
        serve(road(1, 50, lat = 59.900000))
        val repo = repository()

        val match = firstMatch(repo, LatLon(59.900000, 10.600000))

        assertEquals(50, match.limitKmt)
        assertEquals(MatchConfidence.HIGH, match.confidence)
    }

    @Test
    fun `to grenser like naer er et myntkast, ikke et faktum`() = runBlocking {
        // 4,4 m mellom vegene: begge innenfor vinduet, og vinneren avgjøres av tideler.
        serve(
            road(1, 50, lat = 59.900000),
            road(2, 30, lat = 59.90003959),
        )
        val repo = repository()

        val match = firstMatch(repo, LatLon(59.90001800, 10.600000))

        assertEquals(50, match.limitKmt)
        assertEquals(
            "et treff som avgjøres av tideler skal ikke se sikkert ut",
            MatchConfidence.LOW,
            match.confidence,
        )
    }

    // ---- segment-hysterese -----------------------------------------------------------------

    @Test
    fun `fartsgrensen bytter ikke paa en marginal forskjell`() = runBlocking {
        // To parallelle veger 10 m fra hverandre - hovedveg og gang-/sykkelveg, eller en
        // avkjøringsrampe. Uten hysterese hopper skiltet mellom 50 og 30 fra fix til fix.
        serve(
            road(1, 50, lat = 59.900000),
            road(2, 30, lat = 59.90008998),
        )
        val repo = repository()

        val onFifty = firstMatch(repo, LatLon(59.900000, 10.600000))
        assertEquals(50, onFifty.limitKmt)

        // 6 m fra 50-vegen, 4 m fra 30-vegen: 30 vinner, men bare så vidt.
        val marginal = repo.limitAt(LatLon(59.90005399, 10.600000), heading, 60.0)
        assertEquals(
            "to meter er ikke nok til å bytte fartsgrense på skjermen",
            50,
            marginal!!.limitKmt,
        )
        assertTrue(
            "og et tall hysteresen holder igjen skal ikke framstå som sikkert",
            marginal.confidence != MatchConfidence.HIGH,
        )

        // 9,5 m fra 50-vegen, 0,5 m fra 30-vegen: nå er byttet utvetydig.
        val clear = repo.limitAt(LatLon(59.90008548, 10.600000), heading, 60.0)
        assertEquals(30, clear!!.limitKmt)
    }

    @Test
    fun `et ekte soneskifte holdes ikke igjen`() = runBlocking {
        // Samme veg, to strekninger etter hverandre: 80 fram til skiltet, 50 etter. Det er slik
        // NVDB deler dem, og segmentet du forlot slutter ved skiltet.
        serve(
            road(1, 80, lat = 59.900000, west = 10.6000, east = 10.6020),
            road(2, 50, lat = 59.900000, west = 10.6020, east = 10.6180),
        )
        val repo = repository()

        // Begge posisjonene i samme rute: dette handler om matchingen, ikke om nedlastingen.
        val before = firstMatch(repo, LatLon(59.900000, 10.601000), speedKmt = 80.0)
        assertEquals(80, before.limitKmt)

        // 56 m forbi skiltet: 80-segmentet slutter der, og er ute av matchevinduet.
        val after = repo.limitAt(LatLon(59.900000, 10.603000), heading, 80.0)
        assertEquals("den gamle grensen finnes ikke lenger her", 50, after!!.limitKmt)
        assertFalse("og det skal være et ekte treff, ikke et cachet ett", after.stale)
    }
}
