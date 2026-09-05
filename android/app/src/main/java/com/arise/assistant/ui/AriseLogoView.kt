package com.arise.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.util.AttributeSet
import android.view.View
import com.arise.assistant.engine.ArisePhase
import com.arise.assistant.settings.Settings
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Premium dimensional Arise mark — pure Canvas (zero 3D-engine weight).
 *
 * A translucent glass core with layered depth, an orbiting particle halo,
 * parallax sparks, rim light and soft glows. Motion is state-reactive:
 * IDLE breathes at low intensity, WAKING pulses, LISTENING blooms with
 * microphone amplitude, PROCESSING orbits layers, SPEAKING pulses to speech,
 * SUCCESS flashes, ERROR warns.
 *
 * All settings (motion, quality, battery saver, reduced motion, theme, size,
 * voice reactivity, idle intensity) come from [Settings] every frame so the
 * panel applies live.
 */
class AriseLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val settings = Settings(context)

    // theme palette
    private data class Palette(val core: Int, val core2: Int, val accent: Int, val glow: Int, val spark: Int)
    private val themes = mapOf(
        "violet" to Palette(0xFF7C5CFF.toInt(), 0xFF4B2BD6.toInt(), 0xFF38E1C6.toInt(), 0x337C5CFF.toInt(), 0xFFFFC857.toInt()),
        "ocean" to Palette(0xFF2FA8FF.toInt(), 0xFF0B6BD9.toInt(), 0xFF7CF0FF.toInt(), 0x332FA8FF.toInt(), 0xFFFFB86B.toInt()),
        "sunset" to Palette(0xFFFF5F8F.toInt(), 0xFFC0306B.toInt(), 0xFFFFC857.toInt(), 0x33FF5F8F.toInt(), 0xFF8E7CFF.toInt()),
        "mono" to Palette(0xFFE8ECF8.toInt(), 0xFF8B93B0.toInt(), 0xFFFFFFFF.toInt(), 0x22FFFFFF.toInt(), 0xFFFFFFFF.toInt())
    )

    // paints allocated once
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastT = 0L
    private var seed = (System.currentTimeMillis() % 100000).toFloat()

    /** Float-domain trig (Canvas wants Float angles). */
    private fun s(v: Float): Float = kotlin.math.sin(v.toDouble()).toFloat()
    private fun c(v: Float): Float = kotlin.math.cos(v.toDouble()).toFloat()

    @Volatile var currentPhase: ArisePhase = ArisePhase.IDLE
    @Volatile var micLevel: Float = 0f   // 0..1 live mic amplitude (voice-reactive)
    @Volatile var scaleFactor: Float = 1f

    fun setPhase(p: ArisePhase) { currentPhase = p; invalidate() }
    fun setAudioLevel(v: Float) { micLevel = v; if (settings.logoVoiceReactive) invalidate() }
    fun setSizeScale(s: Float) { scaleFactor = s; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10 || h < 10) return
        val now = System.currentTimeMillis()
        val t = (now - lastT).toFloat() / 1000f
        lastT = now
        seed += t * 0.7f

        val pal = themes[settings.theme] ?: themes.getValue("violet")
        val motionEnabled = settings.logoAnimationEnabled && !settings.logoReducedMotion
        val quality = qualityTier()
        val cx = w / 2f
        val cy = h / 2f
        val R = min(w, h) * 0.28f * scaleFactor   // core radius

        // soft backdrop vignette for depth (subtle, cheap)
        bgPaint.shader = RadialGradient(cx, cy, w * 0.62f, 0x00000000.toInt(), 0x08000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val anim = motionEnabled
        val time = if (anim) (now / 1000f) else 0f

        // state-driven numbers
        val idleI = if (anim) settings.logoIdleIntensity else 0.06f
        val (pulse, bloom, breathe, orbit, flash, warn, anything) = stateMotion(pal, time, idleI)

        val cx2 = cx + s(time * 0.9f) * R * 0.06f * orbit // parallax drift
        val cy2 = cy + c(time * 0.7f) * R * 0.05f * orbit

        // ----- ambient ring (dashed approximation by arcs) -----
        ringPaint.color = adjustAlpha(pal.accent, 90 + (pulse * 110).toInt())
        ringPaint.strokeWidth = (R * 0.055f).coerceAtLeast(2.5f)
        val ringR = R * (1.42f + 0.03f * pulse)
        drawRing(canvas, cx, cy, ringR, ringPaint, time, orbit)

        // ----- halo (radial glow) -----
        haloPaint.shader = RadialGradient(cx2, cy2, R * (1.1f + bloom * 0.9f),
            blend(pal.glow, pal.core, 0.35f), 0x00000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx2, cy2, R * (1.1f + bloom), haloPaint)

        // ----- orbiting particles -----
        if (quality > 0) {
            val n = when (quality) { 1 -> 6; 2 -> 12; else -> 16 }
            particlePaint.alpha = (140 + (pulse * 80).toInt()).coerceAtMost(255)
            for (i in 0 until n) {
                val a = (i * (2 * PI / n)).toFloat() + time * (0.5f + 0.25f * orbit)
                val pr = R * (1.62f + 0.12f * s(seed + i * 1.7f) * idleI)
                val px = cx + c(a) * pr
                val py = cy + s(a) * pr * 0.86f
                val ps = R * (0.05f + 0.04f * s(seed * 2 + i * 3.1f))
                particlePaint.color = if (i % 3 == 0) pal.spark else pal.accent
                canvas.drawCircle(px, py, ps, particlePaint)
            }
        }

        // ----- dimensional core: glass orb -----
        val grad = LinearGradient(cx2, cy2 - R, cx2, cy2 + R,
            blend(pal.core, Color.WHITE, 0.10f), pal.core2, Shader.TileMode.CLAMP)
        orbPaint.shader = grad
        orbPaint.alpha = 255
        canvas.drawCircle(cx2, cy2, R * (1f + 0.10f * pulse), orbPaint)
        orbPaint.shader = null

        // specular top-left highlight (glass feel)
        orbPaint.color = 0x55FFFFFF
        canvas.drawCircle(cx2 - R * 0.34f, cy2 - R * 0.42f, R * 0.22f, orbPaint)
        orbPaint.color = 0x22FFFFFF
        canvas.drawCircle(cx2 - R * 0.14f, cy2 - R * 0.6f, R * 0.5f, orbPaint)
        orbPaint.alpha = 255

        // rim light (depth edge)
        rimPaint.color = adjustAlpha(Color.WHITE, 40 + (pulse * 60).toInt())
        rimPaint.strokeWidth = R * 0.045f
        canvas.drawCircle(cx2, cy2, R * (1f + 0.10f * pulse) * 0.985f, rimPaint)

        // inner nucleus (layered depth)
        orbPaint.color = blend(pal.core, Color.BLACK, 0.35f)
        canvas.drawCircle(cx2, cy2, R * 0.42f, orbPaint)
        orbPaint.color = pal.accent
        orbPaint.alpha = (150 + (pulse * 90).toInt()).coerceAtMost(255)
        canvas.drawCircle(cx2 + c(time) * R * 0.13f, cy2 + s(time * 0.8f) * R * 0.13f, R * 0.20f, orbPaint)

        // 4-point spark, gold (signature)
        val sk = R * 0.32f
        sparkPaint.color = pal.spark
        sparkPaint.alpha = (200 + (pulse * 55).toInt()).coerceAtMost(255)
        val sa = time * 0.5f
        canvas.save()
        canvas.rotate(45f + sa * 40f, cx2 + R * 0.78f, cy2 - R * 0.72f)
        drawSpark(canvas, cx2 + R * 0.78f, cy2 - R * 0.72f, sk)
        canvas.restore()

        // ------------------ state accents ------------------
        if (flash > 0f) {
            // success: expanding bright ring
            ringPaint.color = pal.accent
            ringPaint.strokeWidth = (R * 0.18f * flash)
            canvas.drawCircle(cx2, cy2, R * (1f + flash * 0.9f), ringPaint)
        }
        if (warn > 0f) {
            // error: amber slash pulses
            warnPaint.color = 0xFFFF5470.toInt()
            warnPaint.strokeWidth = R * 0.14f
            val dx = R * 0.5f * warn
            canvas.drawLine(cx - dx, cy - dx, cx + dx, cy + dx, warnPaint)
        }
        // speech frequency jitter handled by pulse already (audio-reactive)
        if (motionEnabled) postInvalidateOnAnimation()
    }

    // paints for accents
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** Extract per-state motion numbers given elapsed clock time. */
    private fun stateMotion(pal: Palette, time: Float, idleI: Float): StateMotion {
        val core = pal.core
        val blink = (s(time * 3.0f) + 1f) / 2f
        return when (currentPhase) {
            ArisePhase.IDLE -> StateMotion(0.12f + 0.10f * idleI * blink, 0f, 0.25f, 0.3f, 0f, 0f, true)
            ArisePhase.WAKING -> StateMotion(0.55f + 0.4f * blink, 0.25f, 1f, 1f, 0f, 0f, true)
            ArisePhase.VERIFYING -> StateMotion(0.35f + 0.25f * s(time * 5f), 0.18f, 0.7f, 0.6f, 0f, 0f, true)
            ArisePhase.ACK -> StateMotion(0.7f, 0.4f, 1.1f, 1f, 0f, 0f, true)
            ArisePhase.LISTENING -> StateMotion(
                0.55f + 0.5f * micLevel.coerceIn(0f, 1f),
                0.5f + 1.3f * micLevel.coerceIn(0f, 1f),
                1f + 0.7f * micLevel.coerceIn(0f, 1f),
                0.9f, 0f, 0f, true
            )
            ArisePhase.PROCESSING -> StateMotion(0.45f, 0.3f, 0.4f, 1.6f, 0f, 0f, true)
            ArisePhase.SPEAKING -> StateMotion(0.35f + 0.3f * micLevel + 0.25f * blink, 0.3f + micLevel * 0.5f, 1.1f, 0.9f, 0f, 0f, true)
            ArisePhase.CONFIRMING -> StateMotion(0.5f + 0.3f * blink, 0.3f, 1.2f, 0.6f, 0f, 0f, true)
            ArisePhase.SUCCESS -> StateMotion(0.5f + 0.5f * blink, 0.4f, 1.4f, 0.8f, 0.45f * (1f - blink), 0f, true)
            ArisePhase.ERROR -> StateMotion(0.5f + 0.4f * blink, 0.15f, 0.9f, 0.5f, 0f, 0.8f + 0.3f * blink, true)
        }
    }

    private data class StateMotion(
        val pulse: Float, val bloom: Float, val breathe: Float,
        val orbit: Float, val flash: Float, val warn: Float, val anything: Boolean
    )

    private fun qualityTier(): Int {
        if (!settings.logoAnimationEnabled) return 0
        if (settings.batterySaverAutoFx && isBatteryLow()) return 1
        return when (settings.logoQuality) {
            "low" -> 1
            "medium" -> 2
            else -> 3
        }
    }

    private fun isBatteryLow(): Boolean = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val l = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        l in 0..18
    } catch (_: Exception) { false }

    private fun drawRing(c: Canvas, cx: Float, cy: Float, r: Float, paint: Paint, time: Float, orbit: Float) {
        val dashLen = r * 0.7f + orbit * r * 0.2f
        val step = 0.69813f // 2π/9
        val off = time * 0.6f * orbit
        for (i in 0 until 9) {
            val a0 = i * step + off
            val a1 = min(a0 + step * 0.42f, a0 + step)
            c.drawArc(cx - r, cy - r, cx + r, cy + r, a0 * 57.29578f, (a1 - a0) * 57.29578f, false, paint)
        }
    }

    private fun drawSpark(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x, y, s * 0.22f, sparkPaint)
        val tips = listOf(
            x to y - s, x to y + s, x - s to y, x + s to y,
            x - s * 0.62f to y - s * 0.62f, x + s * 0.62f to y + s * 0.62f,
            x - s * 0.62f to y + s * 0.62f, x + s * 0.62f to y - s * 0.62f
        )
        for ((tx, ty) in tips) c.drawLine(x, y, tx, ty, sparkPaint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ta = t.coerceIn(0f, 1f)
        return Color.argb(
            ((Color.alpha(a) * (1 - ta)) + Color.alpha(b) * ta).toInt(),
            ((Color.red(a) * (1 - ta)) + Color.red(b) * ta).toInt(),
            ((Color.green(a) * (1 - ta)) + Color.green(b) * ta).toInt(),
            ((Color.blue(a) * (1 - ta)) + Color.blue(b) * ta).toInt()
        )
    }

    private fun adjustAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

}
