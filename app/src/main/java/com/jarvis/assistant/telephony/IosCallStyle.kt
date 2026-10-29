package com.jarvis.assistant.telephony

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iPhone-style design tokens and small view factories shared by the HOPE Phone screens
 * (dialer, contacts, recents, incoming / outgoing / active call).
 */
object IosCallStyle {
    val GREEN = Color.rgb(48, 209, 88)
    val RED = Color.rgb(255, 69, 58)
    val BLUE = Color.rgb(10, 132, 255)
    val ORANGE = Color.rgb(255, 159, 10)

    /** Colour set for list screens (dialer, contacts, recents). */
    class Palette(val dark: Boolean) {
        val background = if (dark) Color.BLACK else Color.rgb(242, 242, 247)
        val cell = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
        val label = if (dark) Color.WHITE else Color.BLACK
        val secondary = Color.rgb(142, 142, 147)
        val separator = if (dark) Color.rgb(56, 56, 58) else Color.rgb(198, 198, 200)
        val key = if (dark) Color.rgb(51, 51, 51) else Color.rgb(226, 226, 230)
        val search = if (dark) Color.rgb(28, 28, 30) else Color.rgb(228, 228, 233)
        val tabBar = if (dark) Color.rgb(22, 22, 24) else Color.rgb(249, 249, 249)
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun roundRect(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Circle with a press ripple. */
    fun rippleCircle(color: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
        circle(color),
        circle(Color.WHITE)
    )

    fun icon(context: Context, resId: Int, tint: Int, sizeDp: Int): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Round button with an icon and a small caption underneath, like the iPhone in-call grid. */
    class RoundAction(
        context: Context,
        iconRes: Int,
        caption: String,
        private val sizeDp: Int = 72
    ) : LinearLayout(context) {
        private val disc = FrameLayout(context)
        private val glyph = ImageView(context)
        private val captionView = TextView(context)
        private var activeFill = Color.WHITE
        private var idleFill = Color.argb(64, 255, 255, 255)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val size = dp(context, sizeDp)
            glyph.setImageResource(iconRes)
            glyph.setColorFilter(Color.WHITE)
            val pad = dp(context, sizeDp / 4)
            glyph.setPadding(pad, pad, pad, pad)
            disc.addView(glyph, FrameLayout.LayoutParams(size, size))
            disc.background = rippleCircle(idleFill)
            addView(disc, LayoutParams(size, size))
            captionView.text = caption
            captionView.textSize = 12f
            captionView.gravity = Gravity.CENTER
            captionView.setTextColor(Color.WHITE)
            captionView.setPadding(0, dp(context, 6), 0, 0)
            addView(captionView, LayoutParams(-2, -2))
            isClickable = true
            isFocusable = true
            contentDescription = caption
        }

        fun setFill(color: Int, iconTint: Int = Color.WHITE) {
            disc.background = rippleCircle(color)
            glyph.setColorFilter(iconTint)
        }

        /** iPhone toggle look: white disc with dark icon when on, translucent disc when off. */
        fun setActive(active: Boolean) {
            if (active) setFill(activeFill, Color.BLACK) else setFill(idleFill, Color.WHITE)
        }

        fun setCaption(text: String) {
            captionView.text = text
            contentDescription = text
        }

        fun setIcon(resId: Int) = glyph.setImageResource(resId)

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }
    }

    fun bold(view: TextView): TextView = view.apply { setTypeface(typeface, Typeface.BOLD) }

    fun spacer(context: Context, heightDp: Int) =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp)) }

    /** Soft initial-letter avatar like the iPhone contact placeholder. */
    fun avatar(context: Context, name: String, sizeDp: Int, dark: Boolean): TextView {
        val initial = name.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
        return TextView(context).apply {
            text = initial
            gravity = Gravity.CENTER
            textSize = sizeDp * 0.42f
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (dark) intArrayOf(Color.rgb(142, 142, 147), Color.rgb(99, 99, 102))
                else intArrayOf(Color.rgb(176, 176, 181), Color.rgb(142, 142, 147))
            ).apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }
    }

    /** DTMF / dialpad letters under each digit, exactly as on iOS. */
    fun lettersFor(digit: String): String = when (digit) {
        "2" -> "ABC"; "3" -> "DEF"; "4" -> "GHI"; "5" -> "JKL"
        "6" -> "MNO"; "7" -> "PQRS"; "8" -> "TUV"; "9" -> "WXYZ"
        "0" -> "+"
        else -> ""
    }
}
