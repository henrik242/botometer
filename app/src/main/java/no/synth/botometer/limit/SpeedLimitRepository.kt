package no.synth.botometer.limit

import android.util.Log
import android.util.LruCache
import no.synth.botometer.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Hvor godt matchingen tror på treffet sitt. Ordnet fra dårligst til best, så [minOf] gir
 * det svakeste leddet.
 *
 * Vinneren alene sier ikke om den vant klart. Ligger to segmenter med ulik fartsgrense like
 * nær, er treffet et myntkast - og et myntkast skal ikke se ut som et faktum på en bilskjerm.
 */
enum class MatchConfidence { LOW, MEDIUM, HIGH }

data class LimitMatch(
    val limitKmt: Int,
    val roadRef: String?,
    val distanceMeters: Double,
    val stale: Boolean = false,
    val confidence: MatchConfidence = MatchConfidence.HIGH,
    /**
     * Motorveg ifølge NVDB. null = ikke slått opp. Slås bare opp i 90-soner og høyere, siden
     * det er den eneste satsen som skiller på vegtype.
     */
    val motorway: Boolean? = null,
)

/**
 * Henter fartsgrenser fra NVDB i ruter (tiles) og kart-matcher GPS-posisjonen mot dem.
 *
 * Hvorfor tiles: NVDB-kall tar hundrevis av millisekunder og mobildekning i norske dalfører er
 * upålitelig. Ett kall per GPS-fix er både for tregt og for skjørt. I stedet lastes ~2x2 km
 * ruter, cachet i minnet, og ruta foran bilen forhåndslastes ut fra kurs og fart.
 */
