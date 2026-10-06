package com.youfree.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Barra de ajuste no estilo da Central de Controle: cápsula grossa que se enche de branco,
 * com o ícone dentro. Arrastar muda o valor de forma relativa (como no iPhone).
 */
@SuppressLint("ViewConstructor")
class PillSlider(context: Context, iconRes: Int) : View(context) {

    var value = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var enabledLook = true
        set(v) {
            field = v
            invalidate()
        }
    var onChange: ((Float) -> Unit)? = null
    var onDisabledTap: (() -> Unit)? = null
    var onTouchActivity: (() -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5C787880 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val clip = Path()
    private val icon: Drawable = context.getDrawable(iconRes)!!.mutate()
    private var downX = 0f
    private var startValue = 0f
    private var lastEdge = -1

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        clip.reset()
        rect.set(0f, 0f, w, h)
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, track)
        val fw = w * value
        if (enabledLook) {
            rect.set(0f, 0f, fw, h)
            canvas.drawRect(rect, fill)
        }
        canvas.restore()

        val s = (h * 0.42f).toInt()
        val left = (h * 0.32f).toInt()
        val top = ((h - s) / 2f).toInt()
        icon.setBounds(left, top, left + s, top + s)
        val covered = enabledLook && fw > left + s * 0.6f
        icon.colorFilter = PorterDuffColorFilter(
            if (!enabledLook) Ui.GRAY else if (covered) 0xFF3A3A3C.toInt() else Color.WHITE,
            PorterDuff.Mode.SRC_IN,
        )
        icon.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        onTouchActivity?.invoke()
        if (!enabledLook) {
            if (e.actionMasked == MotionEvent.ACTION_UP) onDisabledTap?.invoke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                startValue = value
                animate().scaleX(1.03f).scaleY(1.06f).setDuration(160).setInterpolator(Ui.SPRING_SMOOTH).start()
            }
            MotionEvent.ACTION_MOVE -> {
                value = startValue + (e.x - downX) / width
                val edge = if (value <= 0f) 0 else if (value >= 1f) 1 else -1
                if (edge >= 0 && edge != lastEdge) Ui.haptic(this)
                lastEdge = edge
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(Ui.SPRING_BOUNCY).start()
                lastEdge = -1
            }
        }
        return true
    }
}

/** Botão redondo de alternar (Wi-Fi, Bluetooth, Lanterna...) com legenda embaixo. */
class ToggleTile(context: Context, iconRes: Int, label: String, private val onColor: Int) : LinearLayout(context) {

    private val circle = FrameLayout(context)
    private val glyph = Ui.icon(context, iconRes)
    private val caption: TextView = Ui.text(context, 11f, Ui.SECONDARY, value = label, weight = Ui.Weight.MEDIUM)

    var on = false
        set(v) {
            field = v
            circle.background = Ui.oval(if (v) onColor else 0xFF2C2C2E.toInt())
            glyph.imageTintList = android.content.res.ColorStateList.valueOf(
                if (v && onColor == Color.WHITE) Color.BLACK else Color.WHITE,
            )
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = Ui.dp(context, 54)
        circle.addView(glyph, FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24), Gravity.CENTER))
        addView(circle, LayoutParams(size, size))
        caption.gravity = Gravity.CENTER
        caption.maxLines = 1
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 6) })
        on = false
    }

    fun setOnTap(action: () -> Unit) = Ui.pressable(circle, action)
}

/**
 * Orbe animado da assistente, inspirado na Siri: manchas de cor girando dentro de um
 * círculo, que crescem com a voz e ganham um anel colorido enquanto escutam.
 */
