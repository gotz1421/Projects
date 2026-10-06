package com.voicerewriter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The VoiceFlow bubble, drawn by hand so its three states can animate smoothly into each other:
 *
 *  - [State.IDLE]       cyan→blue disc with a white microphone and a soft glow. Static, so a
 *                       bubble that sits on screen all day costs no redraws.
 *  - [State.RECORDING]  same disc, with light rings rippling outward and the disc breathing
 *                       with the live mic level.
 *  - [State.PROCESSING] deep navy disc with an animated waveform and a rotating cyan arc,
 *                       while the speech is transcribed and cleaned up.
 *
 * State changes cross-fade over ~220 ms. The frame clock only runs while something moves.
 */
class FlowBubbleView(context: Context) : View(context) {

    enum class State { IDLE, RECORDING, PROCESSING, CANCELLED }

    private companion object {
        val CYAN = Color.parseColor("#22D3EE")
        val BLUE = Color.parseColor("#0EA5E9")
        val SKY = Color.parseColor("#2563EB")
        val NAVY = Color.parseColor("#0B1929")
        val MINT = Color.parseColor("#A7F3D0")
        val RING = Color.parseColor("#BFF4FB")
        const val FADE_MS = 220f
        /** Disc radius as a fraction of the view's half-size; the rest is room for rings/glow. */
        const val DISC = 0.72f
    }

    var state: State = State.IDLE
        private set
    private var previous: State = State.IDLE
    private var fadeStart = 0L

    private var levelTarget = 0f
    private var level = 0f

    /**
     * The window size the disc is designed for. While recording the service grows the window so
     * the ripples have room; the disc keeps this size so the bubble doesn't jump.
     */
    var baseSizePx = 0
    /** Disc radius used for this frame. */
    private var discR = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    /** Microphone in a 100×100 box (same geometry as res/drawable/ic_voiceflow.xml). */
    private val micBody = Path().apply {
        addRoundRect(RectF(38f, 14f, 62f, 60f), 12f, 12f, Path.Direction.CW)
    }
    private val micCradle = Path().apply {
        moveTo(27f, 47f)
        cubicTo(27f, 61f, 37f, 71f, 50f, 71f)
        cubicTo(63f, 71f, 73f, 61f, 73f, 47f)
        moveTo(50f, 71f)
        lineTo(50f, 85f)
    }

    private var discShader: Shader? = null
    private var glowShader: Shader? = null
    private var shaderSize = -1

    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    fun switchTo(newState: State) {
        if (newState == state) return
        previous = state
        state = newState
        fadeStart = now()
        if (newState != State.RECORDING) { levelTarget = 0f }
        syncClock()
        invalidate()
    }

    /** Raw peak amplitude from the recorder (0..32767). */
    fun setAmplitude(raw: Int) {
        levelTarget = (raw / 14000f).coerceIn(0f, 1f)
    }

    private fun now() = System.currentTimeMillis()

    private fun fading(): Boolean = now() - fadeStart < FADE_MS

