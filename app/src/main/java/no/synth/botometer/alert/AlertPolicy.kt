package no.synth.botometer.alert

import no.synth.botometer.fine.FineEstimate

/**
 * Avgjør *når* det skal varsles. Ingen Android, ingen NotificationManager - bare regelen.
 *
 * Skilt fra [SpeedingAlerts] med vilje: om varselet faktisk når fram til skjermen er Androids
 * ansvar og vanskelig å teste meningsfullt, mens regelen om når vi har noe å si er vår egen og
 * den eneste delen det er verdt å låse. Da slipper testen også Robolectric.
 */
class AlertPolicy(private val now: () -> Long = System::currentTimeMillis) {

    sealed interface Decision {
        /** Nytt bøtenivå. Si fra. */
        data class Alert(val estimate: FineEstimate) : Decision

        /** Lovlig fart igjen. Et varsel som ikke lenger gjelder skal ikke bli stående. */
        data object Withdraw : Decision

        /** Ingenting nytt. Stille. */
        data object Ignore : Decision
    }

    private var lastBand: String? = null
    /** null = aldri varslet. 0 ville betydd «varslet ved epoch», som er noe annet. */
    private var lastAlertAtMs: Long? = null

    fun next(estimate: FineEstimate): Decision {
        val band = bandOf(estimate)

        if (band == null) {
            val hadAlert = lastBand != null
            lastBand = null
            return if (hadAlert) Decision.Withdraw else Decision.Ignore
        }

        // Samme nivå som sist: brukeren vet det allerede. Et varsel per GPS-fix er ikke et
        // varsel, det er støy.
        if (band == lastBand) return Decision.Ignore

        // Hysterese mangler (se README). Uten den vipper trinnet fram og tilbake på en grense,
        // og ti varsler er verre enn ingen. Minsteintervallet er den grove kuren.
        //
        // Det første varselet slipper alltid gjennom: det finnes ikke noe forrige varsel å ligge
        // for tett på.
        val since = lastAlertAtMs
        if (since != null && now() - since < MIN_INTERVAL_MS) return Decision.Ignore

        lastBand = band
        lastAlertAtMs = now()
        return Decision.Alert(estimate)
    }

    /** null = ingenting å varsle om. Ellers en nøkkel som endrer seg når nivået endrer seg. */
    private fun bandOf(estimate: FineEstimate): String? = when (estimate) {
        is FineEstimate.NoOffence, is FineEstimate.UnknownLimit -> null
        is FineEstimate.SimplifiedFine -> estimate.band
        is FineEstimate.Prosecution -> "anmeldelse"
    }

    companion object {
        /** Grovkornet vern mot maset ved en trinngrense, i påvente av ekte hysterese. */
        const val MIN_INTERVAL_MS = 20_000L
    }
}
