package no.synth.botometer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import no.synth.botometer.fine.FineTableRepository
import no.synth.botometer.limit.NvdbClient
import no.synth.botometer.limit.SpeedLimitRepository

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
