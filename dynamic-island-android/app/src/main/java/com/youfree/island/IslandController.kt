package com.youfree.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A ilha dinâmica: uma pílula preta no topo da tela que cresce para mostrar
 * notificações, música, carregamento e o assistente de voz.
 *
 * Estados: IDLE (pílula pequena sobre a câmera), COMPACT (faixa com ícone e texto)
 * e EXPANDED (cartão com detalhes e botões). Tudo roda na thread principal.
 */
class IslandController(private val ctx: Context) {

    private enum class Mode { IDLE, COMPACT, EXPANDED }

    private data class Event(
        val title: String,
        val subtitle: String,
        val icon: Drawable? = null,
        val emoji: String? = null,
        val accent: Int = ACCENT,
        val durationMs: Long = 4500,
        val notification: NotificationInfo? = null,
    )

    private val prefs = Prefs(ctx)
    private val wm = ctx.getSystemService(WindowManager::class.java)!!
    private val main = IslandHub.main
    val assistant = Assistant(ctx, this)

    private var attached = false
    private var mode = Mode.IDLE
    private var transient: Event? = null
    private var expandedEvent: Event? = null
    private var busy = false // ouvindo ou pensando: não recolher sozinho

    // ---------- Views ----------

    private val background = GradientDrawable().apply { setColor(Color.BLACK) }
    private var radius = 0f

    private val root = FrameLayout(ctx)
    private val idleLayer = View(ctx)

    private val compact = LinearLayout(ctx)
    private val compactIcon = ImageView(ctx)
    private val compactEmoji = text(16f, Color.WHITE)
    private val compactTitle = text(13f, Color.WHITE)
    private val compactWave = WaveView(ctx)
    private val compactDot = View(ctx)

    private val expanded = LinearLayout(ctx)
    private val expIcon = ImageView(ctx)
    private val expEmoji = text(20f, Color.WHITE)
    private val expTitle = text(15f, Color.WHITE, bold = true)
    private val expWave = WaveView(ctx)
    private val expClock = text(13f, MUTED)
    private val expBody = text(15f, 0xFFE6E6EA.toInt())
    private val expOpen = text(13f, ACCENT, bold = true)
    private val mediaRow = LinearLayout(ctx)
    private val mediaArt = ImageView(ctx)
    private val mediaTitle = text(14f, Color.WHITE, bold = true)
    private val mediaArtist = text(12f, MUTED)
    private val mediaPlay = iconButton("⏯") { playPause() }

