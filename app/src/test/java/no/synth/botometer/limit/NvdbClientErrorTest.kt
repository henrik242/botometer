package no.synth.botometer.limit

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * En feil brukeren ikke kan handle på er nesten like ille som ingen feil. «NVDB svarte 400» sier
 * at noe er permanent galt, men ikke hva - og appen har ingen crashlogg, brukeren ingen adb.
 * NVDB forklarer seg i feilkroppen; da må den også komme fram.
 */
class NvdbClientErrorTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun clientAgainstServer() = NvdbClient(
        clientName = "botometer-test",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private val bbox = BBox(west = 11.80, south = 59.74, east = 11.82, north = 59.76)

    @Test
    fun `forklaringen fra NVDB blir med i feilmeldingen`() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"melding":"segmentering er ikke en gyldig parameter"}""")
        )

        val e = assertThrows(NvdbException::class.java) {
            runBlocking { clientAgainstServer().speedLimitsIn(bbox) }
        }

        assertEquals(400, e.statusCode)
        assertTrue(
            "feilmeldingen må si hva NVDB klaget på, ikke bare tallet: ${e.message}",
            e.message!!.contains("segmentering er ikke en gyldig parameter"),
        )
    }

    @Test
    fun `400 proeves ikke paa nytt`() {
        // Backoff-logikken hviler på dette: en ugyldig forespørsel blir ikke gyldig av
        // gjentakelse, og 1 Hz GPS ville gitt 60 kall i minuttet mot et offentlig API.
        server.enqueue(MockResponse().setResponseCode(400).setBody("nei"))

        val e = assertThrows(NvdbException::class.java) {
            runBlocking { clientAgainstServer().speedLimitsIn(bbox) }
        }
        assertFalse(e.retryable)
    }

    @Test
    fun `5xx regnes som forbigaaende`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("nede"))

        val e = assertThrows(NvdbException::class.java) {
            runBlocking { clientAgainstServer().speedLimitsIn(bbox) }
        }
        assertTrue(e.retryable)
    }

    @Test
    fun `en tom feilkropp gir fortsatt en lesbar melding`() {
        server.enqueue(MockResponse().setResponseCode(400))

        val e = assertThrows(NvdbException::class.java) {
            runBlocking { clientAgainstServer().speedLimitsIn(bbox) }
        }
        assertEquals("NVDB svarte 400", e.message)
    }

    @Test
    fun `X-Client sendes, ellers struper Vegvesenet oss`() {
        server.enqueue(MockResponse().setBody("""{"objekter":[],"metadata":{}}"""))

        runBlocking { clientAgainstServer().speedLimitsIn(bbox) }

        assertEquals("botometer-test", server.takeRequest().getHeader("X-Client"))
    }
}
