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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Satsen for 36-40 km/t over i en 90-sone gjelder BARE motorveg. Uten et oppslag var svaret
 * alltid «vet ikke», og appen måtte merke beløpet som usikkert i det ene tilfellet der forskjellen
 * er 16 700 kroner mot anmeldelse og tap av førerrett.
 *
 * Vegobjekttype 595 «Motorveg» dekker strekninger med vedtatt motorvegstatus; egenskap 5378
 * «Motorvegtype» skiller motorveg (7355) fra motortrafikkveg (7356), og motortrafikkveg er ikke
 * motorveg i denne sammenhengen.
 *
 * NVDB oppgir enum-egenskaper med både kode og tekst. Testene her låser at begge former forstås,
 * og at et objekt uten lesbar Motorvegtype fortsatt teller som motorveg - typen heter tross alt
 * det, og å gjette «ikke motorveg» ville flyttet en bot til anmeldelse.
 */
@RunWith(RobolectricTestRunner::class)
class MotorwayLookupTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private val position = LatLon(59.900000, 10.600000)
    private val heading = 90.0

    @Before fun start() {
        server = MockWebServer()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private val nittiSone =
        """{"id":1,"egenskaper":[{"id":2021,"verdi":90}],""" +
            """"geometri":{"wkt":"LINESTRING(10.5900 59.900000, 10.6100 59.900000)"},""" +
            """"lokasjon":{"vegsystemreferanser":[{"kortform":"EV6"}]}}"""

    /** Motorvegobjekt oppå samme linje. [egenskaper] er JSON-en for egenskapslista. */
    private fun motorveg(egenskaper: String) =
        """{"id":9,"egenskaper":$egenskaper,""" +
            """"geometri":{"wkt":"LINESTRING(10.5900 59.900000, 10.6100 59.900000)"}}"""

    /**
     * Ruteoppslagene skilles på vegobjekttypen i stien: 105 er fartsgrense, 595 er motorveg.
     * Det er nettopp det oppdelte oppslaget som er poenget - motorveg hentes bare når det trengs.
     */
    private fun serve(motorwayObjects: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path.contains("/vegobjekter/595") ->
                        """{"objekter":[$motorwayObjects],"metadata":{}}"""
                    else -> """{"objekter":[$nittiSone],"metadata":{}}"""
                }
                return MockResponse().setBody(body)
            }
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

    /** Motorvegruta hentes først etter at fartsgrensen viste seg å være 90 eller høyere. */
    private suspend fun matchWithMotorway(repo: SpeedLimitRepository): LimitMatch =
        withTimeout(10_000) {
            var match: LimitMatch? = null
            while (match?.motorway == null) {
                match = repo.limitAt(position, heading, 120.0)?.takeIf { !it.stale }
                if (match?.motorway == null) delay(20)
            }
            match!!
        }

    @Test
    fun `motorveg kjennes igjen paa enum-koden`() = runBlocking {
        serve(motorveg("""[{"id":5378,"navn":"Motorvegtype","verdi":"Motorveg","enum_id":7355}]"""))

        val match = matchWithMotorway(repository())

        assertEquals(90, match.limitKmt)
        assertTrue(match.motorway!!)
    }

    @Test
    fun `motortrafikkveg er ikke motorveg`() = runBlocking {
        // Forskjellen er 16 700 kroner mot anmeldelse. Den skal ikke slurves bort.
        serve(motorveg("""[{"id":5378,"navn":"Motorvegtype","verdi":"Motortrafikkveg","enum_id":7356}]"""))

        val match = matchWithMotorway(repository())

        assertFalse(match.motorway!!)
    }

    @Test
    fun `motortrafikkveg kjennes igjen paa teksten alene`() = runBlocking {
        // Skulle enum_id mangle, er teksten det vi har.
        serve(motorveg("""[{"id":5378,"navn":"Motorvegtype","verdi":"Motortrafikkveg"}]"""))

        assertFalse(matchWithMotorway(repository()).motorway!!)
    }

    @Test
    fun `et objekt uten lesbar Motorvegtype teller som motorveg`() = runBlocking {
        // Vegobjekttype 595 HETER Motorveg. Skulle egenskapen mangle eller kodes om, er
        // typedefinisjonen det som fortsatt stemmer - og å gjette «ikke motorveg» ville
        // flyttet en bot til anmeldelse.
        serve(motorveg("[]"))

        assertTrue(matchWithMotorway(repository()).motorway!!)
    }

    @Test
    fun `ingen motorveg i ruta gir et ekte nei`() = runBlocking {
        serve("")

        assertFalse(
            "ruta er lastet og tom - det er et svar, ikke et fravær av svar",
            matchWithMotorway(repository()).motorway!!,
        )
    }

    @Test
    fun `motorveg slaas ikke opp i en 50-sone`() = runBlocking {
        // Ett datasett per rute er nok når satsen ikke skiller på vegtype. Vegvesenets API er
        // åpent og gratis, men ikke gratis å belaste.
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return MockResponse().setBody(
                    """{"objekter":[{"id":1,"egenskaper":[{"id":2021,"verdi":50}],""" +
                        """"geometri":{"wkt":"LINESTRING(10.5900 59.900000, 10.6100 59.900000)"}}],""" +
                        """"metadata":{}}"""
                )
            }
        }
        server.start()
        val repo = repository()

        val match = withTimeout(10_000) {
            var m: LimitMatch? = null
            while (m == null) {
                m = repo.limitAt(position, heading, 60.0)?.takeIf { !it.stale }
                if (m == null) delay(20)
            }
            m
        }

        assertEquals(50, match.limitKmt)
        assertNull("ingen grunn til å spørre om vegtype når satsen ikke bryr seg", match.motorway)

        assertTrue("bare fartsgrenser skal hentes", paths.none { it.contains("/vegobjekter/595") })
    }
}
