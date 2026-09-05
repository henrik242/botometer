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
 * En strekning med vedtatt motorvegstatus. Bare geometrien er interessant: spørsmålet er «er vi
 * på en motorveg?», ikke hvilken.
 *
 * Motortrafikkveg er IKKE motorveg i denne sammenhengen - satsen for 36-40 km/t over i en
 * 90-sone gjelder bare motorveg - så de filtreres bort i parsingen.
 */
data class MotorwaySegment(
    val nvdbId: Long,
    val line: List<LatLon>,
)

/**
 * @param complete false betyr at vi traff sidegrensen og at datasettet er ufullstendig.
 * Uten dette flagget ville manglende fartsgrenser sett ut som «her finnes det ingen».
 */
data class TileData<T>(
    val segments: List<T>,
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
 * Vegobjekttype 595 = Motorveg. Egenskap 5378 = Motorvegtype (7355 Motorveg, 7356 Motortrafikkveg).
 * segmentering=true gir oss geometri splittet på veglenker, som er det vi vil matche mot.
 */
class NvdbClient(
    private val clientName: String,
    private val http: OkHttpClient = defaultHttp(),
    /** Injiserbar så feilhåndteringen kan testes mot en lokal server, uten nett. */
    private val baseUrl: String = BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun speedLimitsIn(bbox: BBox): TileData<SpeedLimitSegment> =
        fetchAll(bbox, TYPE_FARTSGRENSE, ::parseSpeedLimits)

    /**
     * Motorvegstrekninger i ruta. Hentes bare når den matchede fartsgrensen er 90 eller høyere -
     * det er den eneste satsen som skiller på vegtype - så den doble NVDB-trafikken påløper
     * ikke i en 50-sone.
     */
    suspend fun motorwaysIn(bbox: BBox): TileData<MotorwaySegment> =
        fetchAll(bbox, TYPE_MOTORVEG, ::parseMotorways)

    private suspend fun <T> fetchAll(
        bbox: BBox,
        type: Int,
        parse: (JsonObject) -> List<T>,
    ): TileData<T> = withContext(Dispatchers.IO) {
        val out = ArrayList<T>()
        var start: String? = null

        // NVDB paginerer alle treff over sidestørrelsen. Uten dette mistet vi fartsgrenser
        // stille i tette byruter - nettopp der de varierer mest.
        for (page in 0 until MAX_PAGES) {
            val p = fetchPage(bbox, type, start, parse)
            out += p.segments

            // Et tomt `neste` er IKKE det vanlige stoppsignalet: NVDB oppgir `neste.start` også
            // når det ikke er mer å hente. Den tomme siden er signalet.
            //
            // Uten dette gikk hver rute 25 runder, hentet 24 tomme sider, og ble til slutt
            // merket ufullstendig fordi løkka gikk tom for forsøk - ikke fordi dataene manglet.
            // Diagnostikken viste da «10 segmenter (UFULLSTENDIG)», som er selvmotsigende, og
            // hvert eneste ruteoppslag kostet 25 kall mot et offentlig API.
            //
            // `p.next == start` fanger et token som ikke flytter seg. Da ville vi ellers hentet
            // samme side om og om igjen.
            if (p.rawCount == 0 || p.next == null || p.next == start) {
                return@withContext TileData(out, complete = true)
            }
            start = p.next
        }

        Log.w(TAG, "Traff sidegrensen ($MAX_PAGES sider, ${out.size} objekter) for $bbox - ufullstendig")
        TileData(out, complete = false)
    }

    /**
     * @param rawCount antall objekter NVDB faktisk sendte, før parsing. Det er dette som avgjør
     * om det er mer å hente - ikke hvor mange vi klarte å tolke, ellers ville en side med bare
     * uparsebare objekter sett ut som slutten.
     */
    private data class Page<T>(
        val segments: List<T>,
        val next: String?,
        val rawCount: Int,
    )

    private fun <T> fetchPage(
        bbox: BBox,
        type: Int,
        start: String?,
        parse: (JsonObject) -> List<T>,
    ): Page<T> {
        val url = buildString {
            append(baseUrl)
            append("/vegobjekter/api/v4/vegobjekter/$type")
            append("?kartutsnitt=${bbox.west},${bbox.south},${bbox.east},${bbox.north}")
            append("&srid=4326")
            append("&inkluder=egenskaper,geometri,lokasjon")
            append("&segmentering=true")
            append("&antall=$PAGE_SIZE")
            // Totalantallet er irrelevant for en kartvisning, og det koster tid på serveren å
            // telle det opp.
            //
            // Her sto også sortering=false. Den parameteren finnes ikke i v4 - den er fra v3 -
            // og v4 AVVISER ukjente parametre i stedet for å ignorere dem:
            //
            //   {"detail":"Ukjente parametre: sortering","status":400,...}
            //
            // Legg derfor aldri til en parameter her uten å slå den opp i v4-dokumentasjonen.
            // NvdbClientErrorTest låser settet, så en ny parameter må gjøres bevisst.
            append("&inkluderAntall=false")
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
                    // Feilkroppen MÅ være med. NVDB forklarer i den hvilken parameter den ikke
                    // likte, og uten den står brukeren igjen med «NVDB svarte 400» - et tall som
                    // sier at noe er permanent galt, men ikke hva. Det er den eneste beskjeden
                    // som finnes: appen har ingen crashlogg, og en telefon har ingen adb.
                    val forklaring = runCatching { resp.body?.string() }.getOrNull()
                        ?.trim()?.take(MAX_ERROR_BODY)
                    throw NvdbException(
                        resp.code,
                        "NVDB svarte ${resp.code}" + if (forklaring.isNullOrEmpty()) "" else ": $forklaring",
                    )
                }
                resp.body?.string() ?: throw NvdbException(resp.code, "Tomt svar fra NVDB")
            }
        } catch (e: NvdbException) {
            throw e
        } catch (e: IOException) {
            throw NvdbException(null, "Nettverksfeil mot NVDB: ${e.message}", e)
        }

        val root = json.parseToJsonElement(body).jsonObject
        val objekter = root["objekter"]?.jsonArray.orEmpty()
        val segments = objekter.flatMap { parse(it.jsonObject) }

        // Vi følger `start`-tokenet, ikke `neste.href`. Href-en i responsen har historisk pekt
        // på gamle stier (uten /api/v4/), så det er tryggere å bygge URL-en selv.
        val next = root["metadata"]?.jsonObject
            ?.get("neste")?.jsonObject
            ?.get("start")?.jsonPrimitive?.contentOrNull

        return Page(segments, next, objekter.size)
    }

    /**
     * Ett [SpeedLimitSegment] per sammenhengende linjestykke, ikke ett per vegobjekt.
     *
     * Et vegobjekt kan ha MULTILINESTRING-geometri: flere adskilte strekninger med samme
     * fartsgrense. Tidligere ble de slått sammen med `flatten()` til én punktliste, og
     * kart-matchingen - som binder sammen punkt i og i+1 - trakk da en rett linje fra enden av
     * én strekning til starten av den neste. Den linja finnes ikke i virkeligheten, men den er
     * like matchbar som en ekte veg.
     *
     * Effekten var systematisk og gikk alltid samme vei: en 30- eller 50-sone i et tettsted
     * består av mange korte, adskilte strekninger, mens en 70- eller 80-veg er én lang. Byens
     * fantomlinjer spente derfor på kryss og tvers over hovedvegen, og lot en lav grense vinne
     * på avstand mot den riktige høye.
     */
    private fun parseSpeedLimits(obj: JsonObject): List<SpeedLimitSegment> {
        val id = obj["id"]?.jsonPrimitive?.longOrNullSafe() ?: return emptyList()

        val limit = property(obj, EGENSKAP_FARTSGRENSE)
            ?.get("verdi")?.jsonPrimitive?.intOrNullSafe()
            ?: return emptyList()

        val roadRef = obj["lokasjon"]?.jsonObject
            ?.get("vegsystemreferanser")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("kortform")?.jsonPrimitive?.contentOrNull

        return lines(obj).map { SpeedLimitSegment(id, limit, it, roadRef) }
    }

    /**
     * Vegobjekttype 595 heter «Motorveg» og dekker «strekninger som har vedtatt status
     * motorveg» - altså begge slag. Egenskap 5378 skiller dem, og det er bare ekte motorveg
     * som gir satsen for 36-40 km/t over.
     *
     * Vi ekskluderer bare det som *eksplisitt* er motortrafikkveg, i stedet for å kreve at
     * noe eksplisitt er motorveg. NVDB oppgir enum-egenskaper med både kode (`enum_id`) og
     * tekst (`verdi`), og skulle den ene formen mangle eller endre seg, er «objekt av typen
     * Motorveg regnes som motorveg» det som fortsatt stemmer med typedefinisjonen.
     */
    private fun parseMotorways(obj: JsonObject): List<MotorwaySegment> {
        val id = obj["id"]?.jsonPrimitive?.longOrNullSafe() ?: return emptyList()

        val type = property(obj, EGENSKAP_MOTORVEGTYPE)
        val enumId = type?.get("enum_id")?.jsonPrimitive?.intOrNullSafe()
        val text = type?.get("verdi")?.jsonPrimitive?.contentOrNull
        val motortrafikkveg = enumId == ENUM_MOTORTRAFIKKVEG ||
            text?.startsWith("Motortrafikk", ignoreCase = true) == true
        if (motortrafikkveg) return emptyList()

        return lines(obj).map { MotorwaySegment(id, it) }
    }

    private fun property(obj: JsonObject, id: Int): JsonObject? =
        obj["egenskaper"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.intOrNullSafe() == id }

    private fun lines(obj: JsonObject): List<List<LatLon>> {
        val wkt = obj["geometri"]?.jsonObject?.get("wkt")?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        return Wkt.parseLines(wkt)
    }

    companion object {
        private const val TAG = "NvdbClient"
        const val BASE = "https://nvdbapiles.atlas.vegvesen.no"
        const val TYPE_FARTSGRENSE = 105
        const val EGENSKAP_FARTSGRENSE = 2021
        const val TYPE_MOTORVEG = 595
        const val EGENSKAP_MOTORVEGTYPE = 5378
        const val ENUM_MOTORVEG = 7355
        const val ENUM_MOTORTRAFIKKVEG = 7356
        private const val PAGE_SIZE = 1000

        /** Nok til å få med NVDBs forklaring, lite nok til å ikke fylle diagnostikkflaten. */
        private const val MAX_ERROR_BODY = 400

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
