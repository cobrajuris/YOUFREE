package com.youfree.island

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** Pequena "fábrica" de views para a ilha, que é montada toda em código. */
object Ui {
    val BLACK = 0xF2000000.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val PURPLE = 0xFFB18CFF.toInt()
    val GREEN = 0xFF3DDC97.toInt()
    val YELLOW = 0xFFFFC94D.toInt()
    val RED = 0xFFFF5A5F.toInt()
    val MUTED = 0xFF9A9AA5.toInt()
    val TEXT = 0xFFE6E6EA.toInt()
    val CHIP = 0xFF1E1E27.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun text(ctx: Context, sp: Float, color: Int = Color.WHITE, bold: Boolean = false, value: CharSequence = "") =
        TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun icon(ctx: Context, res: Int, tint: Int = Color.WHITE) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Botão arredondado com texto. */
    fun pill(ctx: Context, label: String, color: Int = CHIP, onClick: () -> Unit) =
        text(ctx, 14f, Color.WHITE, bold = true, value = label).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            background = rounded(color, dpf(ctx, 20f))
            setOnClickListener { onClick() }
        }

    fun clipRound(view: View, radius: Float, color: Int = CHIP) {
        view.background = rounded(color, radius)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }
}
