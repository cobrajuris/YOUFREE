package com.youfree.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Barrinhas de sinal do celular (0 a 4). */
class SignalView(context: Context) : View(context) {
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
    private val rect = RectF()

    /** -1 = sem informação (mostra tudo apagado). */
    var level = -1
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bars = 4
        val gap = w * 0.12f
        val bw = (w - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val bh = h * (0.35f + 0.65f * i / (bars - 1))
            val left = i * (bw + gap)
            rect.set(left, h - bh, left + bw, h)
            canvas.drawRoundRect(rect, bw / 3, bw / 3, if (i < level) on else off)
        }
    }
}

/** Bateria desenhada: contorno, nível e cor (verde carregando, vermelha abaixo de 20%). */
class BatteryView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAAFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
    private val rect = RectF()

    var percent = 100
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }
    var charging = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = h * 0.1f
        stroke.strokeWidth = sw
        val capW = w * 0.08f
        val bodyR = w - capW
        rect.set(sw / 2, sw / 2, bodyR - sw / 2, h - sw / 2)
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, stroke)
        rect.set(bodyR, h * 0.32f, w, h * 0.68f)
        canvas.drawRoundRect(rect, capW / 2, capW / 2, cap)
        fill.color = when {
            charging -> Ui.GREEN
            percent <= 20 -> Ui.RED
            else -> 0xFFFFFFFF.toInt()
        }
        val inset = sw * 1.6f
        val innerW = (bodyR - inset * 2) * percent / 100f
        rect.set(inset, inset, inset + innerW, h - inset)
        canvas.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill)
    }
}
