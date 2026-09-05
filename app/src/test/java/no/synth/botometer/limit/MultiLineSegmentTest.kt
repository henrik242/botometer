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
 * Et vegobjekt med MULTILINESTRING-geometri er flere adskilte strekninger med samme fartsgrense.
 *
 * De ble tidligere slått sammen til én punktliste. Kart-matchingen binder sammen punkt i og i+1,
 * så den trakk en rett linje fra enden av én strekning til starten av den neste - en veg som
 * ikke finnes, men som er like matchbar som en ekte.
 *
 * Feilen gikk alltid samme vei. En 30-sone i et tettsted er mange korte, adskilte strekninger;
 * en 80-veg er én lang. Byens fantomlinjer spente derfor på kryss og tvers over hovedvegen, og
 * lot den lave grensen vinne på avstand. Symptomet var «finner 30 eller 50 der det er 70 eller
 * 80», konsekvent.
 */
class MultiLineSegmentTest {

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

    /** To korte strekninger med en luke mellom - typisk 30-sone gjennom et tettsted. */
    private val toDelstrekninger =
        "MULTILINESTRING((11.800 59.740, 11.801 59.740), (11.815 59.755, 11.816 59.755))"

    private fun svar(wkt: String) = MockResponse().setBody(
        """{"objekter":[{"id":1,"egenskaper":[{"id":2021,"verdi":30}],""" +
            """"geometri":{"wkt":"$wkt"}}],"metadata":{}}"""
    )

    @Test
    fun `hver delstrekning blir sitt eget segment`() {
        server.enqueue(svar(toDelstrekninger))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertEquals("to adskilte strekninger er to segmenter", 2, data.segments.size)
        data.segments.forEach {
            assertEquals("hvert segment er ett linjestykke, altså to punkt", 2, it.line.size)
        }
    }

    @Test
    fun `ingen fantomlinje spenner over luka`() {
        server.enqueue(svar(toDelstrekninger))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        // Luka er ~1,2 km. Ingen ekte punktpar i svaret ligger mer enn noen titalls meter fra
        // hverandre, så et langt sprang betyr at to strekninger er limt sammen.
        data.segments.forEach { seg ->
            for (i in 0 until seg.line.size - 1) {
                val meters = Geo.haversineMeters(seg.line[i], seg.line[i + 1])
                assertTrue(
                    "linjestykke på ${"%.0f".format(meters)} m spenner over luka mellom to strekninger",
                    meters < 500,
                )
            }
        }
    }

    @Test
    fun `alle delstrekninger arver fartsgrense og vegreferanse`() {
        server.enqueue(svar(toDelstrekninger))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertTrue(data.segments.all { it.limitKmt == 30 })
        assertTrue("samme vegobjekt, samme id", data.segments.all { it.nvdbId == 1L })
    }

    @Test
    fun `en enkel LINESTRING gir fortsatt ett segment`() {
        server.enqueue(svar("LINESTRING(11.800 59.740, 11.801 59.741, 11.802 59.742)"))

        val data = runBlocking { client().speedLimitsIn(bbox) }

        assertEquals(1, data.segments.size)
        assertEquals(3, data.segments.single().line.size)
    }
}
