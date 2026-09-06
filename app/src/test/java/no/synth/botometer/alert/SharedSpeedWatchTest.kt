package no.synth.botometer.alert

import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.BotometerApp
import no.synth.botometer.fine.FineTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SpeedWatch] har alltid sagt at de tre flatene deler regnestykket. De gjorde det ikke - hver
 * flate lagde sitt eget eksemplar - og den dyreste følgen var stille: fartsvarslene beholdt
 * satstabellen slik den var da tjenesten startet, så «Oppdater satser» nådde dem aldri. Appen
 * kunne varsle om et beløp den ikke viste noe sted.
 */
@RunWith(RobolectricTestRunner::class)
class SharedSpeedWatchTest {

    private val app: BotometerApp get() = ApplicationProvider.getApplicationContext()
    private val cacheFile: File get() = File(app.filesDir, "botesatser-cache.json")

    @After fun cleanup() {
        cacheFile.delete()
    }

    /**
     * Det er DELINGEN som er poenget. Tre eksemplarer betydde tre hystereser, tre
     * `lastMatch`-er og tre satstabeller - altså tre svar på ett spørsmål.
     */
    @Test
    fun `flatene deler ett eksemplar`() {
        assertSame(app.speedWatch, app.speedWatch)
    }

    @Test
    fun `oppdaterte satser slaar gjennom uten omstart`() {
        val watch = app.speedWatch
        val before = watch.calculator.version
        assertNotEquals("testen forutsetter at asseten ikke er datert 2099", "2099-01-01", before)

        // Samme vei «Oppdater satser» går: en nyere tabell i disk-cachen.
        val asset = FineTable.fromJson(
            app.assets.open("botesatser.json").use { it.readBytes().decodeToString() }
        )
        cacheFile.writeText(
            app.assets.open("botesatser.json").use { it.readBytes().decodeToString() }
                .replaceFirst(asset.versjon, "2099-01-01")
        )

        watch.reloadRates()

        assertEquals("2099-01-01", watch.calculator.version)
    }

    /**
     * Og uten [SpeedWatch.reloadRates] blir den gamle tabellen stående. Testen finnes for å
     * gjøre det tydelig at det er kallet, ikke fila, som er koblingen - fjerner noen kallet fra
     * «Oppdater satser», er det denne forventningen som skal se rar ut.
     */
    @Test
    fun `en ny fil alene er ikke nok`() {
        val watch = app.speedWatch
        val before = watch.calculator.version

        cacheFile.writeText(
            app.assets.open("botesatser.json").use { it.readBytes().decodeToString() }
                .replaceFirst(before, "2099-01-01")
        )

        assertEquals("satsene byttes først når noen ber om det", before, watch.calculator.version)
    }
}
