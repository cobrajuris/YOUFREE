package com.youfree.island

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A Ilha Dinâmica: uma pílula preta flutuando em volta da câmera frontal, como no iPhone.
 *
 *  - Repouso: só a pílula em volta da câmera.
 *  - Compacta (atividade ao vivo): conteúdo dos DOIS LADOS da câmera, nunca em cima dela
 *    (timer, música, próximo compromisso, carregando).
 *  - Expandida: ao tocar, cresce num cartão arredondado. A primeira linha fica na altura da
 *    câmera, com coisas à esquerda e à direita dela; o resto desce embaixo.
 *
 * Toque = expandir · Segurar = falar com a assistente · Puxar para baixo = expandir ·
 * Puxar para cima / tocar fora = recolher. Tudo roda na thread principal.
 */
class IslandController(private val ctx: Context, private val windowType: Int) {

    private enum class Kind { NONE, VOICE, TIMER, MUSIC, EVENT, CHARGING, NOTIFICATION, INFO, HOME, CONTROLS, CALENDAR, NOTES }
    private enum class VoiceState { LISTENING, THINKING, ANSWER }

    private val prefs = Prefs(ctx)
    private val wm = ctx.getSystemService(WindowManager::class.java)!!
    private val main = IslandHub.main
    private val ptBR = Locale.forLanguageTag("pt-BR")
    val status = DeviceStatus(ctx)
    val calendar = CalendarRepo(ctx)
    val notes = Notes(ctx)
    val assistant = Assistant(ctx, this)

    /** Sem acessibilidade a barra de status fica por cima: uma faixa invisível embaixo recebe o toque. */
    private val needsCatcher = windowType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private var attached = false
    private var expanded: Kind? = null
    private var shownKey = ""
    private var compactTransient: Kind? = null
    private var notification: NotificationInfo? = null
    private var info: Triple<Int, String, String>? = null // ícone, título, texto
    private var infoColor = Ui.INDIGO

    private var voiceState = VoiceState.LISTENING
    private var voiceHeard = ""
    private var voiceAnswer = ""
    private var voiceLevel = 0f
    private var busy = false
    private var nextEvent: CalEvent? = null
    private val remindedEvents = HashSet<String>()

    /** Atualiza textos que mudam sozinhos (timer, progresso da música, minutos) sem recriar a tela. */
    private var updater: (() -> Unit)? = null

    // ---------- Câmera / medidas ----------
    private fun dp(v: Int) = Ui.dp(ctx, v)
    private var holeCx = 0
    private var holeCy = 0
    private var holeW = 0
    private var holeH = 0

