package no.synth.botometer

import kotlinx.coroutines.runBlocking
import no.synth.botometer.fine.FineTable
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.fine.TableStatus
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * Dekker oppdateringskanalen for satsene: når tabellen regnes som utdatert (som er det telefon-
 * appen automatisk henter på), hvilken av cache og asset som vinner, og at en ødelagt respons
 * ikke får lov til å overleve en restart.
 */
class FineTableRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private val assetJson = File("src/main/assets/botesatser.json").readText()
    private val asset = FineTable.fromJson(assetJson)
    private val assetDate = LocalDate.parse(asset.versjon)

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun repo(today: LocalDate) = FineTableRepository(
        filesDir = temp.root,
        assetTable = { asset },
        remoteUrl = server.url("/botesatser.json").toString(),
        today = { today },
    )

    /** Samme tabell, men med en annen `versjon`, slik at nyeste-vinner-regelen kan testes. */
    private fun tableDated(date: String) = assetJson.replaceFirst(asset.versjon, date)

    private fun cache(json: String) = File(temp.root, "botesatser-cache.json").writeText(json)

    @Test
    fun `uten cache brukes asseten`() {
        val status = repo(assetDate).load()

        assertEquals(TableStatus.Origin.ASSET, status.origin)
        assertEquals(asset.versjon, status.table.versjon)
        assertEquals(0L, status.ageDays)
        assertFalse(status.stale)
    }

    @Test
    fun `satser under 400 dager gamle er ikke utdaterte`() {
        val status = repo(assetDate.plusDays(400)).load()

        assertEquals(400L, status.ageDays)
        assertFalse(status.stale)
    }

    @Test
    fun `satser over 400 dager gamle flagges som utdaterte`() {
        // Dette er flagget telefon-appen henter nye satser på ved oppstart.
        val status = repo(assetDate.plusDays(401)).load()

        assertEquals(401L, status.ageDays)
        assertTrue(status.stale)
    }

    @Test
    fun `nedlastede satser vinner naar de er nyere enn asseten`() {
        cache(tableDated("2027-01-01"))

        val status = repo(assetDate).load()

        assertEquals(TableStatus.Origin.DOWNLOADED, status.origin)
        assertEquals("2027-01-01", status.table.versjon)
    }

    @Test
    fun `asseten vinner over en eldre cache`() {
        // En ny APK skal overstyre satser som ble lastet ned før oppgraderingen.
        cache(tableDated("2020-01-01"))

        val status = repo(assetDate).load()

        assertEquals(TableStatus.Origin.ASSET, status.origin)
        assertEquals(asset.versjon, status.table.versjon)
    }

    @Test
    fun `en uleselig cache faller tilbake paa asseten`() {
        cache("{ dette er ikke JSON")

        val status = repo(assetDate).load()

        assertEquals(TableStatus.Origin.ASSET, status.origin)
    }

    @Test
    fun `refresh henter nye satser og gjor dem gjeldende`() {
        server.enqueue(MockResponse().setBody(tableDated("2027-03-01")))

        val result = runBlocking { repo(assetDate.plusDays(401)).refresh() }

        val status = result.getOrThrow()
        assertEquals("2027-03-01", status.table.versjon)
        assertEquals(TableStatus.Origin.DOWNLOADED, status.origin)
        // Nye satser er ferske satser: advarselen i appen skal forsvinne av seg selv.
        assertFalse(status.stale)
    }

    @Test
    fun `refresh overlever en restart`() {
        server.enqueue(MockResponse().setBody(tableDated("2027-03-01")))
        runBlocking { repo(assetDate).refresh() }

        assertEquals("2027-03-01", repo(assetDate).load().table.versjon)
    }

    @Test
    fun `en feilende respons skriver ingenting til disk`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = runBlocking { repo(assetDate).refresh() }

        assertTrue(result.isFailure)
        assertEquals(asset.versjon, repo(assetDate).load().table.versjon)
    }

    @Test
    fun `odelagt JSON caches ikke`() {
        // Uten dette ville en ødelagt respons overlevd restart og satt appen ut av spill.
        server.enqueue(MockResponse().setBody("{ ikke JSON"))

        val result = runBlocking { repo(assetDate).refresh() }

        assertTrue(result.isFailure)
        assertFalse(File(temp.root, "botesatser-cache.json").exists())
    }

    @Test
    fun `en tabell uten grupper avvises`() {
        // Et tomt svar ville gitt en app som stille lot være å beregne bøter.
        server.enqueue(MockResponse().setBody(assetJson.replaceFirst(Regex("\"grupper\"\\s*:\\s*\\["), "\"grupper\": [],\"ubrukt\": [")))

        val result = runBlocking { repo(assetDate).refresh() }

        assertTrue(result.isFailure)
        assertFalse(File(temp.root, "botesatser-cache.json").exists())
    }

    @Test
    fun `ugyldig versjonsdato gir ingen alder og ingen falsk advarsel`() {
        cache(tableDated("ikke-en-dato"))

        val status = repo(assetDate).load()

        // Asseten har gyldig dato og vinner, så alderen er fortsatt kjent.
        assertEquals(TableStatus.Origin.ASSET, status.origin)

        val onlyBadDates = FineTableRepository(
            filesDir = temp.root,
            assetTable = { FineTable.fromJson(tableDated("ikke-en-dato")) },
            remoteUrl = server.url("/").toString(),
            today = { assetDate },
        )
        assertNull(onlyBadDates.load().ageDays)
        assertFalse(onlyBadDates.load().stale)
    }
}
