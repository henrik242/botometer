package no.synth.botometer.speed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bindeledd mellom foreground-servicen som eier GPS-abonnementet og bilskjermen som tegner.
 *
 * Hvorfor ikke bare la skjermen abonnere direkte: fra Android 10 får en app ikke posisjon når den
 * ikke er i forgrunnen, og en CarAppService gir ikke forgrunnsstatus. Abonnementet må derfor eies
 * av en foreground service, og skjermen leser resultatet herfra.
 *
 * StateFlow og ikke SharedFlow: skjermen skal ha siste kjente fix umiddelbart når den kobler seg
 * på igjen, ikke vente på neste GPS-oppdatering.
 */
object SpeedFeed {
    private val _fixes = MutableStateFlow<SpeedFix?>(null)
    val fixes: StateFlow<SpeedFix?> = _fixes.asStateFlow()

    internal fun publish(fix: SpeedFix) { _fixes.value = fix }

    /** Kalles når servicen stopper, så skjermen ikke viser en fart som er minutter gammel. */
    internal fun clear() { _fixes.value = null }
}
