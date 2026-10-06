package com.youfree.island

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sistema de design da ilha: cores do modo escuro da Apple, tipografia Inter (a fonte
 * aberta mais próxima do SF Pro), movimento com mola, toque que "afunda" e vibração leve.
 */
object Ui {
    // ----- Cores (paleta escura do iOS) -----
    val ISLAND = 0xFF000000.toInt()
    val BG = 0xFF000000.toInt()
    val SURFACE = 0xFF1C1C1E.toInt()
    val ELEVATED = 0xFF2C2C2E.toInt()
    val FILL = 0xFF3A3A3C.toInt()
    val SEPARATOR = 0xFF38383A.toInt()
    val LABEL = 0xFFFFFFFF.toInt()
    val SECONDARY = 0x99EBEBF5.toInt()
    val TERTIARY = 0x4DEBEBF5
    val HAIRLINE = 0x1FFFFFFF

    val BLUE = 0xFF0A84FF.toInt()
    val GREEN = 0xFF30D158.toInt()
    val RED = 0xFFFF453A.toInt()
    val ORANGE = 0xFFFF9F0A.toInt()
    val YELLOW = 0xFFFFD60A.toInt()
    val PURPLE = 0xFFBF5AF2.toInt()
    val INDIGO = 0xFF5E5CE6.toInt()
    val PINK = 0xFFFF375F.toInt()
    val TEAL = 0xFF64D2FF.toInt()
    val GRAY = 0xFF8E8E93.toInt()

    // Nomes antigos, usados em alguns pontos.
    val ACCENT = BLUE
    val TEXT = 0xE6FFFFFF.toInt()
    val MUTED = SECONDARY
    val CHIP = ELEVATED

