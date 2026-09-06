package no.synth.botometer.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import no.synth.botometer.alert.ReadingText
import no.synth.botometer.alert.Severity
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.limit.MatchConfidence
import no.synth.botometer.speed.FixFreshness

/**
 * Tegner direkte på Surface fra bilskjermen. Layoutet er bevisst grovt: tre linjer med stor
 * skrift, ingen animasjon, ingen interaksjon. Alt som krever mer enn et blikk hører ikke
 * hjemme på en bilskjerm i bevegelse.
 *
 * `visibleArea` fra verten er ikke det samme som hele flaten - deler kan være dekket av
 * systemets UI (klokke, navigasjonsknapper), så vi sentrerer innholdet i det synlige feltet.
 */
class SpeedometerRenderer(
    /**
     * Lambdaer og ikke verdier: satsene kan byttes ut mens skjermen står, og en `val` tatt ved
     * konstruksjon ville frosset både beløpene og «UTDATERT»-merket til bil-økta ble laget på
     * nytt.
     */
    private val calculator: () -> FineCalculator,
    private val ratesStale: () -> Boolean = { false },
    /**
     * Bilens dag/natt-modus, lest fra verten ved hver tegning. En lambda og ikke en `CarContext`:
     * resten av denne klassen er Canvas og Paint, og det eneste den trengte bilkonteksten til
     * var dette ene svaret.
     */
    private val darkMode: () -> Boolean = { true },
) : SurfaceCallback {

    /**
     * Bilskjermen byttet aldri palett. Android Auto skifter hele hodeenheten mellom dag og natt
     * fra bilens lyssensor, mens Botometer sto svart gjennom begge: et svart hull ved siden av
     * et hvitt system-UI i dagslys, og - verre - en mørk bunn under direkte sol, der gråtonene
     * som er tunet for natt er det første som forsvinner i gjenskinnet.
     */
    private data class Palette(
        val ground: Int,
        val muted: Int,
        val footer: Int,
        val ok: Int,
        val fine: Int,
        val points: Int,
        val prosecution: Int,
        val signFill: Int,
        val signText: Int,
    )

    private val night = Palette(
        ground = Color.rgb(12, 12, 14),
        muted = Color.rgb(150, 150, 150),
        footer = Color.rgb(110, 110, 115),
        ok = Color.rgb(60, 190, 100),
        fine = Color.rgb(230, 195, 60),
        points = Color.rgb(240, 140, 40),
        prosecution = Color.rgb(225, 55, 55),
        signFill = Color.WHITE,
        signText = Color.BLACK,
    )

    private val day = Palette(
        ground = Color.rgb(245, 245, 247),
        muted = Color.rgb(110, 110, 115),
        footer = Color.rgb(95, 95, 100),
        ok = Color.rgb(25, 120, 60),
        fine = Color.rgb(150, 110, 0),
        points = Color.rgb(160, 85, 0),
        prosecution = Color.rgb(200, 45, 45),
        signFill = Color.WHITE,
        signText = Color.BLACK,
    )

    private var palette = night

    private var container: SurfaceContainer? = null
    private var visible: Rect? = null

    private var reading: SpeedWatch.Reading? = null

    private val bigText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val midText = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallText = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val signFill = Paint().apply { isAntiAlias = true; color = Color.WHITE }
    private val signRing = Paint().apply {
        isAntiAlias = true
        color = Color.rgb(200, 30, 30)
        style = Paint.Style.STROKE
    }
    private val signText = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (visible == null) visible = stableArea
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    fun update(reading: SpeedWatch.Reading) {
        this.reading = reading
        draw()
    }

    private fun draw() {
        val surface = container?.surface ?: return
        if (!surface.isValid) return

        palette = if (darkMode()) night else day
        smallText.color = palette.muted
        signFill.color = palette.signFill
        signText.color = palette.signText

        val canvas = try { surface.lockCanvas(null) } catch (t: Throwable) { return }
        try {
            canvas.drawColor(palette.ground)
            val area = visible ?: Rect(0, 0, container!!.width, container!!.height)
            render(canvas, area)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun render(canvas: Canvas, area: Rect) {
        val cx = area.centerX().toFloat()
        val h = area.height().toFloat()
        val unit = h / 10f   // all typografi skaleres mot synlig høyde

        val r = reading
        if (r == null) {
            smallText.textSize = unit * 1.0f
            canvas.drawText("Venter på GPS…", cx, area.centerY().toFloat(), smallText)
            return
        }

        // Fargen bæres av boten, ikke av farten: grønn = ingen bot, gul = bot uten prikker,
        // oransje = prikker, rød = anmeldelse/tap av førerrett. Er GPS-en borte, er det ingen
        // konsekvens å farge - da er grått det ærlige svaret.
        val accent = when (ReadingText.severity(r)) {
            Severity.LOST, Severity.UNKNOWN -> palette.muted
            Severity.NONE -> palette.ok
            Severity.FINE -> palette.fine
            Severity.POINTS -> palette.points
            Severity.PROSECUTION -> palette.prosecution
        }

        // Linje 1: farten.
        bigText.color = accent
        bigText.textSize = unit * 3.4f
        canvas.drawText(ReadingText.speed(r), cx, area.top + unit * 3.4f, bigText)
        smallText.textSize = unit * 0.85f
        canvas.drawText("km/t", cx, area.top + unit * 4.3f, smallText)

        // Fartsgrenseskilt oppe til høyre
        drawSign(canvas, area, unit, r)

        // Linje 2: konsekvensen
        midText.color = accent
        midText.textSize = unit * 2.2f
        canvas.drawText(ReadingText.headline(r), cx, area.top + unit * 6.9f, midText)

        // Linje 3: detaljer
        smallText.textSize = unit * 0.9f
        fitted(subtitle(r), area, unit * 0.9f, unit * 0.6f)
        canvas.drawText(subtitle(r), cx, area.top + unit * 8.1f, smallText)

        fitted(footer(r), area, unit * 0.7f, unit * 0.5f)
        smallText.color = palette.footer
        canvas.drawText(footer(r), cx, area.bottom - unit * 0.4f, smallText)
        smallText.color = palette.muted
    }

    /**
     * Krymper [smallText] til teksten får plass.
     *
     * Den ble tegnet sentrert uten å måles, så den lengste linja - «45 km/t over · over taket
     * for forenklet forelegg · tap av førerrett» - falt av i BEGGE ender. Det som forsvant var
     * «tap av førerrett», altså poenget med linja, på den mest alvorlige avlesningen appen kan
     * vise.
     */
    private fun fitted(text: String, area: Rect, from: Float, floor: Float) {
        var size = from
        smallText.textSize = size
        val max = area.width() * 0.94f
        while (size > floor && smallText.measureText(text) > max) {
            size *= 0.92f
            smallText.textSize = size
        }
    }

    private fun subtitle(r: SpeedWatch.Reading): String {
        // Signalet først. Alt annet på skjermen bygger på et fix, og er det borte, er det den
        // eneste beskjeden som betyr noe.
        when (r.freshness) {
            FixFreshness.LOST ->
                return if (r.limitKmt != null) {
                    "Ingen GPS · fartsgrensen ${r.limitKmt} km/t gjelder fortsatt"
                } else {
                    // Ikke et spørsmål. Sjåføren skal ikke svare på noe under kjøring.
                    "Ingen GPS · tunnel eller dårlig dekning"
                }
            FixFreshness.STALE -> return "Mistet GPS-signalet – venter"
            FixFreshness.FRESH -> Unit
        }

        return when (val estimate = r.estimate) {
            is FineEstimate.UnknownLimit ->
                if (r.match == null) "Ingen NVDB-data her" else "Fartsgrense mangler i NVDB"
            is FineEstimate.NoOffence -> {
                val margin = calculator().kmtToNextBand(r.speedKmt, r.limitKmt)
                if (margin != null && margin <= 10) "$margin km/t til første bot" else "Innenfor"
            }
            is FineEstimate.SimplifiedFine, is FineEstimate.Prosecution ->
                ReadingText.consequences(r)
        }
    }

    private fun footer(r: SpeedWatch.Reading): String {
        val ref = r.match?.roadRef
        val source = when {
            r.manualLimit -> " · fartsgrense satt manuelt"
            r.match?.stale == true -> " · cachet"
            r.match?.confidence == MatchConfidence.LOW -> " · usikkert vegvalg"
            else -> ""
        }
        // Utdaterte satser markeres på bilskjermen også. Et beløp som ser sikkert ut men bygger
        // på tre år gamle satser er verre enn ingen beløp.
        val rates = "satser ${calculator().version}" + if (ratesStale()) " ⚠" else ""
        return listOfNotNull(ref, rates).joinToString(" · ") + source
    }

    /**
     * Skiltet dempes når tallet ikke er å stole på: gammelt treff, usikkert vegvalg, eller dødt
     * GPS-signal. Det er samme grep som `stale` alltid har hatt - poenget er at et tall som ikke
     * gjelder heller ikke skal se ut som om det gjør det.
     */
    private fun drawSign(canvas: Canvas, area: Rect, unit: Float, r: SpeedWatch.Reading) {
        val radius = unit * 1.5f
        val cx = area.right - radius - unit * 0.8f
        val cy = area.top + radius + unit * 0.8f

        val dimmed = r.freshness != FixFreshness.FRESH ||
            r.match?.stale == true ||
            r.match?.confidence == MatchConfidence.LOW

        signFill.alpha = if (dimmed) 110 else 255
        signRing.alpha = if (dimmed) 110 else 255
        signText.alpha = if (dimmed) 140 else 255
        signRing.strokeWidth = radius * 0.22f

        canvas.drawCircle(cx, cy, radius, signFill)
        canvas.drawCircle(cx, cy, radius - signRing.strokeWidth / 2, signRing)

        signText.textSize = radius * 0.95f
        canvas.drawText(r.limitKmt?.toString() ?: "?", cx, cy + signText.textSize * 0.36f, signText)
    }
}
