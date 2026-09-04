package no.synth.botometer.limit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Ett segment fartsgrense med geometri, klart til kart-matching. */
data class SpeedLimitSegment(
    val nvdbId: Long,
    val limitKmt: Int,
    val line: List<LatLon>,
    val roadRef: String?,
)

/**
 * @param complete false betyr at vi traff sidegrensen og at datasettet er ufullstendig.
 * Uten dette flagget ville manglende fartsgrenser sett ut som «her finnes det ingen».
 */
data class TileData(
    val segments: List<SpeedLimitSegment>,
    val complete: Boolean,
)

/**
 * `statusCode == null` betyr nettverksfeil (ingen respons). `retryable` skiller transiente feil
 * (nett, 429, 5xx) fra feil som ikke blir bedre av å prøve igjen (400 på en ugyldig bbox).
 */
class NvdbException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val retryable: Boolean get() = statusCode == null || statusCode == 429 || statusCode >= 500
}

/**
 * NVDB API LES v4. Åpne data under NLOD 2.0, ingen autentisering, men Vegvesenet krever at
 * klienter identifiserer seg med X-Client - uten den risikerer man å bli strupet.
 *
 * Vegobjekttype 105 = Fartsgrense. Egenskap 2021 = Fartsgrense (heltall, km/t).
 * segmentering=true gir oss geometri splittet på veglenker, som er det vi vil matche mot.
 */
class NvdbClient(
    private val clientName: String,
    private val http: OkHttpClient = defaultHttp(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun speedLimitsIn(bbox: BBox): TileData = withContext(Dispatchers.IO) {
        val out = ArrayList<SpeedLimitSegment>()
        var start: String? = null

        // NVDB paginerer alle treff over sidestørrelsen. Uten dette mistet vi fartsgrenser
        // stille i tette byruter - nettopp der de varierer mest.
        for (page in 0 until MAX_PAGES) {
            val (segments, next) = fetchPage(bbox, start)
            out += segments
            if (next == null) return@withContext TileData(out, complete = true)
            start = next
        }

        Log.w(TAG, "Traff sidegrensen ($MAX_PAGES sider, ${out.size} objekter) for $bbox - ufullstendig")
        TileData(out, complete = false)
    }

    private fun fetchPage(bbox: BBox, start: String?): Pair<List<SpeedLimitSegment>, String?> {
        val url = buildString {
            append(BASE)
            append("/vegobjekter/api/v4/vegobjekter/$TYPE_FARTSGRENSE")
            append("?kartutsnitt=${bbox.west},${bbox.south},${bbox.east},${bbox.north}")
            append("&srid=4326")
            append("&inkluder=egenskaper,geometri,lokasjon")
            append("&segmentering=true")
            append("&antall=$PAGE_SIZE")
            // Vi bryr oss verken om rekkefølge eller totalantall; begge koster tid på serveren.
            append("&sortering=false&inkluderAntall=false")
            if (start != null) append("&start=$start")
        }

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Client", clientName)
            .build()

        val body = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw NvdbException(resp.code, "NVDB svarte ${resp.code}")
                }
                resp.body?.string() ?: throw NvdbException(resp.code, "Tomt svar fra NVDB")
            }
        } catch (e: NvdbException) {
            throw e
        } catch (e: IOException) {
            throw NvdbException(null, "Nettverksfeil mot NVDB: ${e.message}", e)
        }

        val root = json.parseToJsonElement(body).jsonObject
        val segments = root["objekter"]?.jsonArray.orEmpty()
            .mapNotNull { parseSegment(it.jsonObject) }

        // Vi følger `start`-tokenet, ikke `neste.href`. Href-en i responsen har historisk pekt
        // på gamle stier (uten /api/v4/), så det er tryggere å bygge URL-en selv.
        val next = root["metadata"]?.jsonObject
            ?.get("neste")?.jsonObject
            ?.get("start")?.jsonPrimitive?.contentOrNull

        return segments to next
    }

    private fun parseSegment(obj: JsonObject): SpeedLimitSegment? {
        val id = obj["id"]?.jsonPrimitive?.longOrNullSafe() ?: return null

        val limit = obj["egenskaper"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.intOrNullSafe() == EGENSKAP_FARTSGRENSE }
            ?.get("verdi")?.jsonPrimitive?.intOrNullSafe()
            ?: return null

        val wkt = obj["geometri"]?.jsonObject?.get("wkt")?.jsonPrimitive?.contentOrNull ?: return null
        val lines = Wkt.parseLines(wkt)
        if (lines.isEmpty()) return null

        val roadRef = obj["lokasjon"]?.jsonObject
            ?.get("vegsystemreferanser")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("kortform")?.jsonPrimitive?.contentOrNull

        // Flat ut multilinestring; hvert delstykke matches uansett punktvis.
        return SpeedLimitSegment(id, limit, lines.flatten(), roadRef)
    }

    companion object {
        private const val TAG = "NvdbClient"
        const val BASE = "https://nvdbapiles.atlas.vegvesen.no"
        const val TYPE_FARTSGRENSE = 105
        const val EGENSKAP_FARTSGRENSE = 2021
        private const val PAGE_SIZE = 1000

        /** 25 sider a 1000 objekter er langt over det en 2x2 km rute kan inneholde. */
        private const val MAX_PAGES = 25

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.longOrNullSafe(): Long? = content.toLongOrNull()
private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? = content.toIntOrNull()
private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String? get() = content.takeIf { it != "null" }
