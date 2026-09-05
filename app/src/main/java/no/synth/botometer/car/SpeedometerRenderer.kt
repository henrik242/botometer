package no.synth.botometer.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import no.synth.botometer.alert.SpeedWatch
import no.synth.botometer.fine.FineCalculator
import no.synth.botometer.fine.FineEstimate
import no.synth.botometer.limit.MatchConfidence
import no.synth.botometer.speed.FixFreshness
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

    private var reading: SpeedWatch.Reading? = null

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

    fun update(reading: SpeedWatch.Reading) {
        this.reading = reading
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

        val r = reading
        if (r == null) {
            smallText.textSize = unit * 1.0f
            canvas.drawText("Venter på GPS…", cx, area.centerY().toFloat(), smallText)
            return
        }

        val lost = r.freshness == FixFreshness.LOST
        val estimate = r.estimate

        // Fargen bæres av boten, ikke av farten: grønn = ingen bot, gul = bot uten prikker,
        // oransje = prikker, rød = anmeldelse/tap av førerrett. Er GPS-en borte, er det ingen
        // konsekvens å farge - da er grått det ærlige svaret.
        val accent = when {
            lost -> Color.rgb(150, 150, 150)
            estimate is FineEstimate.NoOffence -> Color.rgb(60, 190, 100)
            estimate is FineEstimate.UnknownLimit -> Color.rgb(150, 150, 150)
            estimate is FineEstimate.SimplifiedFine ->
                if (estimate.points == 0) Color.rgb(230, 195, 60) else Color.rgb(240, 140, 40)
            estimate is FineEstimate.Prosecution -> Color.rgb(225, 55, 55)
            else -> Color.rgb(150, 150, 150)
        }

        // Linje 1: farten. «--» og ikke «0» når signalet er borte: null er en måling, og vi har
        // ingen. Et nulltall ville dessuten sett ut som at bilen står stille.
        bigText.color = accent
        bigText.textSize = unit * 3.4f
        canvas.drawText(if (lost) "--" else "${r.speedKmt.roundToInt()}", cx, area.top + unit * 3.4f, bigText)
        smallText.textSize = unit * 0.85f
        canvas.drawText("km/t", cx, area.top + unit * 4.3f, smallText)

        // Fartsgrenseskilt oppe til høyre
        drawSign(canvas, area, unit, r)

        // Linje 2: konsekvensen
        midText.color = accent
        midText.textSize = unit * 2.2f
        val headline = when {
            lost -> "Ingen GPS"
            estimate is FineEstimate.UnknownLimit -> "Ukjent fartsgrense"
            estimate is FineEstimate.NoOffence -> "Ingen bot"
            estimate is FineEstimate.SimplifiedFine -> "${nok.format(estimate.amountNok)} kr"
            estimate is FineEstimate.Prosecution -> "Anmeldelse"
            else -> ""
        }
        canvas.drawText(headline, cx, area.top + unit * 6.9f, midText)

        // Linje 3: detaljer
        smallText.textSize = unit * 0.9f
        canvas.drawText(subtitle(r), cx, area.top + unit * 8.1f, smallText)

        smallText.textSize = unit * 0.7f
        smallText.color = Color.rgb(110, 110, 115)
        canvas.drawText(footer(r), cx, area.bottom - unit * 0.4f, smallText)
        smallText.color = Color.LTGRAY
    }

    private fun subtitle(r: SpeedWatch.Reading): String {
        // Signalet først. Alt annet på skjermen bygger på et fix, og er det borte, er det den
        // eneste beskjeden som betyr noe.
        when (r.freshness) {
            FixFreshness.LOST ->
                return if (r.limitKmt != null) {
                    "Ingen GPS · fartsgrensen ${r.limitKmt} km/t gjelder fortsatt"
                } else {
                    "Ingen GPS - tunnel?"
                }
            FixFreshness.STALE -> return "Mistet GPS-signalet - venter"
            FixFreshness.FRESH -> Unit
        }

        return when (val estimate = r.estimate) {
            is FineEstimate.UnknownLimit ->
                if (r.match == null) "Ingen NVDB-data her" else "Fartsgrense mangler i NVDB"
            is FineEstimate.NoOffence -> {
                val margin = calculator.kmtToNextBand(r.speedKmt, r.limitKmt)
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
        val rates = "satser ${calculator.version}" + if (ratesStale) " ⚠" else ""
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
