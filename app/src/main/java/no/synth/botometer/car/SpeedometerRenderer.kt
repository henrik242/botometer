package no.synth.botometer.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.limit.LimitMatch
import no.synth.botometer.speed.SpeedFix
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tegner direkte på Surface fra bilskjermen. Layoutet er bevisst grovt: tre linjer med stor
 * skrift, ingen animasjon, ingen interaksjon. Alt som krever mer enn et blikk hører ikke
 * hjemme på en bilskjerm i bevegelse.
 *
 * `visibleArea` fra verten er ikke det samme som hele flaten - deler kan være dekket av
 * systemets UI (klokke, navigasjonsknapper), så vi sentrerer innholdet i det synlige feltet.
 */
class SpeedometerRenderer(
    private val carContext: CarContext,
    private val calculator: FineCalculator,
    private val ratesStale: Boolean = false,
) : SurfaceCallback {

    private var container: SurfaceContainer? = null
    private var visible: Rect? = null

    private var fix: SpeedFix? = null
    private var match: LimitMatch? = null

    private val nok = NumberFormat.getIntegerInstance(Locale("nb", "NO"))

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
        color = Color.LTGRAY
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

    fun update(fix: SpeedFix, match: LimitMatch?) {
        this.fix = fix
        this.match = match
        draw()
    }

    private fun draw() {
        val surface = container?.surface ?: return
        if (!surface.isValid) return

        val canvas = try { surface.lockCanvas(null) } catch (t: Throwable) { return }
        try {
            canvas.drawColor(Color.rgb(12, 12, 14))
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

        val f = fix
        if (f == null) {
            smallText.textSize = unit * 1.0f
            canvas.drawText("Venter på GPS…", cx, area.centerY().toFloat(), smallText)
            return
        }

        val speed = f.speedKmt.roundToInt()
        val limit = match?.limitKmt
        val estimate = calculator.estimate(f.speedKmt, limit)

        // Fargen bæres av boten, ikke av farten: grønn = ingen bot, gul = bot uten prikker,
        // oransje = prikker, rød = anmeldelse/tap av førerrett.
        val accent = when (estimate) {
            is FineEstimate.NoOffence -> Color.rgb(60, 190, 100)
            is FineEstimate.UnknownLimit -> Color.rgb(150, 150, 150)
            is FineEstimate.SimplifiedFine ->
                if (estimate.points == 0) Color.rgb(230, 195, 60) else Color.rgb(240, 140, 40)
            is FineEstimate.Prosecution -> Color.rgb(225, 55, 55)
        }

        // Linje 1: farten
        bigText.color = accent
        bigText.textSize = unit * 3.4f
        canvas.drawText("$speed", cx, area.top + unit * 3.4f, bigText)
        smallText.textSize = unit * 0.85f
        canvas.drawText("km/t", cx, area.top + unit * 4.3f, smallText)

        // Fartsgrenseskilt oppe til høyre
        drawSign(canvas, area, unit, limit, match?.stale == true)

        // Linje 2: konsekvensen
        midText.color = accent
        midText.textSize = unit * 2.2f
        val headline = when (estimate) {
            is FineEstimate.UnknownLimit -> "Ukjent fartsgrense"
            is FineEstimate.NoOffence -> "Ingen bot"
            is FineEstimate.SimplifiedFine -> "${nok.format(estimate.amountNok)} kr"
            is FineEstimate.Prosecution -> "Anmeldelse"
        }
        canvas.drawText(headline, cx, area.top + unit * 6.9f, midText)

        // Linje 3: detaljer
        smallText.textSize = unit * 0.9f
        canvas.drawText(subtitle(estimate, f), cx, area.top + unit * 8.1f, smallText)

        smallText.textSize = unit * 0.7f
        smallText.color = Color.rgb(110, 110, 115)
        canvas.drawText(footer(), cx, area.bottom - unit * 0.4f, smallText)
        smallText.color = Color.LTGRAY
    }

    private fun subtitle(estimate: FineEstimate, f: SpeedFix): String = when (estimate) {
        is FineEstimate.UnknownLimit ->
            if (match == null) "Ingen NVDB-data her" else "Fartsgrense mangler i NVDB"
        is FineEstimate.NoOffence -> {
            val margin = calculator.kmtToNextBand(f.speedKmt, match?.limitKmt)
            if (margin != null && margin <= 10) "$margin km/t til første bot" else "Innenfor"
        }
        is FineEstimate.SimplifiedFine -> buildString {
            append("${estimate.overKmt} km/t over")
            if (estimate.points > 0) append(" · ${estimate.points} prikker")
            when (estimate.licence) {
                no.synth.botometer.fine.LicenceOutcome.VURDERES -> append(" · beslag vurderes")
                no.synth.botometer.fine.LicenceOutcome.INNDRAS -> append(" · tap av førerrett")
                else -> Unit
            }
            if (estimate.uncertain) append(" (usikker)")
        }
        is FineEstimate.Prosecution ->
            "${estimate.overKmt} km/t over · over taket for forenklet forelegg · tap av førerrett"
    }

    private fun footer(): String {
        val ref = match?.roadRef
        val stale = if (match?.stale == true) " · cachet" else ""
        // Utdaterte satser markeres på bilskjermen også. Et beløp som ser sikkert ut men bygger
        // på tre år gamle satser er verre enn ingen beløp.
        val rates = "satser ${calculator.version}" + if (ratesStale) " ⚠" else ""
        return listOfNotNull(ref, rates).joinToString(" · ") + stale
    }

    private fun drawSign(canvas: Canvas, area: Rect, unit: Float, limit: Int?, stale: Boolean) {
        val r = unit * 1.5f
        val cx = area.right - r - unit * 0.8f
        val cy = area.top + r + unit * 0.8f

        signFill.alpha = if (stale) 110 else 255
        signRing.alpha = if (stale) 110 else 255
        signText.alpha = if (stale) 140 else 255
        signRing.strokeWidth = r * 0.22f

        canvas.drawCircle(cx, cy, r, signFill)
        canvas.drawCircle(cx, cy, r - signRing.strokeWidth / 2, signRing)

        signText.textSize = r * 0.95f
        val label = limit?.toString() ?: "?"
        canvas.drawText(label, cx, cy + signText.textSize * 0.36f, signText)
    }
}
