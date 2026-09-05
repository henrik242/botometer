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

data class LimitMatch(
    val limitKmt: Int,
    val roadRef: String?,
    val distanceMeters: Double,
    val stale: Boolean = false,
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

    /**
     * Negativ cache. Uten den prøver hver GPS-oppdatering på nytt: i en tunnel med 1 Hz blir det
     * 60 forespørsler i minuttet mot Vegvesenets åpne API for en rute vi nettopp fikk feil på.
     */
    private data class Failure(val attempts: Int, val retryAtMs: Long, val permanent: Boolean)

    private val tiles = LruCache<TileKey, List<SpeedLimitSegment>>(64)
    private val failures = HashMap<TileKey, Failure>()
    private val inFlight = HashSet<TileKey>()
    private val lock = Mutex()

    /** Siste sikre treff, brukt som fallback i tunneler og der matchingen blir usikker. */
    private var lastGood: LimitMatch? = null

    suspend fun limitAt(position: LatLon, headingDeg: Double?, speedKmt: Double): LimitMatch? {
        val key = keyOf(position)
        val segments = tiles[key]

        if (segments == null) {
            ensureTile(key)
            // Ingen data ennå: returner forrige treff, men merk det som gammelt så UI kan dempe det.
            return lastGood?.copy(stale = true)
        }

        prefetchAhead(position, headingDeg, speedKmt)

        val plane = LocalPlane(position)
        var best: LimitMatch? = null
        var bestScore = Double.MAX_VALUE

        // Bare for diagnostikk. Et bom uten forklaring er ikke til å feilsøke på.
        var nearestMeters = Double.POSITIVE_INFINITY
        var rejectedByHeading = false

        for (seg in segments) {
            for (i in 0 until seg.line.size - 1) {
                val hit = Geo.distanceToSegment(plane, position, seg.line[i], seg.line[i + 1])
                if (hit.distanceMeters < nearestMeters) nearestMeters = hit.distanceMeters
                if (hit.distanceMeters > maxMatchDistanceMeters) continue

                // Kursfilter: uten det plukker vi lett en parallell veg eller en avkjøring med
                // annen fartsgrense. Under gangfart er kursen fra GPS ubrukelig, så da dropper vi det.
                val headingPenalty = if (headingDeg != null && speedKmt > 8) {
                    val delta = Geo.headingDelta(headingDeg, hit.bearingDeg)
                    if (delta > maxHeadingDeltaDeg) {
                        rejectedByHeading = true
                        continue
                    } else delta / maxHeadingDeltaDeg * 15.0
                } else 0.0

                val score = hit.distanceMeters + headingPenalty
                if (score < bestScore) {
                    bestScore = score
                    best = LimitMatch(seg.limitKmt, seg.roadRef, hit.distanceMeters)
                }
            }
        }

        if (best != null) {
            lastGood = best
            Diagnostics.matched(best.limitKmt, best.distanceMeters, best.roadRef)
            return best
        }
        Diagnostics.missed(segments.size, nearestMeters, rejectedByHeading)
        return lastGood?.copy(stale = true)
    }

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

    private fun ensureTile(key: TileKey) {
        scope.launch {
            val attempt = lock.withLock {
                if (tiles[key] != null || key in inFlight) return@launch

                val failure = failures[key]
                if (failure != null) {
                    if (failure.permanent) return@launch
                    if (now() < failure.retryAtMs) return@launch
                }

                inFlight += key
                (failure?.attempts ?: 0) + 1
            }

            try {
                val data = nvdb.speedLimitsIn(bboxOf(key))
                tiles.put(key, data.segments)
                lock.withLock { failures.remove(key) }
                Diagnostics.tileLoaded(data.segments.size, data.complete, "$key")
                if (!data.complete) {
                    Log.w(TAG, "Ufullstendige data for $key: ${data.segments.size} segmenter")
                } else {
                    Log.d(TAG, "Lastet ${data.segments.size} fartsgrensesegmenter for $key")
                }
            } catch (t: Throwable) {
                recordFailure(key, attempt, t)
            } finally {
                lock.withLock { inFlight -= key }
            }
        }
    }

    private suspend fun recordFailure(key: TileKey, attempt: Int, cause: Throwable) {
        val permanent = cause is NvdbException && !cause.retryable
        val delay = if (permanent) 0L else backoffMs(attempt)

        lock.withLock {
            failures[key] = Failure(attempt, now() + delay, permanent)
            // Hindrer at kartet fylles opp på en lang tur; gamle oppføringer er uinteressante.
            if (failures.size > MAX_FAILURE_ENTRIES) {
                failures.entries
                    .sortedBy { it.value.retryAtMs }
                    .take(failures.size - MAX_FAILURE_ENTRIES)
                    .forEach { failures.remove(it.key) }
            }
        }

        Diagnostics.tileFailed("$key", cause.message ?: cause.javaClass.simpleName, permanent)

        if (permanent) {
            Log.e(TAG, "Permanent feil for $key, prøver ikke igjen: ${cause.message}")
        } else {
            Log.w(TAG, "Forsøk $attempt feilet for $key, nytt forsøk om ${delay}ms: ${cause.message}")
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
        if (tiles[key] == null) ensureTile(key)
    }

    private companion object {
        const val TAG = "SpeedLimitRepo"
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_FAILURE_ENTRIES = 128
    }
}
