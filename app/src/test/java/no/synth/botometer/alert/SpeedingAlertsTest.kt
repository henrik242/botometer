package no.synth.botometer.alert

import android.app.Application
import android.app.NotificationManager
import androidx.car.app.notification.CarAppExtender
import androidx.test.core.app.ApplicationProvider
import no.synth.botometer.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * De to varselbryterne hadde ingen tester. [AlertPolicyTest] dekker NÅR det varsles; dette
 * dekker HVORDAN - og det er der de to bryterne bor.
 *
 * Den viktigste er «varsler på bilskjermen». `CarNotificationManager.notify` legger PÅ en tom
 * `CarAppExtender` hvis varselet ikke har en, så et varsel postet gjennom den ville fått
 * bilvarselet tilbake uansett hva brukeren valgte. Koden går derfor bevisst utenom den når
 * bryteren er av - en omvei ingenting ville avslørt at forsvant.
 */
@RunWith(RobolectricTestRunner::class)
class SpeedingAlertsTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before fun defaults() {
        Settings(context).phoneAlerts = true
        Settings(context).carAlerts = true
    }

    @Test
    fun `begge varsler av poster ingenting`() {
        Settings(context).phoneAlerts = false
        Settings(context).carAlerts = false

        SpeedingAlerts(context).sendTestAlert()

        assertTrue(
            "ingen varsler skal postes når brukeren har slått av begge",
            shadowOf(manager).allNotifications.isEmpty(),
        )
    }

    @Test
    fun `bilvarsler av legger ikke ved bil-utvidelsen`() {
        // Uten dette er bryteren en no-op: CarNotificationManager ville lagt på en tom
        // CarAppExtender, og verten ville vist varselet likevel.
        Settings(context).carAlerts = false

        SpeedingAlerts(context).sendTestAlert()

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertFalse(
            "varselet skal IKKE være utvidet for bil når brukeren har slått det av",
            CarAppExtender.isExtended(posted.first()),
        )
    }

    @Test
    fun `et vanlig varsel gaar paa den hoye kanalen`() {
        Settings(context).carAlerts = false   // ellers går posteringen via bilverten

        SpeedingAlerts(context).sendTestAlert()

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals(SpeedingAlerts.CHANNEL_ID, posted.first().channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(SpeedingAlerts.CHANNEL_ID).importance,
        )
    }

    /** Et testvarsel som ser ut som en ekte bot er en løgn i varselpanelet to timer senere. */
    @Test
    fun `testvarselet sier at det er et testvarsel`() {
        Settings(context).carAlerts = false

        SpeedingAlerts(context).sendTestAlert()

        val title = shadowOf(manager).allNotifications.first()
            .extras.getString("android.title").orEmpty()
        assertTrue(title, title.contains("Testvarsel"))
    }
}
