package com.youfree.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Barrinhas animadas: equalizador da música e nível da voz enquanto ouve. */
class WaveView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = 5

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    /** 0..1 — volume da voz; quando 0 as barras só "dançam". */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        paint.color = 0xFF3DDC97.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w / (bars * 2f - 1f)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until bars) {
            val phase = sin(t * 6f + i * 1.3f)
            val dance = if (active) 0.35f + 0.45f * abs(phase) else 0.18f
            val amount = (dance + level * 0.6f * (0.6f + 0.4f * abs(sin(t * 9f + i)))).coerceIn(0.12f, 1f)
            val bh = h * amount
            val left = i * gap * 2f
            rect.set(left, (h - bh) / 2f, left + gap, (h + bh) / 2f)
            canvas.drawRoundRect(rect, gap / 2f, gap / 2f, paint)
        }
        if (active && isShown) postInvalidateOnAnimation()
    }
}
