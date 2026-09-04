package no.synth.botometer

import android.app.Application
import no.synth.botometer.fine.FineTableRepository

/**
 * Samler konstruksjonen av det appen henter utenfra ett sted, i stedet for at aktiviteten
 * bygger sine egne avhengigheter. Det gir også testene et sted å bytte ut satskilden, uten
 * global tilstand som må ryddes opp etterpå: Robolectric lager en ny Application per test.
 */
open class BotometerApp : Application() {

    /** Application-context med vilje: repoet trenger bare filesDir og assets. */
    open fun createFineTableRepository(): FineTableRepository =
        FineTableRepository(this, getString(R.string.satser_url))
}