    // ----- Tipografia -----
    enum class Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD, DISPLAY, DISPLAY_SEMIBOLD }

    private val fonts = HashMap<Weight, Typeface>()

    fun font(ctx: Context, w: Weight): Typeface = fonts.getOrPut(w) {
        val res = when (w) {
            Weight.REGULAR -> R.font.inter_regular
            Weight.MEDIUM -> R.font.inter_medium
            Weight.SEMIBOLD -> R.font.inter_semibold
            Weight.BOLD -> R.font.inter_bold
            Weight.DISPLAY -> R.font.inter_display_bold
            Weight.DISPLAY_SEMIBOLD -> R.font.inter_display_semibold
        }
        try {
            ctx.resources.getFont(res)
        } catch (_: Exception) {
            Typeface.create(Typeface.DEFAULT, if (w == Weight.REGULAR || w == Weight.MEDIUM) Typeface.NORMAL else Typeface.BOLD)
        }
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun text(
        ctx: Context,
        sp: Float,
        color: Int = LABEL,
        bold: Boolean = false,
        value: CharSequence = "",
        weight: Weight = if (bold) Weight.SEMIBOLD else Weight.REGULAR,
    ) = TextView(ctx).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        typeface = font(ctx, weight)
        // Ajuste fino de espaçamento como no SF: títulos levemente mais justos.
        letterSpacing = when {
            sp >= 28f -> -0.02f
            sp >= 17f -> -0.01f
            sp <= 12f -> 0.01f
            else -> 0f
        }
    }

    fun icon(ctx: Context, res: Int, tint: Int = LABEL) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun gradient(radius: Float, vararg colors: Int, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR) =
        GradientDrawable(orientation, colors).apply { cornerRadius = radius }

    fun clipRound(view: View, radius: Float, color: Int = ELEVATED) {
        view.background = rounded(color, radius)
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    /** Ícone de app no formato "squircle" (cantos de ~22% como no iOS). */
    fun appIcon(ctx: Context, size: Int) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipRound(this, dpf(ctx, size * 0.225f))
    }

    /** Quadradinho colorido com ícone branco, como nos Ajustes do iPhone. */
    fun iconTile(ctx: Context, res: Int, color: Int, sizeDp: Int = 30, iconDp: Int = 18): View =
        FrameLayout(ctx).apply {
            background = rounded(color, dpf(ctx, sizeDp * 0.24f))
            addView(icon(ctx, res), FrameLayout.LayoutParams(dp(ctx, iconDp), dp(ctx, iconDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    // ----- Botões -----

    enum class ButtonStyle { PRIMARY, SECONDARY, TINTED }

    /** Botão cápsula: PRIMARY = branco com texto preto (estilo Apple), SECONDARY = cinza escuro. */
    fun capsule(ctx: Context, label: String, style: ButtonStyle = ButtonStyle.SECONDARY, iconRes: Int = 0, onClick: () -> Unit): View {
        val fg = when (style) {
            ButtonStyle.PRIMARY -> Color.BLACK
            ButtonStyle.SECONDARY -> LABEL
            ButtonStyle.TINTED -> BLUE
        }
        val bg = when (style) {
            ButtonStyle.PRIMARY -> LABEL
            ButtonStyle.SECONDARY -> ELEVATED
            ButtonStyle.TINTED -> 0x330A84FF
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            background = rounded(bg, dpf(ctx, 100f))
            if (iconRes != 0) {
                addView(icon(ctx, iconRes, fg), LinearLayout.LayoutParams(dp(ctx, 17), dp(ctx, 17)).apply { marginEnd = dp(ctx, 6) })
            }
            addView(text(ctx, 15f, fg, value = label, weight = Weight.SEMIBOLD))
            pressable(this, onClick)
        }
    }

    /** Botão redondo só com ícone. */
    fun circle(ctx: Context, res: Int, sizeDp: Int, bg: Int = ELEVATED, tint: Int = LABEL, iconDp: Int = sizeDp / 2, onClick: () -> Unit): View =
        FrameLayout(ctx).apply {
            background = oval(bg)
            addView(icon(ctx, res, tint), FrameLayout.LayoutParams(dp(ctx, iconDp), dp(ctx, iconDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            pressable(this, onClick)
        }

    // ----- Interação -----

    fun haptic(view: View, strong: Boolean = false) {
        view.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    /** Ao tocar, a view "afunda" um pouco e volta com mola; vibra de leve no clique. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).alpha(0.85f)
                    .setDuration(110).setInterpolator(SPRING_FAST).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(380).setInterpolator(SPRING_BOUNCY).start()
            }
            false
        }
        view.setOnClickListener {
            haptic(it)
            onClick()
        }
    }

    /** Entrada em cascata: cada filho sobe um pouco e aparece, um depois do outro. */
    fun stagger(parent: android.view.ViewGroup, startDelay: Long = 90) {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            c.alpha = 0f
            c.translationY = dpf(parent.context, 10f)
            c.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + i * 35L)
                .setDuration(420).setInterpolator(SPRING_SMOOTH).start()
        }
    }

    // ----- Mola -----

    /**
     * Interpolador de mola amortecida (como as animações do iOS). [damping] < 1 dá um leve
     * "quique"; [response] é o tempo aproximado de assentar (fração da duração da animação).
     */
    class Spring(private val damping: Float, private val response: Float = 0.55f) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val omega = 2 * PI.toFloat() / response
            val zeta = damping
            return if (zeta < 1f) {
                val wd = omega * sqrt(1 - zeta * zeta)
                1 - exp(-zeta * omega * t) * (cos(wd * t) + (zeta * omega / wd) * sin(wd * t))
            } else {
                1 - exp(-omega * t) * (1 + omega * t)
            }
        }
    }

    val SPRING_SMOOTH = Spring(0.86f, 0.5f)
    val SPRING_BOUNCY = Spring(0.62f, 0.5f)
    val SPRING_FAST = Spring(1f, 0.35f)
    val SPRING_ISLAND = Spring(0.74f, 0.62f)
}