class SpeedLimitRepository(
    private val nvdb: NvdbClient,
    private val scope: CoroutineScope,
    private val tileDegrees: Double = 0.02,       // ~2.2 km i nord/sør
    private val maxMatchDistanceMeters: Double = 30.0,
    private val maxHeadingDeltaDeg: Double = 45.0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class TileKey(val latIdx: Int, val lonIdx: Int)

    /** Fartsgrenser og motorveg er to datasett i samme rute, med hver sin cache og backoff. */
    private enum class Layer { LIMITS, MOTORWAY }

    private data class TileRequest(val key: TileKey, val layer: Layer)

    /**
     * Negativ cache. Uten den prøver hver GPS-oppdatering på nytt: i en tunnel med 1 Hz blir det
     * 60 forespørsler i minuttet mot Vegvesenets åpne API for en rute vi nettopp fikk feil på.
     */
    private data class Failure(val attempts: Int, val retryAtMs: Long, val permanent: Boolean)

    private val tiles = LruCache<TileKey, List<SpeedLimitSegment>>(64)

    /** Mindre enn fartsgrensecachen: motorveg slås bare opp i 90-soner, og de er få og lange. */
    private val motorwayTiles = LruCache<TileKey, List<MotorwaySegment>>(16)

    private val failures = HashMap<TileRequest, Failure>()
    private val inFlight = HashSet<TileRequest>()
    private val lock = Mutex()

    /** Siste sikre treff, brukt som fallback i tunneler og der matchingen blir usikker. */
    private var lastGood: LimitMatch? = null

    /** Fartsgrensen vi viste sist. Grunnlaget for segment-hysteresen. */
    private var lastLimitKmt: Int? = null

    /**
     * @param accuracyMeters GPS-usikkerheten i fixet. Den er ikke pynt: et fix på ±25 m kan
     * ligge 25 m fra vegen det faktisk er på, og med et fast matchevindu ga det bom - eller
     * verre, treff på en parallell veg med annen fartsgrense.
     */
    suspend fun limitAt(
        position: LatLon,
        headingDeg: Double?,
        speedKmt: Double,
        accuracyMeters: Double = 0.0,
    ): LimitMatch? {
        // Er fixet håpløst, er forrige sikre treff et bedre svar enn et tilfeldig ett. Å matche
        // på en posisjon som kan være femti meter feil er å trekke lodd blant vegene i krysset.
        if (accuracyMeters > MAX_USABLE_ACCURACY_METERS) {
            Diagnostics.poorAccuracy(accuracyMeters)
            return lastGood?.copy(stale = true)
        }

        val key = keyOf(position)
        val segments = tiles[key]

        if (segments == null) {
            ensureTile(key, Layer.LIMITS)
            // Ingen data ennå: returner forrige treff, men merk det som gammelt så UI kan dempe det.
            return lastGood?.copy(stale = true)
        }

        prefetchAhead(position, headingDeg, speedKmt)

        val window = matchWindow(accuracyMeters)
        val result = matchAt(segments, position, headingDeg, speedKmt, window)

        // Kandidatlista tar med det matchingen forkastet. Uten den ser et feil treff ut som en
        // gåte: du vet at 30 er galt, men ikke hva 80-vegen lå på av avstand og kursavvik.
        Diagnostics.candidates(
            result.nearby.sortedBy { it.second }.take(MAX_CANDIDATES).map { (seg, meters, delta) ->
                buildString {
                    append("${seg.limitKmt} km/t")
                    append(" · ${"%.0f".format(meters)} m")
                    if (delta != null) append(" · Δ${"%.0f".format(delta)}°")
                    seg.roadRef?.let { append(" · $it") }
                }
            }
        )

        val best = result.best
        if (best == null) {
            Diagnostics.missed(segments.size, result.nearestMeters, result.rejectedByHeading)
            return lastGood?.copy(stale = true)
        }

        val rawConfidence = confidenceOf(best, result.rivalGap, accuracyMeters)
        val chosen = stabilize(best, result.candidates)

        // Holdt hysteresen igjen, er dette per definisjon ikke det matchingen ville valgt.
        // Da skal tallet heller ikke framstå som sikkert.
        val confidence =
            if (chosen === best) rawConfidence else minOf(rawConfidence, MatchConfidence.MEDIUM)

        val motorway = if (chosen.segment.limitKmt >= MOTORWAY_RELEVANT_FROM_KMT) {
            motorwayAt(position, key, window)
        } else null

        val match = LimitMatch(
            limitKmt = chosen.segment.limitKmt,
            roadRef = chosen.segment.roadRef,
            distanceMeters = chosen.distanceMeters,
            confidence = confidence,
            motorway = motorway,
        )

        lastLimitKmt = match.limitKmt
        lastGood = match
        Diagnostics.matched(match.limitKmt, match.distanceMeters, match.roadRef, confidence.name, motorway)
        return match
    }

    // ---- matching -------------------------------------------------------------------------

    private data class Candidate(
        val segment: SpeedLimitSegment,
        val distanceMeters: Double,
        val score: Double,
    )

    private data class MatchResult(
        val best: Candidate?,
        val candidates: List<Candidate>,
        /** Hvor mye dårligere den nærmeste kandidaten med en ANNEN fartsgrense var. */
        val rivalGap: Double,
        val nearestMeters: Double,
        val rejectedByHeading: Boolean,
        val nearby: List<Triple<SpeedLimitSegment, Double, Double?>>,
    )

    /**
     * Én kandidat per segment - det beste linjestykket i hvert - slik at vi kan spørre hvor
     * klart vinneren vant. Med bare et løpende minimum finnes ikke det spørsmålet.
     */
    private fun matchAt(
        segments: List<SpeedLimitSegment>,
        position: LatLon,
        headingDeg: Double?,
        speedKmt: Double,
        windowMeters: Double,
    ): MatchResult {
        val plane = LocalPlane(position)
        val candidates = ArrayList<Candidate>()

        // Bare for diagnostikk. Et bom uten forklaring er ikke til å feilsøke på.
        var nearestMeters = Double.POSITIVE_INFINITY
        var rejectedByHeading = false
        val nearby = ArrayList<Triple<SpeedLimitSegment, Double, Double?>>()

        for (seg in segments) {
            var segNearest = Double.POSITIVE_INFINITY
            var segBearing = 0.0
            var bestScore = Double.POSITIVE_INFINITY
            var bestDistance = Double.POSITIVE_INFINITY

            for (i in 0 until seg.line.size - 1) {
                val hit = Geo.distanceToSegment(plane, position, seg.line[i], seg.line[i + 1])
                if (hit.distanceMeters < nearestMeters) nearestMeters = hit.distanceMeters
                if (hit.distanceMeters < segNearest) {
                    segNearest = hit.distanceMeters
                    segBearing = hit.bearingDeg
                }
                if (hit.distanceMeters > windowMeters) continue

                // Kursfilter: uten det plukker vi lett en parallell veg eller en avkjøring med
                // annen fartsgrense. Under gangfart er kursen fra GPS ubrukelig, så da dropper vi det.
                //
                // axisDelta, ikke headingDelta: en veg er en linje, ikke en pil. Kjører du motsatt
                // vei av den NVDB har digitalisert, er kursavviket 180° selv om du ligger midt i
                // vegbanen - og da forkastet filteret riktig veg.
                val headingPenalty = if (headingDeg != null && speedKmt > MIN_HEADING_SPEED_KMT) {
                    val delta = Geo.axisDelta(headingDeg, hit.bearingDeg)
                    if (delta > maxHeadingDeltaDeg) {
                        rejectedByHeading = true
                        continue
                    } else delta / maxHeadingDeltaDeg * HEADING_PENALTY_METERS
                } else 0.0

                val score = hit.distanceMeters + headingPenalty
                if (score < bestScore) {
                    bestScore = score
                    bestDistance = hit.distanceMeters
                }
            }

            if (bestScore.isFinite()) candidates += Candidate(seg, bestDistance, bestScore)

            if (segNearest <= DIAGNOSTIC_RADIUS_METERS) {
                nearby += Triple(
                    seg,
                    segNearest,
                    headingDeg?.let { Geo.axisDelta(it, segBearing) },
                )
            }
        }

        val best = candidates.minByOrNull { it.score }
        val rival = best?.let { b ->
            candidates.filter { it.segment.limitKmt != b.segment.limitKmt }.minByOrNull { it.score }
        }
        val gap = if (best == null || rival == null) Double.POSITIVE_INFINITY else rival.score - best.score

        return MatchResult(best, candidates, gap, nearestMeters, rejectedByHeading, nearby)
    }

    /**
     * Matchevinduet vokser med GPS-usikkerheten. Med et fast vindu på 30 m ble et fix på ±25 m
     * et bom, og et bom vises som forrige treff eller «ukjent» - ikke som «GPS-en er dårlig her».
     */
    private fun matchWindow(accuracyMeters: Double): Double =
        (maxMatchDistanceMeters + accuracyMeters.coerceAtLeast(0.0))
            .coerceAtMost(MAX_MATCH_WINDOW_METERS)

    /**
     * Nøyaktigheten setter et tak på tilliten. En posisjon på ±30 m kan ikke gi et sikkert treff
     * uansett hvor pent segmentene ligger - vinduet er da bare bredt nok til å ta med naboen.
     */
    private fun confidenceOf(
        best: Candidate,
        rivalGap: Double,
        accuracyMeters: Double,
    ): MatchConfidence {
        val byMatch = when {
            best.distanceMeters <= CONFIDENT_DISTANCE_METERS && rivalGap >= CONFIDENT_GAP_METERS ->
                MatchConfidence.HIGH
            best.distanceMeters <= maxMatchDistanceMeters && rivalGap >= AMBIGUOUS_GAP_METERS ->
                MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
        val byAccuracy = when {
            accuracyMeters <= GOOD_ACCURACY_METERS -> MatchConfidence.HIGH
            accuracyMeters <= FAIR_ACCURACY_METERS -> MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
        return minOf(byMatch, byAccuracy)
    }

    /**
     * Ikke bytt fartsgrense på en marginal forskjell.
     *
     * I et kryss ligger to segmenter med ulik grense nesten like nær, og vinneren skifter fra
     * fix til fix. Skiltet på skjermen hoppet da mellom 50 og 30 mens bilen sto stille. Den nye
     * grensen må derfor vinne med [SWITCH_MARGIN_METERS] over den vi allerede viser - altså
     * vinne utvetydig, samme margin som skiller et sikkert treff fra et tvilsomt.
     *
     * Målt mot den gamle grensen selv, ikke mot nærmeste konkurrent: ligger det tre ulike
     * grenser i et kryss, er det den vi står og viser som skal utfordres.
     *
     * Ved et ekte soneskifte holder ikke dette igjen: NVDB-segmentet du forlot slutter ved
     * skiltet, så avstanden til det vokser med farten din og den nye vinner innen få meter.
     */
    private fun stabilize(best: Candidate, candidates: List<Candidate>): Candidate {
        val previous = lastLimitKmt ?: return best
        if (best.segment.limitKmt == previous) return best

        val held = candidates
            .filter { it.segment.limitKmt == previous }
            .minByOrNull { it.score }
            ?: return best   // den gamle grensen finnes ikke lenger her; da er byttet ekte

        return if (best.score <= held.score - SWITCH_MARGIN_METERS) best else held
    }

    /**
     * null = vet ikke ennå (ruta er ikke lastet). Da beholder [no.synth.botometer.fine.FineCalculator]
     * dagens oppførsel og merker satsen som usikker, i stedet for å påstå noe vi ikke vet.
     */
    private fun motorwayAt(position: LatLon, key: TileKey, windowMeters: Double): Boolean? {
        val segments = motorwayTiles[key]
        if (segments == null) {
            ensureTile(key, Layer.MOTORWAY)
            return null
        }
        val plane = LocalPlane(position)
        for (seg in segments) {
            for (i in 0 until seg.line.size - 1) {
                val hit = Geo.distanceToSegment(plane, position, seg.line[i], seg.line[i + 1])
                if (hit.distanceMeters <= windowMeters) return true
            }
        }
        return false
    }

    // ---- ruter ----------------------------------------------------------------------------

    private fun keyOf(p: LatLon) = TileKey(
        floor(p.lat / tileDegrees).toInt(),
        floor(p.lon / tileDegrees).toInt(),
    )

    private fun bboxOf(key: TileKey): BBox {
        val south = key.latIdx * tileDegrees
        val west = key.lonIdx * tileDegrees
        // Litt buffer, ellers mister vi segmenter som krysser rutekanten.
        val pad = tileDegrees * 0.15
        return BBox(west - pad, south - pad, west + tileDegrees + pad, south + tileDegrees + pad)
    }

    private fun ensureTile(key: TileKey, layer: Layer) {
        val request = TileRequest(key, layer)
        scope.launch {
            val attempt = lock.withLock {
                if (cached(request) || request in inFlight) return@launch

                val failure = failures[request]
                if (failure != null) {
                    if (failure.permanent) return@launch
                    if (now() < failure.retryAtMs) return@launch
                }

                inFlight += request
                (failure?.attempts ?: 0) + 1
            }

            try {
                val complete = when (layer) {
                    Layer.LIMITS -> {
                        val data = nvdb.speedLimitsIn(bboxOf(key))
                        tiles.put(key, data.segments)
                        Diagnostics.tileLoaded(data.segments.size, data.complete, "$key")
                        if (!data.complete) {
                            Log.w(TAG, "Ufullstendige data for $key: ${data.segments.size} segmenter")
                        } else {
                            Log.d(TAG, "Lastet ${data.segments.size} fartsgrensesegmenter for $key")
                        }
                        data.complete
                    }
                    Layer.MOTORWAY -> {
                        val data = nvdb.motorwaysIn(bboxOf(key))
                        motorwayTiles.put(key, data.segments)
                        Diagnostics.motorwayTileLoaded(data.segments.size, "$key")
                        Log.d(TAG, "Lastet ${data.segments.size} motorvegsegmenter for $key")
                        data.complete
                    }
                }
                lock.withLock { failures.remove(request) }
                if (!complete) Log.w(TAG, "Ufullstendig $layer for $key")
            } catch (t: Throwable) {
                recordFailure(request, attempt, t)
            } finally {
                lock.withLock { inFlight -= request }
            }
        }
    }

    private fun cached(request: TileRequest): Boolean = when (request.layer) {
        Layer.LIMITS -> tiles[request.key] != null
        Layer.MOTORWAY -> motorwayTiles[request.key] != null
    }

    private suspend fun recordFailure(request: TileRequest, attempt: Int, cause: Throwable) {
        val permanent = cause is NvdbException && !cause.retryable
        val delay = if (permanent) 0L else backoffMs(attempt)

        lock.withLock {
            failures[request] = Failure(attempt, now() + delay, permanent)
            // Hindrer at kartet fylles opp på en lang tur; gamle oppføringer er uinteressante.
            if (failures.size > MAX_FAILURE_ENTRIES) {
                failures.entries
                    .sortedBy { it.value.retryAtMs }
                    .take(failures.size - MAX_FAILURE_ENTRIES)
                    .forEach { failures.remove(it.key) }
            }
        }

        Diagnostics.tileFailed(
            "${request.key}/${request.layer}",
            cause.message ?: cause.javaClass.simpleName,
            permanent,
        )

        if (permanent) {
            Log.e(TAG, "Permanent feil for $request, prøver ikke igjen: ${cause.message}")
        } else {
            Log.w(TAG, "Forsøk $attempt feilet for $request, nytt forsøk om ${delay}ms: ${cause.message}")
        }
    }

    /**
     * Eksponentiell backoff med jitter. Jitteren er ikke pynt: uten den vil alle ruter som feilet
     * i samme tunnel prøve igjen i samme sekund idet dekningen kommer tilbake.
     */
    private fun backoffMs(attempt: Int): Long {
        val base = (BASE_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_BACKOFF_MS)
        return base + Random.nextLong(base / 4 + 1)
    }

    /** Last ruten ~60 sekunder fram i tid, minimum 1 km, slik at vi aldri venter på nett. */
    private fun prefetchAhead(position: LatLon, headingDeg: Double?, speedKmt: Double) {
        if (headingDeg == null) return
        val meters = (speedKmt / 3.6 * 60.0).coerceAtLeast(1000.0)
        val ahead = Geo.project(position, headingDeg, meters)
        val key = keyOf(ahead)
        if (tiles[key] == null) ensureTile(key, Layer.LIMITS)
    }

    private companion object {
        const val TAG = "SpeedLimitRepo"
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_FAILURE_ENTRIES = 128

        /** Under gangfart er kursen fra GPS støy, og kursfilteret gjør mer skade enn nytte. */
        const val MIN_HEADING_SPEED_KMT = 8.0

        /** Full kursavvik koster like mye som 15 meter ekstra avstand. */
        const val HEADING_PENALTY_METERS = 15.0

        /** Videre enn matchevinduet med vilje: vi vil se hva som ble forkastet, ikke bare hva som vant. */
        const val DIAGNOSTIC_RADIUS_METERS = 80.0
        const val MAX_CANDIDATES = 4

        /** Over dette er posisjonen for upålitelig til å velge mellom to veger i det hele tatt. */
        const val MAX_USABLE_ACCURACY_METERS = 50.0
        const val MAX_MATCH_WINDOW_METERS = 60.0
        const val GOOD_ACCURACY_METERS = 12.0
        const val FAIR_ACCURACY_METERS = 25.0

        /** Innenfor dette ligger du i vegbanen, ikke i nærheten av den. */
        const val CONFIDENT_DISTANCE_METERS = 15.0

        /** Så mye må nærmeste konkurrent med en annen fartsgrense ligge bak for at treffet er klart. */
        const val CONFIDENT_GAP_METERS = 15.0
        const val AMBIGUOUS_GAP_METERS = 8.0

        /**
         * Så mye bedre må en ny fartsgrense være før vi bytter fra den vi allerede viser.
         * Samme tall som [AMBIGUOUS_GAP_METERS] med vilje: et bytte som ikke ville gitt et
         * utvetydig treff, er heller ikke et bytte verdt å gjøre.
         */
        const val SWITCH_MARGIN_METERS = AMBIGUOUS_GAP_METERS

        /** Motorvegsatsen finnes bare fra 90-sonen og opp. Under det er oppslaget bortkastet. */
        const val MOTORWAY_RELEVANT_FROM_KMT = 90
    }
}
