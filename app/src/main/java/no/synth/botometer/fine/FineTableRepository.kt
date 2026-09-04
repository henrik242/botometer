package no.synth.botometer.fine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TableStatus(
    val table: FineTable,
    val origin: Origin,
    val ageDays: Long?,
    val stale: Boolean,
) {
    enum class Origin { ASSET, DOWNLOADED }
}

/**
 * Satsene lastes fra nett med den innebygde asseten som fallback.
 *
 * Hvorfor dette er nødvendig når appen sideloades: Google Play var oppdateringskanalen. Uten den
 * kjører en APK bygget i 2026 fortsatt 2026-satser i 2029, uten at brukeren merker noe. Satsene
 * justeres omtrent årlig. Et tall som er feil uten å se feil ut er verre enn ingen tall.
 *
 * Integritet: HTTPS mot en kjent URL er nok her. Angrepsflaten er et galt kronebeløp, ikke
 * kodekjøring - JSON-en tolkes av en streng deserialiserer og brukes bare til oppslag.
 */
class FineTableRepository(
    private val filesDir: File,
    private val assetTable: () -> FineTable,
    private val remoteUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val today: () -> LocalDate = LocalDate::now,
) {
    /**
     * Konstruktøren appen bruker. Den over tar disk og asset direkte, slik at cache-, alders- og
     * nedlastingslogikken kan testes på JVM uten Android-runtime.
     */
    constructor(
        context: Context,
        remoteUrl: String,
        http: OkHttpClient = OkHttpClient(),
        today: () -> LocalDate = LocalDate::now,
    ) : this(context.filesDir, { FineTable.fromAssets(context) }, remoteUrl, http, today)

    private val cacheFile: File get() = File(filesDir, CACHE_NAME)

    /** Synkron og rask: brukes ved oppstart og i bilen. Ingen nettverk. */
    fun load(): TableStatus {
        val downloaded = runCatching {
            if (cacheFile.exists()) FineTable.fromJson(cacheFile.readText()) else null
        }.getOrNull()

        val asset = assetTable()

        // Nyeste versjon vinner. Er den nedlastede eldre enn asseten, har brukeren nettopp
        // installert en nyere APK enn cachen - da skal asseten brukes.
        val table = when {
            downloaded == null -> asset
            versionDate(downloaded)?.isAfter(versionDate(asset)) == true -> downloaded
            else -> asset
        }
        val origin = if (table === asset) TableStatus.Origin.ASSET else TableStatus.Origin.DOWNLOADED

        val age = versionDate(table)?.let { ChronoUnit.DAYS.between(it, today()) }
        return TableStatus(table, origin, age, stale = age != null && age > STALE_AFTER_DAYS)
    }

    /** Kalles fra telefonen, aldri fra bilskjermen. Nettverksarbeid hører ikke hjemme under kjøring. */
    suspend fun refresh(): Result<TableStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(remoteUrl).header("Accept", "application/json").build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("tomt svar")
            }
            // Parse FØR vi skriver til disk, ellers cacher vi en ødelagt fil som overlever restart.
            val parsed = FineTable.fromJson(body)
            require(parsed.grupper.isNotEmpty()) { "tabellen mangler grupper" }
            cacheFile.writeText(body)
            Log.i(TAG, "Hentet satser versjon ${parsed.versjon}")
            load()
        }
    }

    private fun versionDate(table: FineTable): LocalDate? =
        runCatching { LocalDate.parse(table.versjon) }.getOrNull()

    private companion object {
        const val TAG = "FineTableRepo"
        const val CACHE_NAME = "botesatser-cache.json"

        /** Satsene justeres omtrent årlig; over 400 dager gamle skal flagges. */
        const val STALE_AFTER_DAYS = 400L
    }
}
