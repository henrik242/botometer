package no.synth.botometer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Manifestet er den eneste delen av bil-integrasjonen som ikke har en kompilator bak seg. Faller
 * en attributt ut, bygger appen fint, testene er grønne, og feilen viser seg først som en app som
 * ikke er der - i bilen, uten feilmelding noe sted.
 *
 * Nettopp label og icon på tjenesten har vært borte én gang. Verten bruker attributtene på selve
 * CarAppService til å representere appen i bilens system-UI, og arver dem ikke fra <application>.
 * Uten dem har den ingenting å tegne en oppføring med.
 */
@RunWith(RobolectricTestRunner::class)
class CarAppManifestTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Samme spørring som `pm query-services`, og som verten selv gjør. */
    @Suppress("DEPRECATION")
    private fun ownServices(intent: Intent) = context.packageManager
        .queryIntentServices(intent, PackageManager.GET_META_DATA)
        .filter { it.serviceInfo.packageName == context.packageName }

    @Test
    fun `CarAppService kan slås opp på sin action`() {
        assertEquals(
            "nøyaktig én CarAppService skal være deklarert",
            1,
            ownServices(Intent("androidx.car.app.CarAppService")).size,
        )
    }

    @Test
    fun `tjenesten har egen label og eget ikon`() {
        val info = ownServices(Intent("androidx.car.app.CarAppService")).single().serviceInfo

        assertNotEquals("android:label mangler på CarAppService", 0, info.labelRes)
        assertNotEquals("android:icon mangler på CarAppService", 0, info.icon)
    }

    @Test
    fun `tjenesten er eksportert`() {
        // Verten er en annen prosess. Uten exported kan den ikke binde seg.
        val info = ownServices(Intent("androidx.car.app.CarAppService")).single().serviceInfo
        assertTrue("CarAppService må være exported", info.exported)
    }

    @Test
    fun `NAVIGATION er deklarert som kategori`() {
        // Den eneste kategorien som gir en fri tegneflate. Byttes den ut, dukker appen opp og er
        // ubrukelig - speedometeret har ingenting å tegne på.
        val medKategori = ownServices(
            Intent("androidx.car.app.CarAppService")
                .addCategory("androidx.car.app.category.NAVIGATION")
        )
        assertEquals("NAVIGATION-kategorien mangler i intent-filteret", 1, medKategori.size)
    }

    @Test
    fun `metadata verten leser før den binder seg er på plass`() {
        @Suppress("DEPRECATION")
        val meta = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData

        assertEquals(3, meta.getInt("androidx.car.app.minCarApiLevel"))
        assertNotEquals(
            "com.google.android.gms.car.application må peke på automotive_app_desc",
            0,
            meta.getInt("com.google.android.gms.car.application"),
        )
    }
}