    private fun syncClock() {
        val animate = isAttachedToWindow && (state != State.IDLE || fading() || state == State.CANCELLED)
        if (animate && !clock.isStarted) clock.start()
        if (!animate && clock.isStarted) clock.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncClock()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    private fun ensureShaders(cx: Float, cy: Float, half: Float, r: Float) {
        if (shaderSize == width) return
        shaderSize = width
        discShader = LinearGradient(
            cx - r * 0.6f, cy - r, cx + r * 0.6f, cy + r,
            intArrayOf(CYAN, BLUE, SKY), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        glowShader = RadialGradient(
            cx, cy, half,
            intArrayOf(withAlpha(CYAN, 0.45f), withAlpha(CYAN, 0.18f), Color.TRANSPARENT),
            floatArrayOf(r / half * 0.92f, r / half * 1.12f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = min(width, height) / 2f
        if (half <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = (if (baseSizePx > 0) baseSizePx / 2f else half) * DISC
        discR = r
        ensureShaders(cx, cy, half, r)

        // Ease the live level: quick to rise with the voice, slow to fall, so it breathes
        // rather than jitters.
        level += (levelTarget - level) * (if (levelTarget > level) 0.45f else 0.12f)

        val t = (now() % 100_000L) / 1000f // seconds, wraps far beyond any session
        val mix = ((now() - fadeStart) / FADE_MS).coerceIn(0f, 1f)
        if (mix < 1f) drawState(canvas, previous, 1f - mix, cx, cy, half, r, t)
        drawState(canvas, state, mix, cx, cy, half, r, t)

        if (mix >= 1f) syncClock()
    }

    private fun drawState(
        canvas: Canvas, s: State, alpha: Float,
        cx: Float, cy: Float, half: Float, r: Float, t: Float,
    ) {
        if (alpha <= 0.01f) return
        when (s) {
            State.IDLE -> {
                drawGlow(canvas, cx, cy, half, alpha)
                drawDisc(canvas, cx, cy, r, alpha)
                drawMic(canvas, cx, cy, r, alpha)
            }
            State.RECORDING -> {
                // A soft halo that swells with the voice.
                val haloR = r * (1.45f + level * 0.45f)
                fill.shader = RadialGradient(
                    cx, cy, haloR,
                    intArrayOf(withAlpha(CYAN, alpha * (0.40f + level * 0.25f)), withAlpha(CYAN, alpha * 0.12f), Color.TRANSPARENT),
                    floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, haloR, fill)
                fill.shader = null
                // Three rings rippling out, eased so they leave the disc fast and settle softly;
                // they brighten while you speak.
                for (k in 0..2) {
                    val p = ((t / 2.1f) + k / 3f) % 1f
                    val e = 1f - (1f - p) * (1f - p)
                    val rr = r * 1.04f + (half - r * 1.04f) * e
                    stroke.shader = null
                    stroke.strokeWidth = r * 0.07f * (1f - p * 0.6f)
                    val a = (1f - p) * (1f - p) * (0.45f + level * 0.55f)
                    stroke.color = withAlpha(RING, alpha * a)
                    canvas.drawCircle(cx, cy, rr, stroke)
                }
                // A thin mint "live" ring hugging the disc, tracking the voice level.
                stroke.strokeWidth = r * 0.07f
                stroke.color = withAlpha(MINT, alpha * (0.25f + level * 0.6f))
                canvas.drawCircle(cx, cy, r * (1.06f + level * 0.08f), stroke)
                // The disc breathes gently even in silence, and more with the voice.
                val idle = 0.018f * sin(t * 2f * PI.toFloat() / 1.8f)
                val breathe = r * (1f + idle + level * 0.06f)
                drawDisc(canvas, cx, cy, breathe, alpha)
                drawMic(canvas, cx, cy, breathe, alpha)
            }
            State.CANCELLED -> {
                // "Cancelled": a quick side-to-side shake that dies out while the disc turns
                // slate, shrinks a touch and shows an ✕ — then the service returns it to rest.
                val since = (now() - fadeStart) / 1000f
                val shake = sin(since * 38f) * r * 0.10f * (1f - (since / 0.45f)).coerceIn(0f, 1f)
                val k = 1f - 0.12f * (since / 0.3f).coerceIn(0f, 1f)
                canvas.save()
                canvas.translate(shake, 0f)
                fill.shader = null
                fill.color = withAlpha(Color.parseColor("#475569"), alpha)
                canvas.drawCircle(cx, cy, r * k, fill)
                stroke.shader = null
                stroke.color = withAlpha(Color.WHITE, alpha)
                stroke.strokeWidth = r * 0.14f
                val a = r * 0.32f * k
                canvas.drawLine(cx - a, cy - a, cx + a, cy + a, stroke)
                canvas.drawLine(cx + a, cy - a, cx - a, cy + a, stroke)
                canvas.restore()
            }
            State.PROCESSING -> {
                drawGlow(canvas, cx, cy, half, alpha * 0.8f)
                fill.shader = null
                fill.color = withAlpha(NAVY, alpha)
                canvas.drawCircle(cx, cy, r, fill)
                // Rotating arc just inside the edge.
                val inset = r * 0.16f
                oval.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
                stroke.strokeWidth = r * 0.11f
                stroke.shader = null
                stroke.color = withAlpha(Color.WHITE, alpha * 0.08f)
                canvas.drawArc(oval, 0f, 360f, false, stroke)
                stroke.shader = SweepGradient(
                    cx, cy, intArrayOf(withAlpha(CYAN, 0f), withAlpha(CYAN, alpha), withAlpha(BLUE, alpha)),
                    floatArrayOf(0f, 0.6f, 0.75f),
                )
                canvas.save()
                canvas.rotate((t * 300f) % 360f, cx, cy)
                canvas.drawArc(oval, 0f, 270f, false, stroke)
                canvas.restore()
                stroke.shader = null
                // Five waveform bars, each on its own phase.
                val bars = floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
                val barW = r * 0.10f
                val gap = r * 0.075f
                val total = bars.size * barW + (bars.size - 1) * gap
                var x = cx - total / 2f + barW / 2f
                stroke.strokeWidth = barW
                for ((i, base) in bars.withIndex()) {
                    val wave = 0.55f + 0.45f * sin((t * 2f * PI.toFloat() * 1.1f) + i * 0.9f)
                    val h = r * 0.62f * base * wave
                    stroke.color = withAlpha(if (i == 2) MINT else CYAN, alpha)
                    canvas.drawLine(x, cy - h / 2f, x, cy + h / 2f, stroke)
                    x += barW + gap
                }
            }
        }
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, half: Float, alpha: Float) {
        fill.shader = glowShader
        fill.alpha = (alpha * 255).toInt()
        canvas.drawCircle(cx, cy, half, fill)
        fill.shader = null
        fill.alpha = 255
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        fill.shader = discShader
        fill.alpha = (alpha * 255).toInt()
        canvas.save()
        val k = r / discR
        canvas.scale(k, k, cx, cy) // the shader is laid out for the base radius
        canvas.drawCircle(cx, cy, r / k, fill)
        canvas.restore()
        fill.shader = null
        fill.alpha = 255
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        val box = r * 1.15f // the 100-unit mic box, as a share of the disc
        val scale = box / 100f
        canvas.save()
        canvas.translate(cx - box / 2f, cy - box / 2f)
        canvas.scale(scale, scale)
        fill.shader = null
        fill.color = withAlpha(Color.WHITE, alpha)
        canvas.drawPath(micBody, fill)
        stroke.shader = null
        stroke.color = withAlpha(Color.WHITE, alpha)
        stroke.strokeWidth = 7f
        canvas.drawPath(micCradle, stroke)
        canvas.restore()
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