    private val params = WindowManager.LayoutParams(
        dp(prefs.idleWidthDp),
        dp(prefs.idleHeightDp),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(prefs.offsetYDp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Ilha"
    }

    private var sizeAnimator: ValueAnimator? = null
    private val endTransient = Runnable { transient = null; if (mode != Mode.EXPANDED) render() }
    private val autoCollapse = Runnable { if (!busy && mode == Mode.EXPANDED) collapse() }
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            if (mode == Mode.EXPANDED) main.postDelayed(this, 15_000)
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

    // ---------- Carregador ----------

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pct = batteryPercent()
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    showEvent(Event("Carregando", "$pct%", emoji = "⚡", accent = GREEN))
                Intent.ACTION_POWER_DISCONNECTED ->
                    showEvent(Event("Carregador desconectado", "$pct%", emoji = "🔌", accent = MUTED))
            }
        }
    }

    init {
        buildViews()
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    fun attach() {
        if (attached) return
        wm.addView(root, params)
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
        refreshMedia()
        onConfigurationChanged(ctx.resources.configuration)
        // Pequena animação de "olá" ao ligar.
        showEvent(Event(prefs.assistantName, "Pronta! Segure para falar", emoji = "✨", durationMs = 2500))
    }

    fun detach() {
        if (!attached) return
        attached = false
        main.removeCallbacks(endTransient)
        main.removeCallbacks(autoCollapse)
        main.removeCallbacks(clockTick)
        sizeAnimator?.cancel()
        try {
            ctx.unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
        }
        if (sessionsListenerAdded) sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionsListenerAdded = false
        media?.unregisterCallback(mediaCallback)
        media = null
        assistant.shutdown()
        try {
            wm.removeView(root)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun onConfigurationChanged(config: Configuration) {
        // Em vídeos/jogos na horizontal a ilha some para não atrapalhar.
        root.visibility = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
        if (attached) render()
    }

    /** Reaplica tamanho e posição depois de mudar as configurações. */
    fun applyPrefs() {
        params.y = dp(prefs.offsetYDp)
        if (attached) render()
    }

    // =====================================================================
    // Entradas: notificações, eventos, assistente
    // =====================================================================

    fun showNotification(info: NotificationInfo) {
        if (!prefs.showNotifications) return
        val line = if (info.text.isNotBlank()) "${info.title}: ${info.text}" else info.title
        showEvent(
            Event(
                title = info.appName,
                subtitle = line,
                icon = info.icon,
                accent = ACCENT,
                notification = info,
            ),
        )
    }

    /** Usado pelo botão "Testar" do app. */
    fun showDemo() {
        showEvent(Event("Ilha Assistente", "Funcionando! Toque para abrir, segure para falar", emoji = "👋"))
    }

    private fun showEvent(event: Event) {
        transient = event
        main.removeCallbacks(endTransient)
        main.postDelayed(endTransient, event.durationMs)
        if (mode == Mode.EXPANDED) {
            if (!busy && expandedEvent?.notification != null && event.notification != null) {
                openExpanded(event)
            }
            return
        }
        render()
    }

    fun startVoice() {
        busy = true
        assistant.stopSpeaking()
        openExpanded(Event(prefs.assistantName, "Ouvindo…", emoji = "🎙️"), listening = true)
        launchVoiceActivity(typing = false)
    }

    fun startTyping() {
        assistant.stopSpeaking()
        launchVoiceActivity(typing = true)
    }

    private fun launchVoiceActivity(typing: Boolean) {
        val intent = Intent(ctx, VoiceActivity::class.java)
            .putExtra(VoiceActivity.EXTRA_TYPING, typing)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ctx.startActivity(intent)
    }

    fun onListening() {
        busy = true
        setWave(true, PURPLE)
    }

    fun onVoiceLevel(rmsDb: Float) {
        // rmsDb vai de ~-2 (silêncio) até ~10 (voz alta).
        expWave.level = ((rmsDb + 2f) / 12f)
    }

    fun onPartial(text: String) {
        if (text.isNotBlank()) setBody("“$text”")
    }

    fun onUserSaid(text: String) {
        busy = true
        if (mode != Mode.EXPANDED) openExpanded(Event(prefs.assistantName, "", emoji = "💭"))
        setBody("“$text”\n\nPensando…")
        setWave(true, ACCENT)
        assistant.handle(text) { reply ->
            busy = false
            setBody(reply)
            setWave(false, ACCENT)
            if (prefs.speakReplies) assistant.speak(reply)
            scheduleCollapse((reply.length * 70L).coerceIn(7_000L, 25_000L))
        }
    }

    fun onVoiceError(message: String) {
        busy = false
        setWave(false, ACCENT)
        if (mode != Mode.EXPANDED) openExpanded(Event(prefs.assistantName, message, emoji = "🎙️")) else setBody(message)
        scheduleCollapse(4_000)
    }

    fun onVoiceCancelled() {
        busy = false
        setWave(false, ACCENT)
        collapse()
    }

    fun onSpeaking(speaking: Boolean) {
        if (mode == Mode.EXPANDED && !busy) setWave(speaking, ACCENT)
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
            // Sem acesso às notificações ainda: sem controle de música.
        }
    }

    private fun pickMedia(list: List<MediaController>?) {
        val chosen = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (chosen?.sessionToken != media?.sessionToken) {
            media?.unregisterCallback(mediaCallback)
            media = chosen
            chosen?.registerCallback(mediaCallback, main)
        }
        onMediaChanged()
    }

    private fun onMediaChanged() {
        if (!attached) return
        if (mode == Mode.EXPANDED) {
            fillMediaRow()
            resizeExpanded()
        } else {
            render()
        }
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

    // =====================================================================
    // Desenho e animação
    // =====================================================================

    private fun render() {
        if (!attached) return
        when {
            mode == Mode.EXPANDED -> resizeExpanded()
            transient != null -> {
                fillCompact(transient ?: return)
                transitionTo(Mode.COMPACT)
            }
            isMusicPlaying -> {
                fillCompactMedia()
                transitionTo(Mode.COMPACT)
            }
            else -> transitionTo(Mode.IDLE)
        }
    }

    private fun openExpanded(event: Event, listening: Boolean = false) {
        expandedEvent = event
        main.removeCallbacks(autoCollapse)
        fillExpanded(event)
        setWave(listening, PURPLE)
        updateClock()
        main.removeCallbacks(clockTick)
        main.postDelayed(clockTick, 15_000)
        if (mode != Mode.EXPANDED) transitionTo(Mode.EXPANDED) else resizeExpanded()
        if (!busy) scheduleCollapse(9_000)
    }

    private fun collapse() {
        main.removeCallbacks(autoCollapse)
        main.removeCallbacks(clockTick)
        busy = false
        expandedEvent = null
        setWave(false, ACCENT)
        val event = transient
        val target = when {
            event != null -> {
                fillCompact(event)
                Mode.COMPACT
            }
            isMusicPlaying -> {
                fillCompactMedia()
                Mode.COMPACT
            }
            else -> Mode.IDLE
        }
        transitionTo(target)
    }

    private fun scheduleCollapse(delayMs: Long) {
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, delayMs)
    }

    private fun onIslandTap() {
        when (mode) {
            Mode.EXPANDED -> if (!busy) collapse()
            Mode.COMPACT -> {
                val event = transient
                if (event != null) {
                    openExpanded(event.copy(subtitle = event.notification?.let { n -> listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString("\n") } ?: event.subtitle))
                } else {
                    openExpanded(Event(prefs.assistantName, greeting(), emoji = "✨"))
                }
            }
            Mode.IDLE -> openExpanded(Event(prefs.assistantName, greeting(), emoji = "✨"))
        }
    }

    private fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hi = when (hour) {
            in 5..11 -> "Bom dia!"
            in 12..17 -> "Boa tarde!"
            else -> "Boa noite!"
        }
        return "$hi Toque em Falar ou segure a ilha para conversar comigo."
    }

    private fun transitionTo(target: Mode) {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val (w, h, r) = when (target) {
            Mode.IDLE -> {
                val ih = dp(prefs.idleHeightDp)
                Triple(dp(prefs.idleWidthDp), ih, ih / 2f)
            }
            Mode.COMPACT -> {
                val ch = dp(prefs.idleHeightDp + 8)
                Triple(min(screenW - dp(24), dp(340)), ch, ch / 2f)
            }
            Mode.EXPANDED -> {
                val ew = expandedWidth()
                Triple(ew, measureExpanded(ew), dp(30).toFloat())
            }
        }
        val layer = layerFor(target)
        layer.layoutParams = FrameLayout.LayoutParams(w, h, layerGravity(target))

        if (target != mode) {
            mode = target
            for (other in listOf(idleLayer, compact, expanded)) {
                if (other === layer) continue
                other.animate().cancel()
                other.animate().setStartDelay(0).alpha(0f).setDuration(90).withEndAction {
                    if (layerFor(mode) !== other) other.visibility = View.INVISIBLE
                }.start()
            }
            layer.visibility = View.VISIBLE
            layer.animate().cancel()
            layer.alpha = 0f
            layer.animate().setStartDelay(if (target == Mode.IDLE) 0 else 120).alpha(1f).setDuration(200).start()
        }
        animateSize(w, h, r)
    }

    private fun resizeExpanded() {
        if (mode != Mode.EXPANDED) return
        val ew = expandedWidth()
        val eh = measureExpanded(ew)
        expanded.layoutParams = FrameLayout.LayoutParams(ew, eh, layerGravity(Mode.EXPANDED))
        animateSize(ew, eh, dp(30).toFloat())
    }

    private fun expandedWidth(): Int {
        val screenW = ctx.resources.displayMetrics.widthPixels
        return min(screenW - dp(16), dp(400))
    }

    private fun measureExpanded(width: Int): Int {
        expanded.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.6f).roundToInt()
        return expanded.measuredHeight.coerceAtMost(maxH)
    }

    /** O cartão expandido "desce" a partir do topo; as outras camadas crescem do centro. */
    private fun layerGravity(m: Mode): Int =
        if (m == Mode.EXPANDED) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER

    private fun layerFor(m: Mode): View = when (m) {
        Mode.IDLE -> idleLayer
        Mode.COMPACT -> compact
        Mode.EXPANDED -> expanded
    }

    private fun animateSize(w: Int, h: Int, r: Float) {
        sizeAnimator?.cancel()
        val sw = params.width
        val sh = params.height
        val sr = radius
        if (sw == w && sh == h && sr == r) {
            if (attached) wm.updateViewLayout(root, params)
            return
        }
        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.width = (sw + (w - sw) * t).roundToInt().coerceAtLeast(1)
                params.height = (sh + (h - sh) * t).roundToInt().coerceAtLeast(1)
                setRadius(sr + (r - sr) * t.coerceAtMost(1f))
                if (attached) wm.updateViewLayout(root, params)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sizeAnimator = null
                }
            })
            start()
        }
    }

    private fun setRadius(r: Float) {
        radius = r
        background.cornerRadius = r
        root.invalidateOutline()
    }

    // =====================================================================
    // Conteúdo
    // =====================================================================

    private fun fillCompact(event: Event) {
        setIcon(compactIcon, compactEmoji, event.icon, event.emoji)
        compactTitle.text = if (event.subtitle.isBlank()) event.title else event.subtitle
        compactWave.visibility = View.GONE
        compactDot.visibility = View.VISIBLE
        (compactDot.background as GradientDrawable).setColor(event.accent)
    }

    private fun fillCompactMedia() {
        setIcon(compactIcon, compactEmoji, mediaArtwork(), "🎵")
        compactTitle.text = nowPlaying ?: "Tocando"
        compactDot.visibility = View.GONE
        compactWave.visibility = View.VISIBLE
        compactWave.color = GREEN
        compactWave.active = true
    }

    private fun fillExpanded(event: Event) {
        setIcon(expIcon, expEmoji, event.icon, event.emoji)
        expTitle.text = event.title
        setBody(event.subtitle, resize = false)
        val intent = event.notification?.contentIntent
        expOpen.visibility = if (intent != null) View.VISIBLE else View.GONE
        expOpen.setOnClickListener { openNotification(intent) }
        fillMediaRow()
    }

    private fun fillMediaRow() {
        val c = media
        val meta = c?.metadata
        if (c == null || meta == null) {
            mediaRow.visibility = View.GONE
            return
        }
        mediaRow.visibility = View.VISIBLE
        val art = mediaArtwork()
        if (art != null) mediaArt.setImageDrawable(art) else mediaArt.setImageDrawable(appIcon(c.packageName))
        mediaTitle.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música"
        mediaArtist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        mediaPlay.text = if (isMusicPlaying) "⏸" else "▶"
    }

    private fun setBody(text: String, resize: Boolean = true) {
        expBody.text = text
        expBody.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        if (resize) resizeExpanded()
    }

    private fun setWave(on: Boolean, color: Int) {
        expWave.color = color
        expWave.active = on
        expWave.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (!on) expWave.level = 0f
    }

    private fun updateClock() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        expClock.text = "$time · ${batteryPercent()}%"
    }

    private fun openNotification(intent: PendingIntent?) {
        if (intent == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                intent.send(ctx, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
        } catch (_: PendingIntent.CanceledException) {
        }
        collapse()
    }

    private fun mediaArtwork(): Drawable? {
        val meta = media?.metadata ?: return null
        val bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return media?.packageName?.let { appIcon(it) }
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun appIcon(pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun setIcon(image: ImageView, emojiView: TextView, icon: Drawable?, emoji: String?) {
        if (icon != null) {
            image.setImageDrawable(icon)
            image.visibility = View.VISIBLE
            emojiView.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            emojiView.text = emoji ?: "✨"
            emojiView.visibility = View.VISIBLE
        }
    }

    fun batteryPercent(): Int =
        ctx.getSystemService(BatteryManager::class.java)!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun isCharging(): Boolean = ctx.getSystemService(BatteryManager::class.java)!!.isCharging

    // =====================================================================
    // Montagem das views
    // =====================================================================

    private fun buildViews() {
        setRadius(dp(prefs.idleHeightDp) / 2f)
        root.background = background
        root.outlineProvider = ViewOutlineProvider.BACKGROUND
        root.clipToOutline = true
        root.isClickable = true
        root.setOnClickListener { onIslandTap() }
        root.setOnLongClickListener {
            startVoice()
            true
        }
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                if (mode == Mode.EXPANDED && !busy) collapse()
                true
            } else {
                if (e.action == MotionEvent.ACTION_DOWN && mode == Mode.EXPANDED && !busy) scheduleCollapse(9_000)
                false
            }
        }

        // ----- compacta -----
        compact.orientation = LinearLayout.HORIZONTAL
        compact.gravity = Gravity.CENTER_VERTICAL
        compact.setPadding(dp(8), 0, dp(14), 0)
        roundClip(compactIcon, dp(12).toFloat())
        compactIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        compact.addView(compactIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        compactEmoji.gravity = Gravity.CENTER
        compact.addView(compactEmoji, LinearLayout.LayoutParams(dp(24), dp(24)))
        compactTitle.isSingleLine = true
        compactTitle.ellipsize = TextUtils.TruncateAt.END
        compact.addView(compactTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        compact.addView(compactWave, LinearLayout.LayoutParams(dp(22), dp(16)))
        compactDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
        }
        compact.addView(compactDot, LinearLayout.LayoutParams(dp(8), dp(8)))

        // ----- expandida -----
        expanded.orientation = LinearLayout.VERTICAL
        expanded.setPadding(dp(18), dp(14), dp(18), dp(16))

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        roundClip(expIcon, dp(8).toFloat())
        expIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        header.addView(expIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        expEmoji.gravity = Gravity.CENTER
        header.addView(expEmoji, LinearLayout.LayoutParams(dp(30), dp(30)))
        expTitle.isSingleLine = true
        expTitle.ellipsize = TextUtils.TruncateAt.END
        header.addView(expTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        header.addView(expWave, LinearLayout.LayoutParams(dp(30), dp(18)).apply { marginEnd = dp(10) })
        header.addView(expClock)
        expanded.addView(header)

        expBody.maxLines = 9
        expBody.ellipsize = TextUtils.TruncateAt.END
        expBody.setLineSpacing(dp(2).toFloat(), 1f)
        expanded.addView(expBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        expOpen.text = "Abrir ›"
        expOpen.setPadding(0, dp(8), dp(16), dp(2))
        expanded.addView(expOpen)

        // música
        mediaRow.orientation = LinearLayout.HORIZONTAL
        mediaRow.gravity = Gravity.CENTER_VERTICAL
        mediaRow.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xFF15151C.toInt())
        }
        mediaRow.setPadding(dp(8), dp(8), dp(8), dp(8))
        roundClip(mediaArt, dp(10).toFloat())
        mediaArt.scaleType = ImageView.ScaleType.CENTER_CROP
        mediaRow.addView(mediaArt, LinearLayout.LayoutParams(dp(42), dp(42)))
        val mediaTexts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mediaTitle.isSingleLine = true
        mediaTitle.ellipsize = TextUtils.TruncateAt.END
        mediaArtist.isSingleLine = true
        mediaArtist.ellipsize = TextUtils.TruncateAt.END
        mediaTexts.addView(mediaTitle)
        mediaTexts.addView(mediaArtist)
        mediaRow.addView(mediaTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        mediaRow.addView(iconButton("⏮") { previousTrack() })
        mediaRow.addView(mediaPlay)
        mediaRow.addView(iconButton("⏭") { nextTrack() })
        expanded.addView(mediaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // ações
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(pill("🎙️ Falar", ACCENT) { startVoice() }, weighted())
        actions.addView(pill("⌨️ Digitar") { startTyping() }, weighted())
        actions.addView(pill("🔔") { readNotifications() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        actions.addView(pill("✕") { busy = false; assistant.stopSpeaking(); collapse() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        expanded.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        for (layer in listOf(idleLayer, compact, expanded)) {
            root.addView(layer, FrameLayout.LayoutParams(dp(prefs.idleWidthDp), dp(prefs.idleHeightDp), Gravity.CENTER))
        }
        compact.visibility = View.INVISIBLE
        expanded.visibility = View.INVISIBLE
        setWave(false, ACCENT)
    }

    private fun readNotifications() {
        onUserSaid("ler notificações")
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }

    private fun pill(label: String, color: Int = 0xFF23232D.toInt(), onClick: () -> Unit) = text(14f, Color.WHITE, bold = true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color)
        }
        setOnClickListener {
            scheduleCollapse(9_000)
            onClick()
        }
    }

    private fun iconButton(label: String, onClick: () -> Unit) = text(20f, Color.WHITE).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener {
            scheduleCollapse(9_000)
            onClick()
            main.postDelayed({ if (mode == Mode.EXPANDED) fillMediaRow() }, 300)
        }
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun roundClip(view: View, r: Float) {
        view.background = GradientDrawable().apply {
            cornerRadius = r
            setColor(0xFF23232D.toInt())
        }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        val ACCENT = 0xFF7C5CFF.toInt()
        val PURPLE = 0xFFB18CFF.toInt()
        val GREEN = 0xFF3DDC97.toInt()
        val MUTED = 0xFF9A9AA5.toInt()
    }
}
