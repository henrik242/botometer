package no.synth.botometer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uten Play Console finnes ingen crash-rapportering, og en telefon i en bil har ingen adb. Et
 * krasj etterlot seg derfor ingenting - appen forsvant, og neste oppstart så helt vanlig ut.
 * «Den krasjer av og til» er ikke noe å feilsøke på.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun crash(message: String) {
        CrashLog.install(context)
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), IllegalStateException(message))
    }

    @Test
    fun `stacktracen overlever krasjet`() {
        crash("noe gikk galt i bilen")

        val logged = CrashLog.read(context)!!
        assertTrue("meldingen må være med: $logged", logged.contains("noe gikk galt i bilen"))
        assertTrue("typen sier hva slags feil det var", logged.contains("IllegalStateException"))
        assertTrue("stacktracen peker på hvor", logged.contains("CrashLogTest"))
    }

    @Test
    fun `versjonen foelger med, ellers vet vi ikke hvilken kode som krasjet`() {
        crash("uansett")
        assertTrue(CrashLog.read(context)!!.contains(BuildConfig.VERSION_NAME))
    }

    @Test
    fun `ingenting aa lese naar ingenting har krasjet`() {
        CrashLog.clear(context)
        assertNull(CrashLog.read(context))
    }

    @Test
    fun `loggen kan toemmes naar den er rapportert`() {
        crash("rapportert og ferdig")
        assertTrue(CrashLog.read(context) != null)

        CrashLog.clear(context)
        assertNull(CrashLog.read(context))
    }

    @Test
    fun `siste krasj vinner`() {
        crash("gammel feil")
        crash("fersk feil")

        val logged = CrashLog.read(context)!!
        assertTrue(logged.contains("fersk feil"))
        assertTrue("en gammel stacktrace er villedende", !logged.contains("gammel feil"))
    }

    /**
     * Håndtereren må gi feilen videre. Svelger vi den, dør ikke prosessen slik Android forventer,
     * og appen blir stående halvdød - verre enn krasjet den skulle dokumentere.
     */
    @Test
    fun `den forrige handtereren blir kalt`() {
        var passedOn = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> passedOn = true }

        CrashLog.install(context)
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("videre"))

        assertTrue("kjeden må gå videre", passedOn)
    }
}
