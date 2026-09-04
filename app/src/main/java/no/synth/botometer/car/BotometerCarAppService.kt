package no.synth.botometer.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import no.synth.botometer.BuildConfig

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

class BotometerSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SpeedometerScreen(carContext)
}
