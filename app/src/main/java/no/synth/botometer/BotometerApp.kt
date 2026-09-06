package no.synth.botometer

import android.app.Application
import androidx.car.app.connection.CarConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.limit.NvdbClient
import no.synth.botometer.limit.SpeedLimitRepository
import no.synth.botometer.speed.GpsSpeedSource
import no.synth.botometer.speed.Tracking
import no.synth.botometer.speed.TrackingHolders

/**
 * Samler konstruksjonen av det appen henter utenfra ett sted, i stedet for at aktiviteten
 * bygger sine egne avhengigheter. Det gir også testene et sted å bytte ut satskilden, uten
 * global tilstand som må ryddes opp etterpå: Robolectric lager en ny Application per test.
 */
open class BotometerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Først av alt: et krasj under oppstart er også et krasj som skal kunne vises fram.
        CrashLog.install(this)
        followCarConnection()
    }

    /**
     * Sporingen følger BILFORBINDELSEN, ikke en skjerm.
     *
     * Begge de andre holderne er skjermer, og skjermer slukker. Kjører du med Maps på
     * bilskjermen og telefonskjermen går i svart - som den gjør av seg selv når telefonen
     * projiserer - så er `MainActivity.onStop` det siste som slipper, og appen slutter å varsle
     * nøyaktig i det varselet er den eneste flaten den har igjen. BilØKTA holder også, men den
     * finnes bare om Botometer faktisk ble åpnet i bilen den turen; gjorde den ikke det, var
     * telefonskjermen alene.
     *
     * `observeForever` og ikke en livssyklus: en observatør bundet til en aktivitet slutter å se
     * i samme øyeblikk som problemet oppstår. Denne lever så lenge prosessen gjør.
     *
     * Det betyr også at prosessen må være i live når du kobler til. Er appen helt død, starter
     * ingenting av seg selv - Android Auto vekker oss først når Botometer åpnes i bilen. Se
     * README.
     */
    private fun followCarConnection() {
        runCatching {
            CarConnection(this).type.observeForever { type ->
                val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
                    type == CarConnection.CONNECTION_TYPE_NATIVE
                if (connected && GpsSpeedSource.hasLocationPermission(this)) {
                    Tracking.acquire(this, TrackingHolders.Holder.CONNECTION)
                } else {
                    Tracking.release(TrackingHolders.Holder.CONNECTION)
                }
            }
        }.onFailure { Diagnostics.carConnectionFailed(it.message ?: it.javaClass.simpleName) }
    }

    /** Application-context med vilje: repoet trenger bare filesDir og assets. */
    open fun createFineTableRepository(): FineTableRepository =
        FineTableRepository(this, getString(R.string.satser_url))

    /**
     * Fartsgrensene deles av alle som viser dem: bilskjermen, telefonskjermen og fartsvarslene.
     *
     * Det er ikke bare ryddig. Rute-cachen ligger i instansen, så to repoer ville betydd to
     * cacher og dobbelt så mange kall mot Vegvesenets åpne API for nøyaktig samme ruter - og
     * hele poenget med tile-cachen er å ikke gjøre det.
     *
     * Satstabellen deles derimot ikke. Den lastes på nytt hos hver forbruker, slik at
     * «Oppdater satser» slår gjennom uten omstart av prosessen.
     */
    val speedLimits: SpeedLimitRepository by lazy {
        SpeedLimitRepository(
            nvdb = NvdbClient(clientName = getString(R.string.nvdb_client_id)),
            scope = CoroutineScope(SupervisorJob()),
        )
    }
}
