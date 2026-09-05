package no.synth.botometer

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Tar vare på siste krasj, så brukeren har noe å vise fram.
 *
 * Uten Play Console finnes ingen crash-rapportering, og en telefon i en bil har ingen adb. Et
 * krasj etterlot seg derfor ingenting: appen forsvant, og neste oppstart så ut som en helt vanlig
 * oppstart. Da er det ingenting å feilsøke på - bare en anelse om at «den krasjer av og til».
 *
 * Håndtereren kjører mens prosessen er på vei ned. Den skal derfor gjøre minst mulig, og alt den
 * gjør må tåle å feile: en krasj i krasjhåndtereren skjuler den opprinnelige feilen.
 */
object CrashLog {

    private const val FILE = "siste-krasj.txt"

    /** Maks tegn som vises i UI. Toppen av stacktracen er den som sier hvor det skjedde. */
    const val MAX_SHOWN = 3_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Kjeden må gå videre. Svelger vi feilen her, dør ikke prosessen slik Android
            // forventer, og appen blir stående i en halvdød tilstand som er verre enn krasjet.
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(context.filesDir, FILE).writeText(
            buildString {
                appendLine(Instant.now().toString())
                appendLine("Botometer ${BuildConfig.VERSION_NAME}")
                appendLine("Tråd: ${thread.name}")
                appendLine()
                append(trace)
            }
        )
    }
}
