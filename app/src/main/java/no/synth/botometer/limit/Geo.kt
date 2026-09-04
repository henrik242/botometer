package no.synth.botometer.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
}

/**
 * Lokal ekvirektangulær projeksjon rundt et referansepunkt. På noen få kilometer er feilen
 * neglisjerbar, og vi slipper trigonometri per punkt i den varme løkka (kart-matching
 * kjører for hvert GPS-fix mot noen hundre segmenter).
 */
class LocalPlane(private val ref: LatLon) {
    private val mPerDegLat = 111_132.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(ref.lat))

    fun x(lon: Double) = (lon - ref.lon) * mPerDegLon
    fun y(lat: Double) = (lat - ref.lat) * mPerDegLat
}

object Geo {

    /** Avstand i meter fra p til linjestykket a-b, samt segmentets retning i grader. */
    fun distanceToSegment(plane: LocalPlane, p: LatLon, a: LatLon, b: LatLon): SegmentHit {
        val px = plane.x(p.lon); val py = plane.y(p.lat)
        val ax = plane.x(a.lon); val ay = plane.y(a.lat)
        val bx = plane.x(b.lon); val by = plane.y(b.lat)

        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        return SegmentHit(hypot(px - cx, py - cy), bearing)
    }

    /** Minste vinkel mellom to kurser, 0-180. Retning uten fortegn, siden vegen kan være tolinjet. */
    fun headingDelta(a: Double, b: Double): Double {
        val d = abs((a - b + 540.0) % 360.0 - 180.0)
        return d
    }

    /** Punkt `meters` fram i kurs `bearing`. Brukes til å forhåndslaste ruta foran bilen. */
    fun project(from: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val rad = Math.toRadians(bearingDeg)
        val dLat = meters * kotlin.math.cos(rad) / 111_132.0
        val dLon = meters * kotlin.math.sin(rad) / (111_320.0 * cos(Math.toRadians(from.lat)))
        return LatLon(from.lat + dLat, from.lon + dLon)
    }

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la = Math.toRadians(a.lat); val lb = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            cos(la) * cos(lb) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * 6_371_000.0 * atan2(sqrt(h), sqrt(1 - h))
    }
}

data class SegmentHit(val distanceMeters: Double, val bearingDeg: Double)

/**
 * Minimal WKT-parser for det NVDB faktisk returnerer for vegobjekter: POINT / LINESTRING /
 * MULTILINESTRING, med eller uten Z. Ingen grunn til å dra inn JTS for dette.
 *
 * NB: NVDB oppgir koordinater i den rekkefølgen SRID-en definerer. For srid=4326 kommer de
 * som (lon lat). Vi sanity-sjekker mot Norges utstrekning og bytter om nødvendig, så en
 * eventuell API-endring gir feil fartsgrense i stedet for krasj.
 */
object Wkt {
    private val NORWAY_LAT = 57.0..72.0
    private val NORWAY_LON = 3.0..33.0

    fun parseLines(wkt: String): List<List<LatLon>> {
        val body = wkt.substringAfter('(', "").dropLast(1)
        if (body.isBlank()) return emptyList()
        val chunks = if (wkt.startsWith("MULTILINESTRING", ignoreCase = true)) {
            Regex("""\(([^()]*)\)""").findAll(body).map { it.groupValues[1] }.toList()
        } else {
            listOf(body)
        }
        return chunks.mapNotNull { chunk ->
            val pts = chunk.split(',').mapNotNull { parsePoint(it) }
            pts.takeIf { it.size >= 2 }
        }
    }

    private fun parsePoint(raw: String): LatLon? {
        val nums = raw.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (nums.size < 2) return null
        val (a, b) = nums[0] to nums[1]
        return when {
            b in NORWAY_LAT && a in NORWAY_LON -> LatLon(b, a)   // (lon lat) - forventet
            a in NORWAY_LAT && b in NORWAY_LON -> LatLon(a, b)   // (lat lon) - byttet
            else -> null                                          // UTM e.l. - ignorer
        }
    }
}
