package no.synth.botometer.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import no.synth.botometer.BuildConfig
import no.synth.botometer.speed.Tracking
import no.synth.botometer.speed.TrackingHolders

class BotometerCarAppService : CarAppService() {

    /**
     * Uten vertsvalidering kan en vilkårlig app på telefonen binde seg til denne tjenesten og
     * lese posisjonen din. Det er ekstra viktig her: en sideloadet APK har ingen Play-signatur
     * som knytter den til et opphav, så validering er den eneste kontrollen som gjenstår.
     *
     * Flagget settes i build.gradle.kts og er `false` i alle release-bygg, uavhengig av
     * hvilken nøkkel de er signert med.
     */
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.ALLOW_ALL_CAR_HOSTS) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = BotometerSession()
}

/**
 * Økta, ikke skjermen, er det som holder på posisjonssporingen.
 *
 * Verten stopper skjermen vår i det Google Maps tar over bilskjermen, men økta lever videre -
 * og det er nettopp da fartsvarslene er den eneste flaten som er igjen. Fulgte sporingen
 * skjermen, døde varslene i samme øyeblikk som de ble det eneste appen hadde å si.
 *
 * Økta destrueres når appen avsluttes i bilen eller telefonen kobles fra. Det er der turen er
 * over, og det er der sporingen skal slippes.
 */
class BotometerSession : Session(), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen = SpeedometerScreen(carContext)

    override fun onDestroy(owner: LifecycleOwner) {
        Tracking.release(TrackingHolders.Holder.CAR)
    }
}
