package no.synth.botometer

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Når appen sideloades er brukeren sin egen support. Uten Play Console finnes det ingen
 * crash-rapporter og ingen ANR-statistikk - så appen må kunne fortelle hva den driver med.
 *
 * Dette vises på telefonen, aldri i bilen. Diagnostikk på bilskjermen under kjøring er
 * distraksjon uansett hvor nyttig den er.
 */
object Diagnostics {
    private val lastNvdbError = AtomicReference<String?>(null)
    private val lastTileInfo = AtomicReference<String?>(null)
    private val lastMatchInfo = AtomicReference<String?>(null)
    private val nvdbRequests = AtomicLong(0)
    private val nvdbFailures = AtomicLong(0)

    fun tileLoaded(segments: Int, complete: Boolean, key: String) {
        nvdbRequests.incrementAndGet()
        lastTileInfo.set("$key: $segments segmenter" + if (!complete) " (UFULLSTENDIG)" else "")
    }

    fun tileFailed(key: String, message: String, permanent: Boolean) {
        nvdbRequests.incrementAndGet()
        nvdbFailures.incrementAndGet()
        lastNvdbError.set("$key: $message" + if (permanent) " [permanent]" else "")
    }

    fun matched(limitKmt: Int, distanceMeters: Double, roadRef: String?) {
        lastMatchInfo.set("$limitKmt km/t, ${"%.1f".format(distanceMeters)} m unna" + (roadRef?.let { " · $it" } ?: ""))
    }

    fun summary(): String = buildString {
        appendLine("NVDB-kall: ${nvdbRequests.get()} (${nvdbFailures.get()} feilet)")
        appendLine("Siste rute: ${lastTileInfo.get() ?: "ingen ennå"}")
        appendLine("Siste treff: ${lastMatchInfo.get() ?: "ingen ennå"}")
        appendLine("Siste feil: ${lastNvdbError.get() ?: "ingen"}")
    }
}
