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
    private val lastMissInfo = AtomicReference<String?>(null)
    private val lastCandidates = AtomicReference<String?>(null)
    private val lastMotorwayInfo = AtomicReference<String?>(null)
    private val lastAccuracyInfo = AtomicReference<String?>(null)
    private val locationServiceError = AtomicReference<String?>(null)
    private val nvdbRequests = AtomicLong(0)
    private val nvdbFailures = AtomicLong(0)

    private val alertsPosted = AtomicLong(0)
    private val alertsFailed = AtomicLong(0)
    private val lastAlert = AtomicReference<String?>(null)
    private val lastAlertError = AtomicReference<String?>(null)
    private val carSession = AtomicReference<String?>(null)
    private val carSessionActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Posisjonssporingen kan nektes uten at brukeren merker noe annet enn at farten står stille.
     * Da må appen si hva som skjedde, og hva som fikser det.
     */
    fun locationServiceFailed(message: String) {
        locationServiceError.set(message)
    }

    fun locationServiceStarted() {
        locationServiceError.set(null)
    }

    fun tileLoaded(segments: Int, complete: Boolean, key: String) {
        nvdbRequests.incrementAndGet()
        lastTileInfo.set("$key: $segments segmenter" + if (!complete) " (UFULLSTENDIG)" else "")
    }

    fun tileFailed(key: String, message: String, permanent: Boolean) {
        nvdbRequests.incrementAndGet()
        nvdbFailures.incrementAndGet()
        lastNvdbError.set("$key: $message" + if (permanent) " [permanent]" else "")
    }

    /**
     * Et bom er like mye informasjon som et treff, og uten det står brukeren igjen med «ingen
     * treff ennå» - som ikke skiller mellom «ruta er tom», «nærmeste veg er 80 meter unna» og
     * «kursfilteret forkastet riktig segment».
     */
    fun missed(segments: Int, nearestMeters: Double, rejectedByHeading: Boolean) {
        lastMissInfo.set(
            buildString {
                append("ingen treff blant $segments segmenter")
                if (nearestMeters.isFinite()) append(", nærmeste ${"%.0f".format(nearestMeters)} m")
                if (rejectedByHeading) append(", forkastet av kursfilteret")
            }
        )
    }

    /**
     * Vinneren alene sier ikke hvorfor den vant. Plukker appen 30 der det er 80, er spørsmålet
     * hva 80-vegen lå på av avstand og kursavvik - og det svaret finnes bare her.
     */
    fun candidates(lines: List<String>) {
        lastCandidates.set(lines.takeIf { it.isNotEmpty() }?.joinToString("\n  "))
    }

    fun matched(
        limitKmt: Int,
        distanceMeters: Double,
        roadRef: String?,
        confidence: String,
        motorway: Boolean?,
    ) {
        lastMatchInfo.set(
            buildString {
                append("$limitKmt km/t, ${"%.1f".format(distanceMeters)} m unna")
                roadRef?.let { append(" · $it") }
                append(" · tillit $confidence")
                motorway?.let { append(if (it) " · motorveg" else " · ikke motorveg") }
            }
        )
    }

    /**
     * Vegobjekttype 595 hentes bare i 90-soner og over. Står den på 0 segmenter der du VET at
     * du kjørte motorveg, er det enten dekningshull i NVDB eller en gal antakelse om hvordan
     * Motorvegtype kodes - og satsen for 36-40 km/t over henger på svaret.
     */
    fun motorwayTileLoaded(segments: Int, key: String) {
        nvdbRequests.incrementAndGet()
        lastMotorwayInfo.set("$key: $segments motorvegsegmenter")
    }

    /**
     * Et fix på ±60 m kan ikke skille to parallelle veger, og matchingen hoppes over. Uten denne
     * linja ser det ut som om NVDB mangler data, når det er GPS-en som ikke ser noe.
     */
    fun poorAccuracy(meters: Double) {
        lastAccuracyInfo.set("hoppet over matching: GPS ±${"%.0f".format(meters)} m")
    }

    /**
     * Bilverten viser bare varsler for en app som faktisk kjører i bilen. Var økta død da
     * varselet ble postet, er det hele forklaringen - og det er ikke synlig noe annet sted.
     */
    fun carSessionCreated() {
        carSessionActive.set(true)
        carSession.set("opprettet ${klokke()}")
    }

    fun carSessionDestroyed() {
        carSessionActive.set(false)
        carSession.set("avsluttet ${klokke()}")
    }

    val carSessionIsActive: Boolean get() = carSessionActive.get()

    fun carSessionLine(): String =
        if (carSessionActive.get()) "✓ Bil-økt: aktiv (${carSession.get()})"
        else "✗ Bil-økt: ikke aktiv" + (carSession.get()?.let { " (sist $it)" } ?: " (aldri startet)")

    /**
     * @param carActive om bil-økta var i live i det varselet ble postet. Det er nettopp den
     * kombinasjonen som avgjør om verten hadde noen mulighet til å vise det.
     */
    fun alertPosted(title: String, text: String, carActive: Boolean) {
        alertsPosted.incrementAndGet()
        lastAlert.set(
            "«$title · $text» ${klokke()}" + if (carActive) " (bil-økt aktiv)" else " (INGEN bil-økt)"
        )
    }

    /**
     * Posteringen var pakket i runCatching og forsvant i stillhet. En feil ingen ser er verre
     * enn en feil - da ser det ut som om varselet ble sendt.
     */
    fun alertFailed(message: String) {
        alertsFailed.incrementAndGet()
        lastAlertError.set("$message ${klokke()}")
    }

    fun alertLines(): String = buildString {
        appendLine("· Varsler sendt: ${alertsPosted.get()} (${alertsFailed.get()} feilet)")
        appendLine("· Siste varsel: ${lastAlert.get() ?: "ingen ennå"}")
        lastAlertError.get()?.let { appendLine("✗ Siste varselfeil: $it") }
    }

    private fun klokke(): String =
        java.time.LocalTime.now().withNano(0).toString()

    fun summary(): String = buildString {
        locationServiceError.get()?.let {
            appendLine("⚠ POSISJONSSPORING STOPPET")
            appendLine("  $it")
            appendLine("  Posisjonstilgangen er «mens appen er i bruk». Fra Android 14 får en app")
            appendLine("  med den tilgangen bare starte posisjonssporing mens den selv er i")
            appendLine("  forgrunnen - og bilskjermen teller ikke. Se README: appen må enten")
            appendLine("  startes fra telefonen først, eller be om «Tillat alltid».")
            appendLine()
        }
        appendLine("NVDB-kall: ${nvdbRequests.get()} (${nvdbFailures.get()} feilet)")
        appendLine("Siste rute: ${lastTileInfo.get() ?: "ingen ennå"}")
        appendLine("Siste treff: ${lastMatchInfo.get() ?: "ingen ennå"}")
        appendLine("Siste bom: ${lastMissInfo.get() ?: "ingen"}")
        appendLine("Kandidater:")
        appendLine("  ${lastCandidates.get() ?: "ingen ennå"}")
        appendLine("Motorveg (595): ${lastMotorwayInfo.get() ?: "ikke slått opp (bare i 90-soner)"}")
        lastAccuracyInfo.get()?.let { appendLine("GPS-nøyaktighet: $it") }
        appendLine("Siste feil: ${lastNvdbError.get() ?: "ingen"}")
    }
}
