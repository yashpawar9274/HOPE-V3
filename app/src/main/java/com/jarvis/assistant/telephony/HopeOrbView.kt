package com.jarvis.assistant.telephony

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The HOPE ORB drawn natively on a Canvas (no WebView / WebGL), so it renders on every phone,
 * on the lock screen, and with any caller background behind it.
 *
 * States: ringing, listening, thinking (connecting), active, idle (on hold), disconnected.
 * Colours, pulse, ripples and spin ease smoothly from one state to the next.
 */
class HopeOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Look(
        val primary: Int,
        val secondary: Int,
        val glow: Float,
        val scale: Float,
        val pulseHz: Float,
        val pulseAmp: Float,
        val spin: Float,
        val ripple: Float,
        val rippleHz: Float
    )

    private class Particle(
        val radius: Float,
        val speed: Float,
        val phase: Float,
        val tilt: Float,
        val flatten: Float,
        val size: Float,
        val mix: Float
    )

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()

    private var target = LOOKS.getValue("idle")
    private var cPrimary = target.primary
    private var cSecondary = target.secondary
    private var cGlow = target.glow
    private var cScale = target.scale
    private var cPulseHz = target.pulseHz
    private var cPulseAmp = target.pulseAmp
    private var cSpin = target.spin
    private var cRipple = target.ripple
    private var cRippleHz = target.rippleHz
    private var time = 0f
    private var lastFrame = 0L
    private var stateName = "idle"

    private val particles: List<Particle> = run {
        val random = java.util.Random(7L)
        List(54) {
            Particle(
                radius = 1.28f + random.nextFloat() * 0.55f,
                speed = (0.25f + random.nextFloat() * 0.75f) * (if (random.nextBoolean()) 1f else -1f),
                phase = random.nextFloat() * (2f * PI.toFloat()),
                tilt = (random.nextFloat() - 0.5f) * 1.8f,
                flatten = 0.28f + random.nextFloat() * 0.34f,
                size = 1.1f + random.nextFloat() * 2.2f,
                mix = random.nextFloat()
            )
        }
    }

    fun setState(name: String) {
        if (name == stateName) return
        stateName = name
        target = LOOKS[name] ?: LOOKS.getValue("idle")
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 64L).toFloat()
        lastFrame = now
        time += dtMs / 1000f
        val ease = 1f - exp(-dtMs / 240f)
        cPrimary = mixColor(cPrimary, target.primary, ease)
        cSecondary = mixColor(cSecondary, target.secondary, ease)
        cGlow += (target.glow - cGlow) * ease
        cScale += (target.scale - cScale) * ease
        cPulseHz += (target.pulseHz - cPulseHz) * ease
        cPulseAmp += (target.pulseAmp - cPulseAmp) * ease
        cSpin += (target.spin - cSpin) * ease
        cRipple += (target.ripple - cRipple) * ease
        cRippleHz += (target.rippleHz - cRippleHz) * ease

        val cx = w / 2f
        val cy = h / 2f
        // The glow and ripples reach about 1.9x the core radius; keep them inside the view.
        val base = min(w, h) / 2f / 1.95f
        val breathe = 1f + cPulseAmp * sin(time * 2f * PI.toFloat() * cPulseHz)
        val r = base * cScale * breathe

        drawGlow(canvas, cx, cy, r)
        drawRipples(canvas, cx, cy, r)
        drawParticles(canvas, cx, cy, r, front = false)
        drawSphere(canvas, cx, cy, r)
        drawParticles(canvas, cx, cy, r, front = true)

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val radius = r * 2.0f
        fill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                withAlpha(cPrimary, (120 * cGlow).toInt()),
                withAlpha(cSecondary, (46 * cGlow).toInt()),
                withAlpha(cSecondary, 0)
            ),
            floatArrayOf(0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
    }

    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (cRipple < 0.02f) return
        for (i in 0 until 3) {
            val phase = (time * cRippleHz + i / 3f) % 1f
            val ringRadius = r * (1.06f + 0.84f * phase)
            val fade = (1f - phase)
            stroke.color = withAlpha(cPrimary, (170 * fade * fade * cRipple).toInt())
            stroke.strokeWidth = density * (2.6f - 1.5f * phase)
            canvas.drawCircle(cx, cy, ringRadius, stroke)
        }
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, r: Float, front: Boolean) {
        for (p in particles) {
            val theta = p.phase + time * p.speed * (0.6f + cSpin * 0.5f)
            val ex = cos(theta) * r * p.radius
            val ey = sin(theta) * r * p.radius * p.flatten
            val x = cx + ex * cos(p.tilt) - ey * sin(p.tilt)
            val y = cy + ex * sin(p.tilt) + ey * cos(p.tilt)
            val depth = sin(theta)
            if ((depth > 0f) != front) continue
            val color = mixColor(cPrimary, Color.WHITE, p.mix * 0.5f)
            val alpha = if (front) 150 + (depth * 90f).toInt() else 60 + ((1f + depth) * 50f).toInt()
            fill.color = withAlpha(color, (alpha * (0.45f + 0.55f * cGlow)).toInt().coerceIn(0, 255))
            canvas.drawCircle(x, y, density * p.size * (0.8f + 0.35f * depth), fill)
        }
    }

    private fun drawSphere(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Glassy body: bright upper-left, deep lower-right.
        fill.shader = RadialGradient(
            cx - r * 0.32f, cy - r * 0.38f, r * 1.32f,
            intArrayOf(
                mixColor(cPrimary, Color.WHITE, 0.62f),
                cPrimary,
                mixColor(cSecondary, Color.BLACK, 0.62f)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)

        // Inner energy bands that turn slowly inside the sphere.
        for (i in 0 until 3) {
            val bandRadius = r * (0.46f + 0.17f * i)
            val sweep = SweepGradient(
                cx, cy,
                intArrayOf(
                    withAlpha(Color.WHITE, 0),
                    withAlpha(Color.WHITE, 140 - 25 * i),
                    withAlpha(cSecondary, 0),
                    withAlpha(cSecondary, 110),
                    withAlpha(Color.WHITE, 0)
                ),
                floatArrayOf(0f, 0.16f, 0.5f, 0.78f, 1f)
            )
            matrix.setRotate((time * (28f + 22f * i) * (0.4f + cSpin * 0.4f)) * (if (i % 2 == 0) 1f else -1f), cx, cy)
            sweep.setLocalMatrix(matrix)
            stroke.shader = sweep
            stroke.strokeWidth = density * (2.4f - 0.5f * i)
            canvas.drawCircle(cx, cy, bandRadius, stroke)
        }

        // Rim light.
        val rim = SweepGradient(
            cx, cy,
            intArrayOf(
                withAlpha(Color.WHITE, 0),
                withAlpha(Color.WHITE, 230),
                withAlpha(Color.WHITE, 0),
                withAlpha(cSecondary, 190),
                withAlpha(Color.WHITE, 0)
            ),
            floatArrayOf(0f, 0.18f, 0.5f, 0.76f, 1f)
        )
        matrix.setRotate(time * 38f * (0.4f + cSpin * 0.4f), cx, cy)
        rim.setLocalMatrix(matrix)
        stroke.shader = rim
        stroke.strokeWidth = density * 2.6f
        canvas.drawCircle(cx, cy, r - density * 1.2f, stroke)
        stroke.shader = null

        // Specular highlight.
        fill.shader = RadialGradient(
            cx - r * 0.34f, cy - r * 0.42f, r * 0.42f,
            intArrayOf(withAlpha(Color.WHITE, 210), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.42f, r * 0.42f, fill)
        fill.shader = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private companion object {
        val LOOKS = mapOf(
            "ringing" to Look(0xFF4FE3FF.toInt(), 0xFF5B8CFF.toInt(), 1.00f, 1.00f, 1.30f, 0.060f, 1.4f, 1.0f, 0.55f),
            "listening" to Look(0xFF3DF5B0.toInt(), 0xFF4FE3FF.toInt(), 1.00f, 1.00f, 0.90f, 0.050f, 1.0f, 0.8f, 0.40f),
            "reading" to Look(0xFF4FE3FF.toInt(), 0xFF8B5CF6.toInt(), 0.92f, 0.98f, 0.62f, 0.035f, 0.8f, 0.55f, 0.34f),
            "thinking" to Look(0xFFB28CFF.toInt(), 0xFF5B8CFF.toInt(), 0.90f, 0.97f, 0.70f, 0.040f, 2.2f, 1.0f, 0.50f),
            "error" to Look(0xFFFF5C72.toInt(), 0xFFFF9A52.toInt(), 0.88f, 0.96f, 0.72f, 0.045f, 0.5f, 0.75f, 0.46f),
            "responding" to Look(0xFF5B8CFF.toInt(), 0xFF4FE3FF.toInt(), 0.95f, 1.00f, 0.42f, 0.035f, 0.9f, 0.35f, 0.3f),
            "active" to Look(0xFF5B8CFF.toInt(), 0xFF4FE3FF.toInt(), 0.85f, 1.00f, 0.25f, 0.025f, 0.6f, 0.0f, 0.30f),
            "idle" to Look(0xFFFFB84F.toInt(), 0xFFFF8A5C.toInt(), 0.55f, 0.92f, 0.15f, 0.015f, 0.2f, 0.0f, 0.30f),
            "disconnected" to Look(0xFFFF4D5E.toInt(), 0xFFFF2D6F.toInt(), 0.35f, 0.85f, 0.10f, 0.010f, 0.1f, 0.0f, 0.30f)
        )
    }
}
