package no.synth.botometer

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.fine.FineTable
import no.synth.botometer.fine.FineTableRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Oppstartsoppdateringen i [MainActivity.refreshIfStale] er den eneste kanalen som gjør noe uten
 * at brukeren ber om det. Da må det også være verifisert at den holder seg i ro når satsene er
 * ferske, og at den ikke tar med seg appen i fallet når nettet svarer stygt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestBotometerApp::class)
class MainActivityRefreshTest {

    private val assetJson = File("src/main/assets/botesatser.json").readText()
    private val asset = FineTable.fromJson(assetJson)
    private val assetDate = LocalDate.parse(asset.versjon)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val cacheFile: File get() = File(context.filesDir, "botesatser-cache.json")

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
        cacheFile.delete()
    }

    @After fun stop() = server.shutdown()

    /**
     * Peker aktiviteten mot den lokale serveren, med en styrt oppfatning av hvilken dag det er.
     * Robolectric lager en ny Application per test, så dette lekker ikke videre.
     */
    private fun pointAppAt(today: LocalDate) {
        ApplicationProvider.getApplicationContext<TestBotometerApp>().repository = {
            FineTableRepository(
                filesDir = context.filesDir,
                assetTable = { asset },
                remoteUrl = server.url("/botesatser.json").toString(),
                today = { today },
            )
        }
    }

    private fun tableDated(date: String) = assetJson.replaceFirst(asset.versjon, date)

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Feltene bygges i kode og har ingen id-er, så teksten plukkes ut av view-treet.
     *
     * Alle tekstfeltene, ikke det første: skjermen er delt i overskrift, problemer, innstillinger
     * og en diagnostikkrapport, og hvilket felt en opplysning havner i er en layoutdetalj. En
     * test som henger på «det første tekstfeltet» tester rekkefølgen på visningen, ikke at
     * satsene ble oppdatert.
     *
     * @param onlyVisible tar bare med det brukeren faktisk ser. Diagnostikken er skjult til man
     * ber om den, og noe som står der er ikke «vist».
     */
    private fun textOf(activity: MainActivity, onlyVisible: Boolean): String {
        fun collect(view: View, into: StringBuilder) {
            if (onlyVisible && view.visibility != View.VISIBLE) return
            when (view) {
                is TextView -> into.appendLine(view.text)
                is ViewGroup -> (0 until view.childCount).forEach { collect(view.getChildAt(it), into) }
                else -> Unit
            }
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        return buildString { collect(root, this) }
    }

    @Test
    fun `utdaterte satser hentes ved oppstart`() {
        pointAppAt(assetDate.plusDays(401))
        server.enqueue(MockResponse().setBody(tableDated("2027-03-01")))

        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull("appen skulle spurt om nye satser", server.takeRequest(5, TimeUnit.SECONDS))
            idleMainLooper()

            assertTrue(cacheFile.exists())
            assertEquals("2027-03-01", FineTable.fromJson(cacheFile.readText()).versjon)
        }
    }

    @Test
    fun `ferske satser gir ingen nettverkstrafikk`() {
        // Poenget med å styre på `stale`: appen skal ikke ringe hjem ved hver eneste oppstart.
        pointAppAt(assetDate)

        ActivityScenario.launch(MainActivity::class.java).use {
            idleMainLooper()

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `satser paa grensen til utdatert hentes ikke`() {
        pointAppAt(assetDate.plusDays(400))

        ActivityScenario.launch(MainActivity::class.java).use {
            idleMainLooper()

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `en feilende oppdatering tar ikke med seg appen`() {
        // Brukeren har ikke bedt om oppdateringen, så en død server skal ikke merkes.
        pointAppAt(assetDate.plusDays(401))
        server.enqueue(MockResponse().setResponseCode(500))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            idleMainLooper()

            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            assertFalse("en feilet nedlasting skal ikke cache noe", cacheFile.exists())
        }
    }

    @Test
    fun `oppdaterte satser vises uten at brukeren gjor noe`() {
        pointAppAt(assetDate.plusDays(401))
        server.enqueue(MockResponse().setBody(tableDated("2027-03-01")))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            idleMainLooper()

            // render() kalles på nytt etter nedlastingen, ellers ville advarselen blitt staaende
            // til brukeren forlot skjermen.
            scenario.onActivity { activity ->
                // Den nye versjonen skal stå der brukeren ser den, ikke bare i rapporten.
                assertTrue(
                    "den nye satsversjonen skal være synlig med én gang",
                    textOf(activity, onlyVisible = true).contains("2027-03-01"),
                )

                val all = textOf(activity, onlyVisible = false)
                assertTrue("satsene skal komme fra nett nå", all.contains("hentet fra nett"))
                assertFalse(
                    "advarselen om gamle satser skal ikke bli stående etter oppdateringen",
                    all.contains("over ett år", ignoreCase = true),
                )
            }
        }
    }
}

/** Byttes inn via @Config, slik at produksjonskoden slipper en testsøm. */
class TestBotometerApp : BotometerApp() {
    var repository: (() -> FineTableRepository)? = null

    override fun createFineTableRepository(): FineTableRepository =
        repository?.invoke() ?: super.createFineTableRepository()
}
