package no.synth.botometer.limit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NVDB oppgir `metadata.neste.start` også når det ikke er mer å hente, så et tomt token er ikke
 * stoppsignalet - den tomme siden er det.
 *
 * Da klienten stoppet på `neste == null` gikk hver eneste rute 25 runder, hentet 24 tomme sider,
 * og ble til slutt merket ufullstendig fordi løkka gikk tom for forsøk. Diagnostikken viste
 * «10 segmenter (UFULLSTENDIG)» - selvmotsigende - og hvert ruteoppslag kostet 25 kall mot
 * Vegvesenets åpne API i stedet for ett.
 */
class NvdbClientPaginationTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun client() = NvdbClient(
        clientName = "botometer-test",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private val bbox = BBox(west = 11.80, south = 59.74, east = 11.82, north = 59.76)

    /** Ett gyldig fartsgrensesegment, med geometri innenfor Norge så Wkt godtar det. */
    private fun objekt(id: Int, limit: Int = 80) =
        """{"id":$id,"egenskaper":[{"id":2021,"verdi":$limit}],""" +
            """"geometri":{"wkt":"LINESTRING(11.80 59.74, 11.81 59.75)"}}"""

    private fun side(objekter: String, neste: String?) = MockResponse().setBody(
        """{"objekter":[$objekter],"metadata":{""" +
            (if (neste != null) """"neste":{"start":"$neste"}""" else "") +
            "}}"
    )

    @Test
    fun `en tom side avslutter pagineringen, og ruta er komplett`() {
        server.enqueue(side(objekt(1), neste = "side2"))
        server.enqueue(side("", neste = "side3"))   // tom, men NVDB tilbyr fortsatt et token

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertEquals(1, data.segments.size)
        assertTrue("en tom side betyr slutt, ikke ufullstendige data", data.complete)
        assertEquals("skulle stoppet etter den tomme siden", 2, server.requestCount)
    }

    @Test
    fun `et token som ikke flytter seg stopper oss`() {
        // Ellers henter vi samme side om og om igjen til sidegrensen.
        server.enqueue(side(objekt(1), neste = "samme"))
        server.enqueue(side(objekt(2), neste = "samme"))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertTrue(data.complete)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `flere fulle sider hentes fortsatt`() {
        // Pagineringen skal ikke bli så forsiktig at den mister data i tette byruter.
        server.enqueue(side(objekt(1), neste = "s2"))
        server.enqueue(side(objekt(2), neste = "s3"))
        server.enqueue(side(objekt(3), neste = null))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertEquals(3, data.segments.size)
        assertTrue(data.complete)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `start-tokenet sendes videre`() {
        server.enqueue(side(objekt(1), neste = "token-2"))
        server.enqueue(side("", neste = null))

        runBlocking { client().speedLimitsIn(bbox) }

        server.takeRequest()
        assertTrue(
            "andre kall må bære start-tokenet",
            server.takeRequest().path!!.contains("start=token-2"),
        )
    }
}