    private fun screenW(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width() else ctx.resources.displayMetrics.widthPixels
    private fun screenH(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height() else ctx.resources.displayMetrics.heightPixels

    private fun pillH() = max(dp(34), holeH + dp(12))
    private fun pillTop() = (holeCy - pillH() / 2).coerceAtLeast(dp(3))
    private fun idleW() = max(dp(104), holeW + dp(56))
    private fun compactW() = min(screenW() - dp(32), dp(246))
    private fun expandedW() = min(screenW() - dp(12), dp(440))
    private fun gap() = holeW + dp(26) // área livre em volta da câmera
    private fun xFor(w: Int): Int {
        val margin = dp(6)
        return (holeCx - w / 2).coerceIn(margin, max(margin, screenW() - w - margin))
    }

    // ---------- Janela e views ----------
    private val windowRoot = FrameLayout(ctx)
    private val island = FrameLayout(ctx)
    private val islandBg = Ui.rounded(Color.BLACK, dp(18).toFloat())
    private var radius = dp(18).toFloat()
    private var islandW = 0
    private var islandH = 0

    private val params = WindowManager.LayoutParams(
        dp(104), dp(34), windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Ilha Dinâmica"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    private var animator: ValueAnimator? = null

    private val autoCollapse = Runnable { if (!busy) collapse() }
    private val endCompactTransient = Runnable {
        compactTransient = null
        render()
    }
    private val ticker = object : Runnable {
        override fun run() {
            updater?.invoke()
            main.postDelayed(this, 1_000)
        }
    }
    private val minuteTick = object : Runnable {
        override fun run() {
            refreshNextEvent()
            main.postDelayed(this, 60_000)
        }
    }

    // ---------- Música ----------
    private val sessionManager = ctx.getSystemService(MediaSessionManager::class.java)!!
    private val listenerComponent = ComponentName(ctx, NotificationWatcher::class.java)
    private var sessionsListenerAdded = false
    private var media: MediaController? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pickMedia(list) }
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onMediaChanged()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMediaChanged()
        override fun onSessionDestroyed() = refreshMedia()
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> showCompactTransient(Kind.CHARGING, 4_500)
                Intent.ACTION_POWER_DISCONNECTED -> if (compactTransient == Kind.CHARGING) {
                    compactTransient = null
                    render()
                }
            }
        }
    }

    init {
        island.background = islandBg
        island.outlineProvider = ViewOutlineProvider.BACKGROUND
        island.clipToOutline = true
        windowRoot.addView(island, FrameLayout.LayoutParams(dp(104), dp(34), Gravity.TOP or Gravity.START))
        setupTouches()
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    fun attach() {
        if (attached) return
        computeCutout()
        params.width = idleW()
        params.height = pillH()
        params.x = xFor(params.width)
        params.y = pillTop()
        islandW = params.width
        islandH = params.height
        island.layoutParams = FrameLayout.LayoutParams(islandW, islandH, Gravity.TOP or Gravity.START)
        radius = islandH / 2f
        islandBg.cornerRadius = radius
        wm.addView(windowRoot, params)
        attached = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(powerReceiver, filter)
        }
        Timers.listener = { onTimersChanged() }
        refreshMedia()
        refreshNextEvent()
        main.post(ticker)
        main.postDelayed(minuteTick, 60_000)
        windowRoot.post {
            computeCutout() // no Android 9/10 a câmera só é conhecida depois de entrar na tela
            render(force = true)
        }
        showInfo(R.drawable.ic_sparkle, Ui.INDIGO, prefs.assistantName, "Pronta. Toque em mim ou diga \"Oi assistente\".")
    }

    fun detach() {
        if (!attached) return
        attached = false
        main.removeCallbacks(autoCollapse)
        main.removeCallbacks(endCompactTransient)
        main.removeCallbacks(ticker)
        main.removeCallbacks(minuteTick)
        animator?.cancel()
        Timers.listener = null
        try {
            ctx.unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
        }
        if (sessionsListenerAdded) sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionsListenerAdded = false
        media?.unregisterCallback(mediaCallback)
        media = null
        assistant.shutdown()
        status.release()
        try {
            wm.removeView(windowRoot)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun onConfigurationChanged(config: Configuration) {
        windowRoot.visibility = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
        computeCutout()
        if (attached) render(force = true)
    }

    fun applyPrefs() {
        computeCutout()
        if (attached) render(force = true)
    }

    // =====================================================================
    // Câmera frontal
    // =====================================================================

    private fun computeCutout() {
        val sw = screenW()
        var hole: Rect? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.currentWindowMetrics.windowInsets.displayCutout
                } else {
                    windowRoot.rootWindowInsets?.displayCutout
                }
                hole = cutout?.boundingRects?.firstOrNull { it.top <= dp(8) && it.height() < screenH() / 6 && it.width() < sw / 2 }
            }
        } catch (_: Exception) {
        }
        if (hole != null && !hole.isEmpty) {
            holeCx = hole.centerX()
            holeCy = hole.centerY()
            holeW = hole.width().coerceAtMost(dp(60))
            holeH = hole.height().coerceAtMost(dp(40))
        } else {
            @Suppress("DiscouragedApi")
            val sbId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            val sb = if (sbId > 0) ctx.resources.getDimensionPixelSize(sbId) else dp(28)
            holeCx = sw / 2
            holeH = dp(22)
            holeW = dp(22)
            holeCy = max(sb / 2, dp(16))
        }
    }

    // =====================================================================
    // Entradas públicas (notificações, assistente, música...)
    // =====================================================================

    fun showNotification(info: NotificationInfo) {
        if (!prefs.showNotifications) return
        if (busy || Timers.ringing != null) return
        notification = info
        expand(Kind.NOTIFICATION, rebuild = true)
    }

    fun showDemo() {
        showInfo(R.drawable.ic_sparkle, Ui.INDIGO, "Ilha Dinâmica", "Tudo certo! Toque para abrir, segure para falar.")
    }

    fun showInfo(iconRes: Int, color: Int, title: String, body: String) {
        if (busy) return
        val e = expanded
        if (e != null && e != Kind.INFO && e != Kind.NOTIFICATION) return
        info = Triple(iconRes, title, body)
        infoColor = color
        expand(Kind.INFO, rebuild = true)
    }

    /** Mostra os timers na ilha (chamado pela assistente ao criar um timer). */
    fun showTimers() {
        expand(Kind.TIMER, rebuild = true)
    }

    fun showNotes() = expand(Kind.NOTES, rebuild = true)

    fun showCalendar() = expand(Kind.CALENDAR, rebuild = true)

    fun startVoice() {
        if (isLocked()) {
            showInfo(R.drawable.ic_lock, Ui.GRAY, "Desbloqueie para falar", "Por segurança, a assistente só ouve com o celular desbloqueado.")
            return
        }
        busy = true
        voiceState = VoiceState.LISTENING
        voiceHeard = ""
        voiceAnswer = ""
        assistant.stopSpeaking()
        IslandHub.pauseWake()
        expand(Kind.VOICE, rebuild = true)
        launchVoiceActivity(typing = false)
    }

    fun startTyping() {
        assistant.stopSpeaking()
        IslandHub.pauseWake()
        launchVoiceActivity(typing = true)
    }

    private fun launchVoiceActivity(typing: Boolean) {
        val intent = Intent(ctx, VoiceActivity::class.java)
            .putExtra(VoiceActivity.EXTRA_TYPING, typing)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            busy = false
            IslandHub.resumeWake()
        }
    }

    fun onListening() {
        busy = true
        voiceState = VoiceState.LISTENING
        if (expanded != Kind.VOICE) expand(Kind.VOICE, rebuild = true)
    }

    fun onVoiceLevel(rmsDb: Float) {
        voiceLevel = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        siriWave?.level = voiceLevel
    }

    fun onPartial(text: String) {
        if (text.isBlank()) return
        voiceHeard = text
        val v = voiceText ?: return
        v.text = "“$text”"
        if (v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            resizeExpanded()
        }
    }

    fun onUserSaid(text: String) {
        busy = true
        voiceHeard = text
        voiceAnswer = ""
        voiceState = VoiceState.THINKING
        expand(Kind.VOICE, rebuild = true)
        assistant.handle(text) { reply ->
            busy = false
            voiceAnswer = reply
            voiceState = VoiceState.ANSWER
            if (expanded == Kind.VOICE) expand(Kind.VOICE, rebuild = true)
            if (prefs.speakReplies) assistant.speak(reply)
            scheduleCollapse((reply.length * 70L).coerceIn(8_000L, 25_000L))
            IslandHub.resumeWake(2_500)
        }
    }

    fun onVoiceError(message: String) {
        busy = false
        voiceAnswer = message
        voiceState = VoiceState.ANSWER
        expand(Kind.VOICE, rebuild = true)
        scheduleCollapse(4_500)
        IslandHub.resumeWake()
    }

    fun onVoiceCancelled() {
        busy = false
        if (expanded == Kind.VOICE) collapse()
        IslandHub.resumeWake()
    }

    fun onSpeaking(speaking: Boolean) {
        if (expanded == Kind.VOICE && !busy) siriWave?.active = speaking
    }

    /** Abre uma tela de outro app ou do sistema e recolhe a ilha. */
    fun open(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (expanded != Kind.VOICE) collapse()
        true
    } catch (_: Exception) {
        false
    }

    // =====================================================================
    // Agenda
    // =====================================================================

    private fun refreshNextEvent() {
        if (!calendar.canRead()) {
            nextEvent = null
            return
        }
        val now = System.currentTimeMillis()
        val e = calendar.between(now, now + 60 * 60_000L, limit = 3).firstOrNull { !it.allDay && it.begin > now }
        val changed = e?.id != nextEvent?.id
        nextEvent = e
        if (e != null && prefs.eventReminders) {
            val minutes = (e.begin - now) / 60_000L
            val key = "${e.id}@${e.begin}"
            if (minutes <= 10 && remindedEvents.add(key) && !busy && expanded == null) {
                expand(Kind.EVENT, rebuild = true)
                scheduleCollapse(9_000)
                if (prefs.speakReplies) assistant.speak("Daqui a ${minutes.coerceAtLeast(1)} minutos: ${e.title}.")
            }
        }
        if (changed && expanded == null) render()
    }

    // =====================================================================
    // Música
    // =====================================================================

    fun refreshMedia() {
        try {
            if (!sessionsListenerAdded) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                sessionsListenerAdded = true
            }
            pickMedia(sessionManager.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
        }
    }

    private fun pickMedia(list: List<MediaController>?) {
        val chosen = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (chosen?.sessionToken != media?.sessionToken) {
            media?.unregisterCallback(mediaCallback)
            media = chosen
            chosen?.registerCallback(mediaCallback, main)
        }
        onMediaChanged()
    }

    private var lastMusicKey = ""

    private fun onMediaChanged() {
        if (!attached) return
        val key = "$isMusicPlaying|${media?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}"
        if (key == lastMusicKey) return
        lastMusicKey = key
        cachedArtColor = null
        if (expanded == Kind.MUSIC) expand(Kind.MUSIC, rebuild = true) else if (expanded == null) render()
    }

    val isMusicPlaying: Boolean
        get() = media?.playbackState?.state == PlaybackState.STATE_PLAYING

    val nowPlaying: String?
        get() {
            val meta = media?.metadata ?: return null
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            return if (artist.isNullOrBlank()) title else "$title, de $artist"
        }

    fun playPause(): Boolean {
        val c = media ?: return false
        if (isMusicPlaying) c.transportControls.pause() else c.transportControls.play()
        return true
    }

    fun pauseMusic(): Boolean = media?.transportControls?.pause() != null
    fun playMusic(): Boolean = media?.transportControls?.play() != null
    fun nextTrack(): Boolean = media?.transportControls?.skipToNext() != null
    fun previousTrack(): Boolean = media?.transportControls?.skipToPrevious() != null

    private fun artBitmap(): Bitmap? {
        val meta = media?.metadata ?: return null
        return meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    private fun artDrawable(): Drawable? {
        artBitmap()?.let { return BitmapDrawable(ctx.resources, it) }
        val pkg = media?.packageName ?: return null
        return try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private var cachedArtColor: Int? = null

    /** Cor viva tirada da capa do álbum (como as ondas coloridas do iPhone). */
    private fun artColor(): Int {
        cachedArtColor?.let { return it }
        val bmp = artBitmap()
        val c = if (bmp == null) {
            Ui.GREEN
        } else {
            try {
                val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
                val hsv = FloatArray(3)
                Color.colorToHSV(px, hsv)
                hsv[1] = max(hsv[1], 0.5f)
                hsv[2] = max(hsv[2], 0.9f)
                Color.HSVToColor(hsv)
            } catch (_: Exception) {
                Ui.GREEN
            }
        }
        cachedArtColor = c
        return c
    }

    // =====================================================================
    // Timers
    // =====================================================================

    private var lastTimerKey = ""

    private fun onTimersChanged() {
        if (!attached) return
        val key = "${Timers.list.size}|${Timers.ringing?.id}|${Timers.list.count { it.paused }}"
        if (Timers.ringing != null && expanded != Kind.TIMER && !busy) {
            lastTimerKey = key
            expand(Kind.TIMER, rebuild = true)
            return
        }
        if (key != lastTimerKey) {
            lastTimerKey = key
            if (expanded == Kind.TIMER) {
                if (!Timers.active) collapse() else expand(Kind.TIMER, rebuild = true)
            } else if (expanded == null) {
                render()
            }
        } else {
            updater?.invoke()
        }
    }

    // =====================================================================
    // Forma da ilha
    // =====================================================================

    private fun liveKind(): Kind = when {
        Timers.active -> Kind.TIMER
        isMusicPlaying -> Kind.MUSIC
        nextEvent != null -> Kind.EVENT
        else -> Kind.NONE
    }

    private fun defaultExpandKind(): Kind = when (liveKind()) {
        Kind.TIMER -> Kind.TIMER
        Kind.MUSIC -> Kind.MUSIC
        Kind.EVENT -> Kind.EVENT
        else -> Kind.HOME
    }

    private fun showCompactTransient(kind: Kind, ms: Long) {
        compactTransient = kind
        main.removeCallbacks(endCompactTransient)
        main.postDelayed(endCompactTransient, ms)
        if (expanded == null) render()
    }

    private fun expand(kind: Kind, rebuild: Boolean = false) {
        if (!attached) return
        if (expanded == kind && !rebuild) {
            touched()
            return
        }
        expanded = kind
        val content = buildExpanded(kind)
        val w = expandedW()
        val h = measure(content, w)
        show(content, "exp:$kind:${SystemClock.uptimeMillis()}", w, h, min(dp(42).toFloat(), h / 2f))
        scheduleCollapse(
            when (kind) {
                Kind.VOICE -> if (busy) 60_000 else 12_000
                Kind.INFO -> 3_800
                Kind.NOTIFICATION -> 6_500
                Kind.TIMER -> if (Timers.ringing != null) 65_000 else 8_000
                else -> 10_000
            },
        )
    }

    fun collapse() {
        if (expanded == null) return
        if (expanded == Kind.TIMER && Timers.ringing != null) return
        expanded = null
        siriWave = null
        voiceText = null
        render(force = true)
    }

    /** Recalcula o que aparece quando a ilha não está expandida. */
    private fun render(force: Boolean = false) {
        if (!attached) return
        expanded?.let {
            if (force) expand(it, rebuild = true)
            return
        }
        val kind = compactTransient ?: liveKind()
        if (kind == Kind.NONE) {
            updater = null
            show(View(ctx), "idle", idleW(), pillH(), pillH() / 2f, force)
            return
        }
        val row = buildCompact(kind)
        show(row, "compact:$kind:${compactKey(kind)}", compactW(), pillH(), pillH() / 2f, force)
    }

    private fun compactKey(kind: Kind): String = when (kind) {
        Kind.MUSIC -> media?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        Kind.EVENT -> nextEvent?.id?.toString().orEmpty()
        Kind.TIMER -> "${Timers.primary()?.id}|${Timers.ringing != null}"
        else -> ""
    }

    private fun resizeExpanded() {
        if (expanded == null || island.childCount == 0) return
        val content = island.getChildAt(island.childCount - 1)
        val w = expandedW()
        val h = measure(content, w)
        content.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
        animateTo(w, h, min(dp(42).toFloat(), h / 2f))
    }

    private fun measure(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.coerceIn(pillH(), (screenH() * 0.75f).roundToInt())
    }

    /** Troca o conteúdo com animação: o antigo some, o novo aparece crescendo e saindo do desfoque. */
    private fun show(content: View, key: String, w: Int, h: Int, r: Float, force: Boolean = false) {
        if (key == shownKey && !force) {
            animateTo(w, h, r)
            return
        }
        shownKey = key
        for (i in 0 until island.childCount) {
            val old = island.getChildAt(i)
            old.animate().cancel()
            old.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(120)
                .withEndAction { island.removeView(old) }.start()
        }
        content.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
        content.pivotX = (holeCx - xFor(w)).toFloat()
        content.pivotY = 0f
        content.alpha = 0f
        content.scaleX = 0.9f
        content.scaleY = 0.9f
        island.addView(content)
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(420).setInterpolator(Ui.SPRING_SMOOTH).start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(18f, 0f).apply {
                duration = 420
                startDelay = 90
                addUpdateListener {
                    val b = it.animatedValue as Float
                    content.setRenderEffect(if (b < 0.5f) null else RenderEffect.createBlurEffect(b, b, Shader.TileMode.CLAMP))
                }
                start()
            }
        }
        animateTo(w, h, r)
    }

    private fun animateTo(w: Int, h: Int, r: Float) {
        animator?.cancel()
        val catcher = if (needsCatcher && expanded == null) dp(22) else 0
        val sx = params.x
        val sw = islandW
        val sh = islandH
        val sr = radius
        val tx = xFor(w)
        params.y = pillTop()
        if (sw == w && sh == h && sx == tx && sr == r && params.height == h + catcher) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = Ui.SPRING_ISLAND
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val tc = t.coerceIn(0f, 1f)
                islandW = (sw + (w - sw) * t).roundToInt().coerceAtLeast(1)
                islandH = (sh + (h - sh) * t).roundToInt().coerceAtLeast(1)
                radius = (sr + (r - sr) * tc).coerceAtMost(islandH / 2f)
                params.x = (sx + (tx - sx) * tc).roundToInt()
                params.width = islandW
                params.height = islandH + catcher
                islandBg.cornerRadius = radius
                island.layoutParams = FrameLayout.LayoutParams(islandW, islandH, Gravity.TOP or Gravity.START)
                island.invalidateOutline()
                if (attached) wm.updateViewLayout(windowRoot, params)
            }
            start()
        }
    }

    private fun scheduleCollapse(ms: Long) {
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, ms)
    }

    private fun touched() {
        if (expanded != null && !busy) {
            scheduleCollapse(
                when (expanded) {
                    Kind.NOTIFICATION -> 8_000
                    Kind.INFO -> 4_000
                    Kind.TIMER -> if (Timers.ringing != null) 65_000 else 10_000
                    else -> 10_000
                },
            )
        }
    }

    private fun isLocked(): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    // =====================================================================
    // Conteúdo compacto (dos dois lados da câmera)
    // =====================================================================

    private fun cameraRow(width: Int, height: Int): CameraRow = CameraRow(ctx).apply {
        cameraX = holeCx - xFor(width)
        gap = gap()
        sidePadding = dp(12)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun buildCompact(kind: Kind): View {
        val w = compactW()
        val row = cameraRow(w, pillH())
        updater = null
        when (kind) {
            Kind.TIMER -> {
                val t = Timers.primary()
                val ringing = Timers.ringing != null
                val lead = Ui.iconTile(ctx, R.drawable.ic_timer, if (ringing) Ui.RED else Ui.ORANGE, 22, 14)
                val trail = Ui.text(ctx, 16f, if (ringing) Ui.RED else Ui.ORANGE, value = t?.let { Timers.format(it.leftMs()) } ?: "", weight = Ui.Weight.DISPLAY_SEMIBOLD).apply {
                    fontFeatureSettings = "tnum"
                }
                row.set(lead, trail)
                updater = { Timers.primary()?.let { trail.text = Timers.format(it.leftMs()) } }
            }
            Kind.MUSIC -> {
                val art = ImageView(ctx).apply {
                    setImageDrawable(artDrawable())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    Ui.clipRound(this, Ui.dpf(ctx, 6f), Ui.SURFACE)
                    layoutParams = ViewGroup.LayoutParams(dp(22), dp(22))
                }
                val wave = WaveView(ctx).apply {
                    color = artColor()
                    active = true
                    layoutParams = ViewGroup.LayoutParams(dp(22), dp(16))
                }
                row.set(art, wave)
            }
            Kind.EVENT -> {
                val e = nextEvent
                val color = e?.color?.takeIf { it != 0 }?.let { it or 0xFF000000.toInt() } ?: Ui.RED
                val lead = Ui.iconTile(ctx, R.drawable.ic_calendar, color, 22, 13)
                val trail = Ui.text(ctx, 14f, color, value = minutesUntil(e), weight = Ui.Weight.SEMIBOLD)
                row.set(lead, trail)
                updater = { trail.text = minutesUntil(nextEvent) }
            }
            Kind.CHARGING -> {
                val lead = Ui.icon(ctx, R.drawable.ic_flash, Ui.GREEN).apply { layoutParams = ViewGroup.LayoutParams(dp(18), dp(18)) }
                val trail = Ui.text(ctx, 15f, Ui.GREEN, value = "${status.batteryPercent()}%", weight = Ui.Weight.DISPLAY_SEMIBOLD)
                row.set(lead, trail)
            }
            else -> row.set(null, null)
        }
        row.layoutParams = FrameLayout.LayoutParams(w, pillH())
        return row
    }

    private fun minutesUntil(e: CalEvent?): String {
        if (e == null) return ""
        val m = ((e.begin - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return if (m < 1) "agora" else "$m min"
    }

    // =====================================================================
    // Conteúdo expandido
    // =====================================================================

    private var siriWave: SiriWaveView? = null
    private var voiceText: TextView? = null

    /** Cartão expandido: linha de cima na altura da câmera + corpo embaixo. */
    private fun expandedBox(leading: View?, trailing: View?, topHeight: Int = pillH()): Pair<LinearLayout, LinearLayout> {
        val w = expandedW()
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
        }
        val row = cameraRow(w, max(topHeight, pillH())).apply { sidePadding = dp(18) }
        row.set(leading, trailing)
        outer.addView(row)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        outer.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return outer to body
    }

    private fun buildExpanded(kind: Kind): View {
        updater = null
        val view = when (kind) {
            Kind.VOICE -> buildVoice()
            Kind.TIMER -> buildTimer()
            Kind.MUSIC -> buildMusic()
            Kind.EVENT -> buildEvent()
            Kind.NOTIFICATION -> buildNotification()
            Kind.INFO -> buildInfo()
            Kind.CONTROLS -> buildControls()
            Kind.CALENDAR -> buildCalendar()
            Kind.NOTES -> buildNotes()
            else -> buildHome()
        }
        ((view as? ViewGroup)?.getChildAt(1) as? ViewGroup)?.let { Ui.stagger(it, 110) }
        return view
    }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun title(text: String, sp: Float = 16f, color: Int = Color.WHITE, weight: Ui.Weight = Ui.Weight.SEMIBOLD) =
        Ui.text(ctx, sp, color, value = text, weight = weight).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(10) })
        }
    }

    private fun capsule(label: String, style: Ui.ButtonStyle = Ui.ButtonStyle.SECONDARY, iconRes: Int = 0, onClick: () -> Unit) =
        Ui.capsule(ctx, label, style, iconRes) {
            touched()
            onClick()
        }

    /** Botão cápsula com cor própria (laranja dos timers, vermelho de parar...). */
    private fun tinted(label: String, color: Int, onClick: () -> Unit): View = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        background = Ui.rounded((color and 0x00FFFFFF) or 0x38000000, Ui.dpf(ctx, 100f))
        addView(Ui.text(ctx, 15f, color, value = label, weight = Ui.Weight.SEMIBOLD))
        Ui.pressable(this) {
            touched()
            onClick()
        }
    }

    private fun sectionLabel(text: String) = Ui.text(ctx, 12f, Ui.SECONDARY, value = text.uppercase(ptBR), weight = Ui.Weight.SEMIBOLD).apply {
        letterSpacing = 0.06f
    }

    // ----- Assistente (estilo Siri) -----

    private fun buildVoice(): View {
        val label = when (voiceState) {
            VoiceState.LISTENING -> "Ouvindo"
            VoiceState.THINKING -> "Pensando"
            VoiceState.ANSWER -> prefs.assistantName
        }
        val lead = Ui.text(ctx, 17f, Color.WHITE, value = label, weight = Ui.Weight.BOLD)
        val trail = Ui.text(ctx, 14f, Ui.TEAL, value = if (voiceState == VoiceState.ANSWER) "" else prefs.assistantName, weight = Ui.Weight.SEMIBOLD)
        val (outer, body) = expandedBox(lead, trail, dp(44))

        val wave = SiriWaveView(ctx).apply {
            active = voiceState != VoiceState.ANSWER
            level = voiceLevel
        }
        siriWave = wave
        body.addView(wave, lp(0, h = dp(if (voiceState == VoiceState.ANSWER) 28 else 56)))

        val heard = Ui.text(ctx, 15f, Ui.SECONDARY, value = if (voiceHeard.isNotBlank()) "“$voiceHeard”" else "", weight = Ui.Weight.MEDIUM).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (voiceHeard.isBlank()) View.GONE else View.VISIBLE
        }
        voiceText = heard
        body.addView(heard, lp(6))

        if (voiceState == VoiceState.ANSWER && voiceAnswer.isNotBlank()) {
            body.addView(Ui.text(ctx, 19f, Color.WHITE, value = voiceAnswer, weight = Ui.Weight.MEDIUM).apply {
                maxLines = 9
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(Ui.dpf(ctx, 3f), 1f)
            }, lp(8))
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.circle(ctx, R.drawable.ic_keyboard, 44, iconDp = 20) { startTyping() })
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(Ui.capsule(ctx, "Falar de novo", Ui.ButtonStyle.PRIMARY, R.drawable.ic_mic) { startVoice() }, LinearLayout.LayoutParams(dp(170), dp(44)))
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(Ui.circle(ctx, R.drawable.ic_close, 44, iconDp = 18) {
                    assistant.stopSpeaking()
                    collapse()
                })
            }
            body.addView(actions, lp(16))
        }
        return outer
    }

    // ----- Timer (estilo Live Activity) -----

    private fun buildTimer(): View {
        val primary = Timers.primary()
        val ringing = Timers.ringing
        val color = if (ringing != null) Ui.RED else Ui.ORANGE
        val lead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconTile(ctx, R.drawable.ic_timer, color, 26, 16))
            addView(Ui.text(ctx, 15f, Color.WHITE, value = primary?.label ?: "Timer", weight = Ui.Weight.SEMIBOLD).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, 0, 0)
            })
        }
        val count = Timers.list.size
        val trail = Ui.text(ctx, 14f, color, value = if (count > 1) "$count timers" else "", weight = Ui.Weight.MEDIUM)
        val (outer, body) = expandedBox(lead, trail)
        if (primary == null) return outer

        if (ringing != null) {
            body.addView(Ui.text(ctx, 34f, Ui.RED, value = "Tempo esgotado", weight = Ui.Weight.DISPLAY), lp(4))
            body.addView(Ui.text(ctx, 15f, Ui.SECONDARY, value = ringing.label), lp(4))
            body.addView(buttonRow(capsule("Parar", Ui.ButtonStyle.PRIMARY) { Timers.stopRinging() }), lp(16))
            return outer
        }

        val mainRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bigTime = Ui.text(ctx, 50f, Ui.ORANGE, value = Timers.format(primary.leftMs()), weight = Ui.Weight.DISPLAY).apply {
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
        }
        mainRow.addView(bigTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ring = FrameLayout(ctx)
        val ringView = RingView(ctx).apply {
            this.color = Ui.ORANGE
            percent = (100 - primary.progress() * 100).roundToInt()
        }
        ring.addView(ringView, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        ring.addView(Ui.icon(ctx, if (primary.paused) R.drawable.ic_play else R.drawable.ic_pause, Ui.ORANGE), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        Ui.pressable(ring) {
            touched()
            Timers.togglePause(primary)
        }
        mainRow.addView(ring, LinearLayout.LayoutParams(dp(62), dp(62)))
        body.addView(mainRow)
        val endsAt = Ui.text(ctx, 13f, Ui.SECONDARY)
        body.addView(endsAt, lp(2))

        body.addView(buttonRow(
            tinted("+1 min", Ui.ORANGE) { Timers.add(primary, 60) },
            tinted("+5 min", Ui.ORANGE) { Timers.add(primary, 300) },
            tinted("Parar", Ui.RED) { Timers.stop(primary) },
        ), lp(16))

        val others = Timers.list.filter { it !== primary }.take(2).map { t ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = Ui.rounded(Ui.SURFACE, Ui.dpf(ctx, 14f))
                addView(title(t.label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val time = Ui.text(ctx, 16f, Ui.ORANGE, value = Timers.format(t.leftMs()), weight = Ui.Weight.DISPLAY_SEMIBOLD).apply { fontFeatureSettings = "tnum" }
            row.addView(time)
            body.addView(row, lp(8))
            t to time
        }
        updater = {
            bigTime.text = Timers.format(primary.leftMs())
            ringView.percent = (100 - primary.progress() * 100).roundToInt()
            endsAt.text = if (primary.paused) "Pausado" else "Acaba às " + SimpleDateFormat("HH:mm", ptBR).format(Date(System.currentTimeMillis() + primary.leftMs()))
            others.forEach { (t, v) -> v.text = Timers.format(t.leftMs()) }
        }
        updater?.invoke()
        return outer
    }

    // ----- Música -----

    private fun buildMusic(): View {
        val meta = media?.metadata
        val art = ImageView(ctx).apply {
            setImageDrawable(artDrawable())
            scaleType = ImageView.ScaleType.CENTER_CROP
            Ui.clipRound(this, Ui.dpf(ctx, 12f), Ui.SURFACE)
            layoutParams = ViewGroup.LayoutParams(dp(50), dp(50))
        }
        val wave = WaveView(ctx).apply {
            color = artColor()
            active = isMusicPlaying
            layoutParams = ViewGroup.LayoutParams(dp(26), dp(20))
        }
        val (outer, body) = expandedBox(art, wave, dp(66))
        if (meta == null) {
            body.addView(Ui.text(ctx, 15f, Ui.SECONDARY, value = "Nada tocando."))
            return outer
        }
        body.addView(title(meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música", 18f))
        body.addView(title(meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", 15f, Ui.SECONDARY, Ui.Weight.REGULAR), lp(3))

        val track = FrameLayout(ctx).apply { background = Ui.rounded(0x3DFFFFFF, Ui.dpf(ctx, 3f)) }
        val fill = View(ctx).apply { background = Ui.rounded(0xE6FFFFFF.toInt(), Ui.dpf(ctx, 3f)) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(track, lp(16, h = dp(5)))
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val elapsed = Ui.text(ctx, 11f, Ui.SECONDARY, weight = Ui.Weight.MEDIUM).apply { fontFeatureSettings = "tnum" }
        val remaining = Ui.text(ctx, 11f, Ui.SECONDARY, weight = Ui.Weight.MEDIUM).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
        }
        times.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(times, lp(6))

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(Ui.circle(ctx, R.drawable.ic_prev, 52, bg = Color.TRANSPARENT, iconDp = 30) {
            touched()
            previousTrack()
        })
        controls.addView(Ui.circle(ctx, if (isMusicPlaying) R.drawable.ic_pause else R.drawable.ic_play, 64, bg = Color.TRANSPARENT, iconDp = 42) {
            touched()
            playPause()
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(28)
            marginEnd = dp(28)
        })
        controls.addView(Ui.circle(ctx, R.drawable.ic_next, 52, bg = Color.TRANSPARENT, iconDp = 30) {
            touched()
            nextTrack()
        })
        body.addView(controls, lp(6))

        updater = {
            val state = media?.playbackState
            val duration = media?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (state == null || duration <= 0L) {
                track.visibility = View.INVISIBLE
            } else {
                var pos = state.position
                if (state.state == PlaybackState.STATE_PLAYING) {
                    pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
                }
                pos = pos.coerceIn(0L, duration)
                track.visibility = View.VISIBLE
                fill.layoutParams = FrameLayout.LayoutParams((track.width * pos.toFloat() / duration).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                elapsed.text = mmss(pos)
                remaining.text = "-" + mmss(duration - pos)
            }
        }
        track.post { updater?.invoke() }
        return outer
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format(ptBR, "%d:%02d", s / 60, s % 60)
    }

    // ----- Próximo compromisso -----

    private fun buildEvent(): View {
        val e = nextEvent ?: return buildHome()
        val color = if (e.color != 0) e.color or 0xFF000000.toInt() else Ui.RED
        val lead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconTile(ctx, R.drawable.ic_calendar, color, 26, 15))
            addView(Ui.text(ctx, 15f, color, value = "Em " + minutesUntil(e), weight = Ui.Weight.SEMIBOLD).apply { setPadding(dp(8), 0, 0, 0) })
        }
        val trail = Ui.text(ctx, 15f, Color.WHITE, value = SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin)), weight = Ui.Weight.DISPLAY_SEMIBOLD)
        val (outer, body) = expandedBox(lead, trail)
        body.addView(Ui.text(ctx, 22f, Color.WHITE, value = e.title, weight = Ui.Weight.DISPLAY_SEMIBOLD).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (e.location.isNotBlank()) body.addView(title(e.location, 14f, Ui.SECONDARY, Ui.Weight.REGULAR), lp(4))
        val track = FrameLayout(ctx).apply { background = Ui.rounded(0x33FFFFFF, Ui.dpf(ctx, 3f)) }
        val fill = View(ctx).apply { background = Ui.rounded(color, Ui.dpf(ctx, 3f)) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(track, lp(14, h = dp(6)))
        updater = {
            val left = (e.begin - System.currentTimeMillis()).coerceAtLeast(0)
            val frac = 1f - (left / (60 * 60_000f)).coerceIn(0f, 1f)
            fill.layoutParams = FrameLayout.LayoutParams((track.width * frac).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        track.post { updater?.invoke() }
        body.addView(buttonRow(
            capsule("Ver evento", Ui.ButtonStyle.PRIMARY) { open(calendar.viewIntent(e)) },
            capsule("Ok") { collapse() },
        ), lp(16))
        return outer
    }

    // ----- Notificação -----

    private fun buildNotification(): View {
        val n = notification ?: return buildHome()
        val locked = isLocked()
        val hide = locked && prefs.lockHideContent
        val icon = Ui.appIcon(ctx, 28).apply {
            setImageDrawable(n.icon)
            layoutParams = ViewGroup.LayoutParams(dp(28), dp(28))
        }
        val lead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon)
            addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = n.appName, weight = Ui.Weight.MEDIUM).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, 0, 0)
            })
        }
        val trail = Ui.text(ctx, 13f, Ui.SECONDARY, value = SimpleDateFormat("HH:mm", ptBR).format(Date(n.time)))
        val (outer, body) = expandedBox(lead, trail)
        body.addView(title(if (hide) "Nova mensagem" else n.title.ifBlank { n.appName }, 17f))
        if (!hide && n.text.isNotBlank()) {
            body.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = n.text).apply {
                maxLines = if (locked) 1 else 3 // bloqueado: só a prévia
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(Ui.dpf(ctx, 2f), 1f)
            }, lp(4))
        }
        val buttons = mutableListOf<View>()
        if (n.contentIntent != null) buttons.add(capsule("Abrir", Ui.ButtonStyle.PRIMARY) { openNotification(n.contentIntent) })
        buttons.add(capsule("Fechar") { collapse() })
        body.addView(buttonRow(*buttons.toTypedArray()), lp(16))
        return outer
    }

    private fun openNotification(intent: PendingIntent?) {
        if (intent == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(ctx, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
        } catch (_: PendingIntent.CanceledException) {
        }
        collapse()
    }

    // ----- Aviso -----

    private fun buildInfo(): View {
        val (iconRes, titleText, bodyText) = info ?: Triple(R.drawable.ic_sparkle, "", "")
        val lead = Ui.iconTile(ctx, iconRes, infoColor, 26, 16)
        val (outer, body) = expandedBox(lead, null)
        body.addView(title(titleText, 17f))
        if (bodyText.isNotBlank()) {
            body.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = bodyText).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, lp(4))
        }
        return outer
    }

    // ----- Início (tocar com nada ao vivo) -----

    private fun buildHome(): View {
        val now = Date()
        val clock = Ui.text(ctx, 16f, Color.WHITE, value = SimpleDateFormat("HH:mm", ptBR).format(now), weight = Ui.Weight.DISPLAY_SEMIBOLD).apply {
            fontFeatureSettings = "tnum"
        }
        val pct = status.batteryPercent()
        val batt = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, 14f, if (status.isCharging()) Ui.GREEN else Color.WHITE, value = "$pct%", weight = Ui.Weight.SEMIBOLD).apply {
                setPadding(0, 0, dp(6), 0)
            })
            addView(BatteryView(ctx).apply {
                percent = pct
                charging = status.isCharging()
            }, LinearLayout.LayoutParams(dp(24), dp(12)))
        }
        val (outer, body) = expandedBox(clock, batt)

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hi = when (hour) {
            in 5..11 -> "Bom dia"
            in 12..17 -> "Boa tarde"
            else -> "Boa noite"
        }
        body.addView(Ui.text(ctx, 26f, Color.WHITE, value = hi, weight = Ui.Weight.DISPLAY))
        body.addView(Ui.text(ctx, 14f, Ui.SECONDARY, value = SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(now).replaceFirstChar { it.uppercase(ptBR) }), lp(3))

        if (calendar.canRead()) {
            val next = calendar.upcoming(days = 7, limit = 1).firstOrNull()
            if (next != null) body.addView(eventRow(next, withDay = next.begin >= CalendarRepo.startOfDay(1)), lp(14))
        }

        if (!isLocked()) {
            val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun tile(v: View) = tiles.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tile(orbTile())
            tile(actionTile(R.drawable.ic_calendar, "Agenda", Ui.RED) { expand(Kind.CALENDAR, rebuild = true) })
            tile(actionTile(R.drawable.ic_note, "Notas", Ui.YELLOW) { expand(Kind.NOTES, rebuild = true) })
            tile(actionTile(R.drawable.ic_tune, "Controles", Ui.BLUE) { expand(Kind.CONTROLS, rebuild = true) })
            body.addView(tiles, lp(18))
        }
        return outer
    }

    private fun actionTile(iconRes: Int, label: String, color: Int, onClick: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val circle = FrameLayout(ctx).apply {
            background = Ui.oval(Ui.ELEVATED)
            addView(Ui.icon(ctx, iconRes, color), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        addView(circle, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(Ui.text(ctx, 11f, Ui.SECONDARY, value = label, weight = Ui.Weight.MEDIUM), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        Ui.pressable(circle) {
            touched()
            onClick()
        }
    }

    private fun orbTile(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val orb = OrbView(ctx)
        addView(orb, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(Ui.text(ctx, 11f, Ui.SECONDARY, value = "Falar", weight = Ui.Weight.MEDIUM), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        Ui.pressable(orb) { startVoice() }
    }

    private fun eventRow(e: CalEvent, withDay: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.rounded(Ui.SURFACE, Ui.dpf(ctx, 14f))
        val color = if (e.color != 0) e.color or 0xFF000000.toInt() else Ui.BLUE
        addView(View(ctx).apply { background = Ui.rounded(color, Ui.dpf(ctx, 2f)) }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(e.title, 15f))
            val sub = if (withDay) SimpleDateFormat("EEEE, d", ptBR).format(Date(e.begin)).replaceFirstChar { it.uppercase(ptBR) } else e.location
            if (sub.isNotBlank()) addView(title(sub, 13f, Ui.SECONDARY, Ui.Weight.REGULAR), lp(3))
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = if (e.allDay) "dia todo" else SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin)), weight = Ui.Weight.MEDIUM).apply {
            fontFeatureSettings = "tnum"
            setPadding(dp(10), 0, 0, 0)
        })
        Ui.pressable(this) {
            touched()
            open(calendar.viewIntent(e))
        }
    }

    // ----- Controles -----

    private fun buildControls(): View {
        val pct = status.batteryPercent()
        val (outer, body) = expandedBox(
            Ui.text(ctx, 16f, Color.WHITE, value = "Controles", weight = Ui.Weight.SEMIBOLD),
            Ui.text(ctx, 14f, if (status.isCharging()) Ui.GREEN else Color.WHITE, value = "$pct%", weight = Ui.Weight.SEMIBOLD),
        )
        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(t: View) = tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tile(ToggleTile(ctx, R.drawable.ic_wifi, "Wi-Fi", Ui.BLUE).apply {
            on = status.isWifi()
            setOnTap { open(status.wifiPanelIntent()) }
        })
        tile(ToggleTile(ctx, R.drawable.ic_bluetooth, "Bluetooth", Ui.BLUE).apply {
            on = status.isBluetoothOn()
            setOnTap { open(status.bluetoothIntent()) }
        })
        val torch = ToggleTile(ctx, R.drawable.ic_flash, "Lanterna", Color.WHITE).apply { on = status.torchOn }
        torch.setOnTap {
            touched()
            if (status.setTorch(!status.torchOn)) torch.on = status.torchOn
        }
        tile(torch)
        val vibrate = ToggleTile(ctx, R.drawable.ic_vibrate, "Vibrar", Ui.ORANGE).apply { on = status.isVibrateMode() }
        vibrate.setOnTap {
            touched()
            if (status.setVibrateMode(!status.isVibrateMode())) vibrate.on = status.isVibrateMode() else open(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        tile(vibrate)
        body.addView(tiles, lp(6))

        val canBright = status.canChangeBrightness()
        body.addView(PillSlider(ctx, R.drawable.ic_brightness).apply {
            value = status.brightnessPercent() / 100f
            enabledLook = canBright
            onTouchActivity = { touched() }
            onChange = { status.setBrightnessPercent((it * 100).roundToInt()) }
            onDisabledTap = { open(status.brightnessPermissionIntent()) }
        }, lp(18, h = dp(50)))
        body.addView(PillSlider(ctx, R.drawable.ic_speaker).apply {
            value = status.volumePercent() / 100f
            onTouchActivity = { touched() }
            onChange = { status.setVolumePercent((it * 100).roundToInt()) }
        }, lp(10, h = dp(50)))
        return outer
    }

    // ----- Agenda -----

    private fun buildCalendar(): View {
        val now = Calendar.getInstance()
        val (outer, body) = expandedBox(
            Ui.text(ctx, 14f, Ui.RED, value = SimpleDateFormat("EEEE", ptBR).format(now.time).uppercase(ptBR), weight = Ui.Weight.BOLD).apply { letterSpacing = 0.05f },
            Ui.text(ctx, 14f, Ui.SECONDARY, value = SimpleDateFormat("d 'de' MMMM", ptBR).format(now.time), weight = Ui.Weight.MEDIUM),
        )
        if (!calendar.canRead()) {
            body.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = "Permita a agenda no app Ilha para eu mostrar e salvar seus compromissos."))
            body.addView(buttonRow(capsule("Permitir", Ui.ButtonStyle.PRIMARY) { open(Intent(ctx, MainActivity::class.java)) }), lp(14))
            return outer
        }
        val weekStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        }
        val busyDays = HashSet<Int>()
        calendar.between(weekStart.timeInMillis, weekStart.timeInMillis + 7 * CalendarRepo.DAY, limit = 60).forEach {
            busyDays.add(Calendar.getInstance().apply { timeInMillis = it.begin }.get(Calendar.DAY_OF_YEAR))
        }
        val letters = listOf("D", "S", "T", "Q", "Q", "S", "S")
        val week = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val day = weekStart.clone() as Calendar
        for (i in 0 until 7) {
            val isToday = day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(Ui.text(ctx, 11f, if (isToday) Color.WHITE else Ui.SECONDARY, value = letters[i], weight = Ui.Weight.SEMIBOLD))
                addView(Ui.text(ctx, 16f, Color.WHITE, value = day.get(Calendar.DAY_OF_MONTH).toString(), weight = if (isToday) Ui.Weight.BOLD else Ui.Weight.MEDIUM).apply {
                    gravity = Gravity.CENTER
                    if (isToday) background = Ui.oval(Ui.RED)
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(6) })
                addView(View(ctx).apply {
                    background = Ui.oval(if (busyDays.contains(day.get(Calendar.DAY_OF_YEAR))) 0xCCFFFFFF.toInt() else Color.TRANSPARENT)
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(5) })
            }
            week.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        body.addView(week)

        val today = calendar.today().take(3)
        body.addView(sectionLabel("Hoje"), lp(14))
        if (today.isEmpty()) body.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = "Nenhum evento. Dia livre!"), lp(8))
        today.forEach { body.addView(eventRow(it, false), lp(6)) }
        val later = calendar.upcoming(days = 14, limit = 12).filter { it.begin >= CalendarRepo.startOfDay(1) }.take(2)
        if (later.isNotEmpty()) {
            body.addView(sectionLabel("Próximos"), lp(14))
            later.forEach { body.addView(eventRow(it, true), lp(6)) }
        }
        body.addView(buttonRow(
            capsule("Novo", iconRes = R.drawable.ic_add) { open(calendar.newEventIntent()) },
            capsule("Salvar por voz", Ui.ButtonStyle.PRIMARY, R.drawable.ic_mic) { startVoice() },
        ), lp(16))
        return outer
    }

    // ----- Notas -----

    private fun buildNotes(): View {
        val all = notes.all()
        val (outer, body) = expandedBox(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.iconTile(ctx, R.drawable.ic_note, Ui.YELLOW, 26, 16))
                addView(Ui.text(ctx, 16f, Color.WHITE, value = "Notas", weight = Ui.Weight.SEMIBOLD).apply { setPadding(dp(8), 0, 0, 0) })
            },
            Ui.text(ctx, 14f, Ui.SECONDARY, value = "${all.size}"),
        )
        if (all.isEmpty()) {
            body.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = "Nenhuma nota. Diga \"anota comprar pão\"."))
        }
        all.take(5).forEach { note ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(6), dp(10))
                background = Ui.rounded(Ui.SURFACE, Ui.dpf(ctx, 14f))
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(ctx, 15f, Color.WHITE, value = note.text, weight = Ui.Weight.MEDIUM).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(Ui.text(ctx, 12f, Ui.SECONDARY, value = SimpleDateFormat("d MMM, HH:mm", ptBR).format(Date(note.time))), lp(3))
                }
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.circle(ctx, R.drawable.ic_close, 34, bg = Color.TRANSPARENT, tint = Ui.SECONDARY, iconDp = 16) {
                    notes.remove(note)
                    expand(Kind.NOTES, rebuild = true)
                })
            }
            body.addView(row, lp(6))
        }
        body.addView(buttonRow(capsule("Ditar nota", Ui.ButtonStyle.PRIMARY, R.drawable.ic_mic) { startVoice() }), lp(14))
        return outer
    }

    // =====================================================================
    // Toques
    // =====================================================================

    private fun setupTouches() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var consumed = false
        var moved = false
        val longPress = Runnable {
            if (expanded == null) {
                consumed = true
                Ui.haptic(windowRoot, strong = true)
                startVoice() // segurar = falar com a assistente
            }
        }
        windowRoot.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    if (expanded != null && !busy) collapse()
                    false
                }
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    consumed = false
                    moved = false
                    touched()
                    if (expanded == null) main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(e.rawX - downX) > slop || abs(dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (!consumed && expanded == null && dy > slop * 2) {
                        Ui.haptic(v)
                        expand(defaultExpandKind(), rebuild = true)
                        consumed = true
                    } else if (!consumed && expanded != null && dy < -slop * 3) {
                        collapse()
                        consumed = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!consumed && !moved && expanded == null) {
                        Ui.haptic(v)
                        expand(defaultExpandKind(), rebuild = true)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    true
                }
                else -> true
            }
        }
    }
}