class OrbView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state = State.IDLE
        set(v) {
            field = v
            invalidate()
        }

    /** 0..1, volume da voz. */
    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
        }
    private var smoothLevel = 0f

    private val colors = intArrayOf(Ui.PINK, Ui.PURPLE, Ui.BLUE, Ui.TEAL)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f
        val t = SystemClock.uptimeMillis() / 1000f
        smoothLevel += (level - smoothLevel) * 0.25f

        val speed = when (state) {
            State.IDLE -> 0.35f
            State.LISTENING -> 1.1f + smoothLevel * 1.5f
            State.THINKING -> 1.8f
            State.SPEAKING -> 0.9f
        }
        val pulse = when (state) {
            State.IDLE -> 0.02f * sin(t * 1.6f)
            State.LISTENING -> 0.08f * smoothLevel
            State.THINKING -> 0.03f * sin(t * 4f)
            State.SPEAKING -> 0.04f * (0.5f + 0.5f * sin(t * 7f))
        }
        val r = radius * (0.86f + pulse)

        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        base.shader = RadialGradient(cx, cy, r, 0xFF1A1033.toInt(), 0xFF05030A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, base)
        for (i in colors.indices) {
            val a = t * speed * (0.8f + i * 0.17f) + i * 1.7f
            val bx = cx + cos(a) * r * 0.38f
            val by = cy + sin(a * 1.3f) * r * 0.38f
            val br = r * (0.72f + 0.12f * sin(t * 1.3f + i))
            blob.shader = RadialGradient(bx, by, br, colors[i], colors[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(bx, by, br, blob)
        }
        // Brilho de vidro no alto
        shine.shader = LinearGradient(cx, cy - r, cx, cy + r * 0.2f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.82f, shine)
        canvas.restore()

        if (state == State.LISTENING || state == State.THINKING) {
            ring.strokeWidth = radius * 0.08f
            ring.shader = SweepGradient(cx, cy, intArrayOf(Ui.PINK, Ui.PURPLE, Ui.BLUE, Ui.TEAL, Ui.PINK), null)
            canvas.save()
            canvas.rotate(t * 160f % 360f, cx, cy)
            canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2f, ring)
            canvas.restore()
        }
        if (isShown) postInvalidateOnAnimation()
    }
}

/** Interruptor no estilo iOS (trilho verde, bolinha branca). */
class IosSwitch(context: Context) : View(context) {

    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private var progress = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(Ui.dpf(context, 2.5f), 0f, Ui.dpf(context, 1.5f), 0x40000000)
    }
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // sombra da bolinha
        Ui.pressable(this) { setChecked(!checked, fromUser = true) }
    }

    fun setChecked(value: Boolean, fromUser: Boolean = false, animate: Boolean = true) {
        if (value == checked && fromUser) return
        checked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate) {
            progress = target
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 320
                interpolator = Ui.SPRING_SMOOTH
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (fromUser) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 51), Ui.dp(context, 31))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        track.color = blend(0xFF39393D.toInt(), Ui.GREEN, progress)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val pad = Ui.dpf(context, 2f)
        val d = h - pad * 2
        val x = pad + (w - d - pad * 2) * progress
        canvas.drawCircle(x + d / 2, h / 2, d / 2, thumb)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Controle segmentado (ex.: Esquerda | Direita). */
class Segmented(context: Context, labels: List<String>, selected: Int, private val onSelect: (Int) -> Unit) : FrameLayout(context) {

    private val indicator = View(context)
    private val row = LinearLayout(context)
    private val items = ArrayList<TextView>()
    var selected = selected
        private set

    init {
        background = Ui.rounded(0xFF1C1C1E.toInt(), Ui.dpf(context, 9f))
        val pad = Ui.dp(context, 2)
        setPadding(pad, pad, pad, pad)
        indicator.background = Ui.rounded(0xFF636366.toInt(), Ui.dpf(context, 7f))
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT))
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        labels.forEachIndexed { i, label ->
            val tv = Ui.text(context, 14f, Color.WHITE, value = label, weight = Ui.Weight.SEMIBOLD).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    Ui.haptic(this)
                    select(i)
                    onSelect(i)
                }
            }
            items.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(false) }
    }

    fun select(i: Int) {
        selected = i
        place(true)
    }

    private fun place(animate: Boolean) {
        val inner = width - paddingLeft - paddingRight
        if (inner <= 0 || items.isEmpty()) return
        val w = inner / items.size
        if (indicator.layoutParams.width != w) {
            indicator.layoutParams = LayoutParams(w, LayoutParams.MATCH_PARENT)
        }
        val x = (w * selected).toFloat()
        if (animate) {
            indicator.animate().translationX(x).setDuration(380).setInterpolator(Ui.SPRING_SMOOTH).start()
        } else if (abs(indicator.translationX - x) > 0.5f) {
            indicator.translationX = x
        }
    }
}
