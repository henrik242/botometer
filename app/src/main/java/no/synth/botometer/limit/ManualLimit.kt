package no.synth.botometer.limit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fartsgrensen brukeren har satt for hånd, når NVDB ikke har noen.
 *
 * NVDB dekker ikke alt: private veger, ny veg som ikke er registrert ennå, og strekninger der
 * fartsgrenseobjektet mangler. Der viste appen «Ukjent fartsgrense» og sluttet å regne - altså
 * ingenting, akkurat der du helst vil vite. En grense satt for hånd er et anslag brukeren selv
 * står for, og det er bedre enn ingen.
 *
 * Prosess-globalt med vilje: bilskjermen og fartsvarslene skal regne på det samme som
 * telefonskjermen, ellers varsler appen om en bot den ikke viser. Bare telefonen kan *sette*
 * den - å velge fartsgrense i en bil i bevegelse er ikke noe appen skal invitere til.
 *
 * Den nullstilles når posisjonssporingen stopper ([no.synth.botometer.speed.LocationForegroundService]),
 * slik at neste tur alltid starter på automatikk. En manuell grense som overlever en tur er en
 * grense du har glemt at du satte.
 */
object ManualLimit {

    private val _kmt = MutableStateFlow<Int?>(null)

    /** null = automatikk (NVDB bestemmer). */
    val kmt: StateFlow<Int?> = _kmt.asStateFlow()

    /** De skiltede fartsgrensene i Norge. Ingen grunn til å la brukeren skrive inn 63. */
    val choices = listOf(30, 40, 50, 60, 70, 80, 90, 100, 110)

    fun set(kmt: Int?) {
        _kmt.value = kmt?.takeIf { it in choices }
    }

    fun clear() {
        _kmt.value = null
    }
}
