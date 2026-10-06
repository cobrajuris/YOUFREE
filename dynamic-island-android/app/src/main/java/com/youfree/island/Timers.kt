package com.youfree.island

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Timers que vivem na ilha (como as "Live Activities" do iPhone): vários ao mesmo tempo,
 * com +1/+5 min, pausar e parar. Quando um acaba, toca o alarme até a pessoa parar.
 */
object Timers {

    class Timer(val id: Int, var label: String, var totalMs: Long, var endAt: Long) {
        /** Se pausado, quanto falta (ms); null = rodando. */
        var pausedLeft: Long? = null

        fun leftMs(): Long = pausedLeft ?: (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val paused: Boolean get() = pausedLeft != null
        fun progress(): Float = if (totalMs <= 0) 0f else (1f - leftMs().toFloat() / totalMs).coerceIn(0f, 1f)
    }

    val list = ArrayList<Timer>()
    var ringing: Timer? = null
        private set

    /** Chamado sempre que algo muda (e a cada meio segundo enquanto há timer rodando). */
    var listener: (() -> Unit)? = null

    private var appContext: Context? = null
    private var nextId = 1
    private var ringtone: Ringtone? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val done = list.firstOrNull { !it.paused && it.endAt <= now }
            if (done != null && ringing == null) ring(done)
            listener?.invoke()
            if (list.any { !it.paused } || ringing != null) IslandHub.main.postDelayed(this, 500)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val active: Boolean get() = list.isNotEmpty() || ringing != null

    /** O timer que aparece na ilha: o que toca, ou o que acaba primeiro. */
    fun primary(): Timer? = ringing ?: list.filter { !it.paused }.minByOrNull { it.endAt } ?: list.firstOrNull()

    fun start(seconds: Long, label: String): Timer {
        val ms = seconds * 1000
        val t = Timer(nextId++, label, ms, SystemClock.elapsedRealtime() + ms)
        list.add(t)
        kick()
        return t
    }

    fun add(t: Timer, seconds: Long) {
        val ms = seconds * 1000
        val left = t.pausedLeft
        if (left != null) t.pausedLeft = left + ms else t.endAt += ms
        t.totalMs += ms
        kick()
    }

    fun togglePause(t: Timer) {
        val left = t.pausedLeft
        if (left != null) {
            t.endAt = SystemClock.elapsedRealtime() + left
            t.pausedLeft = null
        } else {
            t.pausedLeft = t.leftMs()
        }
        kick()
    }

    fun stop(t: Timer) {
        list.remove(t)
        if (ringing === t) stopRinging()
        kick()
    }

    fun stopRinging() {
        val r = ringing ?: return
        ringing = null
        list.remove(r)
        ringtone?.stop()
        ringtone = null
        vibrator()?.cancel()
        kick()
    }

    private fun ring(t: Timer) {
        ringing = t
        val ctx = appContext ?: return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (_: Exception) {
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400, 300, 400), 0))
        // Para sozinho depois de 1 minuto.
        IslandHub.main.postDelayed({ if (ringing === t) stopRinging() }, 60_000)
    }

    private fun vibrator(): Vibrator? = appContext?.getSystemService(Vibrator::class.java)

    private fun kick() {
        IslandHub.main.removeCallbacks(tick)
        IslandHub.main.post(tick)
    }

    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }
}
