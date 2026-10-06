package com.youfree.island

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
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
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A ilha lateral. Fica presa na borda da tela em três formas:
 *  - TAB: uma alcinha discreta na borda (arraste para cima/baixo para mudar de lugar);
 *  - DOCK: a coluna de status (hora, sinal, Wi-Fi, brilho, volume, bateria, agenda...);
 *  - CARD: um cartão que sai da borda (notificação, agenda, controles, música, assistente).
 * Visual inspirado no modo escuro da Apple: preto profundo, fio de luz na borda,
 * tipografia Inter, molas e vibração leve. Tudo roda na thread principal.
 */
class IslandController(private val ctx: Context) {

    private enum class Mode { TAB, DOCK, CARD }
    private enum class Card { NONE, NOTIFICATION, CALENDAR, CONTROLS, MUSIC, ASSISTANT, EVENT_SOON, INFO }

    private val prefs = Prefs(ctx)
    private val wm = ctx.getSystemService(WindowManager::class.java)!!
    private val main = IslandHub.main
    val status = DeviceStatus(ctx)
    val calendar = CalendarRepo(ctx)
    val assistant = Assistant(ctx, this)

    private val ptBR = Locale.forLanguageTag("pt-BR")
    private var attached = false
    private var mode = Mode.TAB
    private var card = Card.NONE
    private var busy = false // ouvindo ou pensando: não esconder sozinha
    private var unread = false
    private var rightSide = prefs.rightSide

    // ---------- Medidas ----------
    private fun dp(v: Int) = Ui.dp(ctx, v)
    private val hidden = dp(30) // pedaço que fica fora da tela, para esconder os cantos externos
    private val tabW = dp(9)
    private val tabH = dp(84)
    private val dockW = dp(64)

    // ---------- Views ----------
    private val background = Ui.rounded(Ui.ISLAND, hidden.toFloat(), Ui.HAIRLINE, Math.max(1, dp(1) / 2))
    private val root = FrameLayout(ctx)
    private val tabLayer = FrameLayout(ctx)
    private val tabIndicator = View(ctx)
    private val dockLayer = LinearLayout(ctx)
    private val cardLayer = FrameLayout(ctx)

    private val dockHour = Ui.text(ctx, 18f, Color.WHITE, weight = Ui.Weight.DISPLAY)
    private val dockMinute = Ui.text(ctx, 18f, Color.WHITE, weight = Ui.Weight.DISPLAY)
    private val dockSignal = SignalView(ctx)
    private val dockWifi = Ui.icon(ctx, R.drawable.ic_wifi)
    private val dockBattery = BatteryView(ctx)
    private val dockBatteryText = Ui.text(ctx, 11f, Ui.SECONDARY, weight = Ui.Weight.SEMIBOLD)
    private val dockWeekday = Ui.text(ctx, 8.5f, Ui.RED, weight = Ui.Weight.BOLD)
    private val dockDay = Ui.text(ctx, 16f, Color.WHITE, weight = Ui.Weight.DISPLAY)
    private val dockFlash = Ui.icon(ctx, R.drawable.ic_flash)
    private val dockMusic = Ui.icon(ctx, R.drawable.ic_music, Ui.GREEN)
    private val dockOrb = OrbView(ctx)
    private var dockMusicRow: View? = null

    // Cartão do assistente (reaproveitado enquanto ele está aberto)
    private var asstBody: TextView? = null
    private var asstStatus: TextView? = null
    private var asstOrb: OrbView? = null

    // Cartão de música (atualiza a barra de progresso)
    private var musicProgress: View? = null
    private var musicElapsed: TextView? = null
    private var musicRemaining: TextView? = null
    private var musicPlayIcon: ImageView? = null

    private val params = WindowManager.LayoutParams(
        hidden + tabW,
        tabH,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "Ilha"
        x = -hidden
    }

    private var sizeAnimator: ValueAnimator? = null
    private val autoHide = Runnable { if (!busy) showTab() }
    private val dockTick = object : Runnable {
        override fun run() {
            if (mode == Mode.DOCK) {
                refreshDock()
                main.postDelayed(this, 3_000)
            }
        }
    }
    private val musicTick = object : Runnable {
        override fun run() {
            if (mode == Mode.CARD && card == Card.MUSIC) {
                updateMusicProgress()
                main.postDelayed(this, 1_000)
            }
        }
    }
    private val reminderTick = object : Runnable {
        override fun run() {
            checkEventReminders()
            main.postDelayed(this, 60_000)
        }
    }
    private val remindedEvents = HashSet<String>()

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
            val pct = status.batteryPercent()
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> showInfo(R.drawable.ic_flash, Ui.GREEN, "Carregando", "Bateria em $pct%")
                Intent.ACTION_POWER_DISCONNECTED -> showInfo(R.drawable.ic_power, Ui.GRAY, "Carregador desconectado", "Bateria em $pct%")
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
        applySide()
        params.y = tabY()
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
        main.postDelayed(reminderTick, 5_000)
        showInfo(R.drawable.ic_sparkle, Ui.INDIGO, prefs.assistantName, "Estou aqui na borda. Toque na alcinha para abrir.")
    }

    fun detach() {
        if (!attached) return
        attached = false
        main.removeCallbacks(autoHide)
        main.removeCallbacks(dockTick)
        main.removeCallbacks(musicTick)
        main.removeCallbacks(reminderTick)
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
        status.release()
        try {
            wm.removeView(root)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun onConfigurationChanged(config: Configuration) {
        // Na horizontal (vídeos, jogos) a ilha some para não atrapalhar.
        root.visibility = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
        if (attached) showTab()
    }

    /** Reaplica lado e posição depois de mudar as configurações. */
    fun applyPrefs() {
        rightSide = prefs.rightSide
        applySide()
        if (attached) {
            params.y = tabY()
            wm.updateViewLayout(root, params)
            showTab()
        }
    }

    // =====================================================================
    // Entradas: notificações, avisos, assistente
    // =====================================================================

    fun showNotification(info: NotificationInfo) {
        if (!prefs.showNotifications) return
        if (mode == Mode.CARD && card != Card.NOTIFICATION && card != Card.INFO) {
            unread = true // a pessoa está usando outro cartão: só marca na alcinha
            updateTabIndicator()
            return
        }
        showCard(Card.NOTIFICATION, buildNotificationCard(info), autoHideMs = 6_500)
    }

    fun showDemo() {
        showInfo(R.drawable.ic_sparkle, Ui.INDIGO, "Ilha Assistente", "Tudo certo! Toque na alcinha da borda para abrir.")
    }

    private fun showInfo(iconRes: Int, color: Int, title: String, body: String) {
        if (mode == Mode.CARD && card != Card.INFO && card != Card.NOTIFICATION) return
        showCard(Card.INFO, buildInfoCard(iconRes, color, title, body), autoHideMs = 3_800)
    }

    fun startVoice() {
        busy = true
        assistant.stopSpeaking()
        showAssistant("", OrbView.State.LISTENING, "Ouvindo…")
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
        asstOrb?.state = OrbView.State.LISTENING
        asstStatus?.text = "Ouvindo…"
    }

    fun onVoiceLevel(rmsDb: Float) {
        asstOrb?.level = (rmsDb + 2f) / 12f
    }

    fun onPartial(text: String) {
        if (text.isNotBlank()) setAssistantBody(text, quote = true)
    }

    fun onUserSaid(text: String) {
        busy = true
        if (card != Card.ASSISTANT || mode != Mode.CARD) {
            showAssistant(text, OrbView.State.THINKING, "Pensando…", quote = true)
        } else {
            setAssistantBody(text, quote = true)
            asstOrb?.state = OrbView.State.THINKING
            asstOrb?.level = 0f
            asstStatus?.text = "Pensando…"
        }
        assistant.handle(text) { reply ->
            busy = false
            if (card == Card.ASSISTANT && mode == Mode.CARD) {
                setAssistantBody(reply, quote = false)
                asstOrb?.state = OrbView.State.IDLE
                asstStatus?.text = ""
            } else {
                showAssistant(reply, OrbView.State.IDLE, "")
            }
            if (prefs.speakReplies) assistant.speak(reply)
            scheduleHide((reply.length * 70L).coerceIn(8_000L, 25_000L))
        }
    }

    fun onVoiceError(message: String) {
        busy = false
        if (card == Card.ASSISTANT && mode == Mode.CARD) {
            setAssistantBody(message, quote = false)
            asstOrb?.state = OrbView.State.IDLE
            asstStatus?.text = ""
        } else {
            showAssistant(message, OrbView.State.IDLE, "")
        }
        scheduleHide(4_500)
    }

    fun onVoiceCancelled() {
        busy = false
        showTab()
    }

    fun onSpeaking(speaking: Boolean) {
        if (card == Card.ASSISTANT && mode == Mode.CARD && !busy) {
            asstOrb?.state = if (speaking) OrbView.State.SPEAKING else OrbView.State.IDLE
        }
    }

    /** Abre uma tela de outro app ou do sistema e recolhe a ilha. */
    fun open(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        showTab()
        true
    } catch (_: Exception) {
        false
    }

    // =====================================================================
    // Agenda: aviso antes do compromisso
    // =====================================================================

    private fun checkEventReminders() {
        if (!attached || !prefs.eventReminders || !calendar.canRead()) return
        val now = System.currentTimeMillis()
        for (e in calendar.between(now, now + 11 * 60_000L, limit = 5)) {
            if (e.allDay || e.begin < now) continue
            val key = "${e.id}@${e.begin}"
            if (!remindedEvents.add(key)) continue
            val minutes = ((e.begin - now) / 60_000L).toInt().coerceAtLeast(1)
            if (mode == Mode.CARD && busy) continue
            showCard(Card.EVENT_SOON, buildEventSoonCard(e, minutes), autoHideMs = 10_000)
            if (prefs.speakReplies) assistant.speak("Daqui a $minutes minutos: ${e.title}.")
            break
        }
        if (remindedEvents.size > 200) remindedEvents.clear()
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
        updateTabIndicator()
        dockMusicRow?.visibility = if (media?.metadata != null) View.VISIBLE else View.GONE
        if (mode == Mode.CARD && card == Card.MUSIC) {
            musicPlayIcon?.setImageResource(if (isMusicPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            updateMusicProgress()
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
    // Formas da ilha
    // =====================================================================

    private fun showTab() {
        main.removeCallbacks(autoHide)
        main.removeCallbacks(dockTick)
        main.removeCallbacks(musicTick)
        busy = false
        card = Card.NONE
        asstBody = null
        asstStatus = null
        asstOrb = null
        updateTabIndicator()
        transitionTo(Mode.TAB, tabW, tabH)
    }

    private fun showDock() {
        unread = false
        card = Card.NONE
        refreshDock()
        val h = measure(dockLayer, dockW)
        transitionTo(Mode.DOCK, dockW, h)
        Ui.stagger(dockLayer, 60)
        main.removeCallbacks(dockTick)
        main.postDelayed(dockTick, 3_000)
        scheduleHide(7_000)
    }

    private fun showCard(kind: Card, content: ViewGroup, autoHideMs: Long) {
        card = kind
        if (kind != Card.ASSISTANT) {
            asstBody = null
            asstStatus = null
            asstOrb = null
        }
        main.removeCallbacks(dockTick)
        main.removeCallbacks(musicTick)
        cardLayer.removeAllViews()
        cardLayer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = cardWidth()
        transitionTo(Mode.CARD, w, measure(cardLayer, w))
        Ui.stagger(content)
        if (!busy) scheduleHide(autoHideMs)
    }

    private fun resizeCard() {
        if (mode != Mode.CARD) return
        val w = cardWidth()
        transitionTo(Mode.CARD, w, measure(cardLayer, w))
    }

    private fun scheduleHide(ms: Long) {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, ms)
    }

    /** Qualquer toque dentro da ilha adia o recolhimento. */
    private fun touched() {
        if (mode != Mode.TAB && !busy) scheduleHide(if (mode == Mode.DOCK) 7_000 else 12_000)
    }

    private fun transitionTo(target: Mode, visibleW: Int, h: Int) {
        val layer = when (target) {
            Mode.TAB -> tabLayer
            Mode.DOCK -> dockLayer
            Mode.CARD -> cardLayer
        }
        layer.layoutParams = layerParams(visibleW, h)
        if (target != mode) {
            mode = target
            for (other in listOf(tabLayer, dockLayer, cardLayer)) {
                if (other === layer) continue
                other.animate().cancel()
                other.animate().setStartDelay(0).alpha(0f).setDuration(110).withEndAction {
                    if (other !== currentLayer()) other.visibility = View.INVISIBLE
                }.start()
            }
            layer.visibility = View.VISIBLE
            layer.alpha = 0f
            // Conteúdo entra deslizando de dentro da borda.
            layer.translationX = Ui.dpf(ctx, 14f) * (if (rightSide) 1 else -1)
        }
        layer.animate().cancel()
        layer.animate().setStartDelay(if (target == Mode.TAB) 0 else 80).alpha(1f).translationX(0f)
            .setDuration(420).setInterpolator(Ui.SPRING_SMOOTH).start()
        animateWindow(hidden + visibleW, h, windowYFor(h))
    }

    private fun currentLayer(): View = when (mode) {
        Mode.TAB -> tabLayer
        Mode.DOCK -> dockLayer
        Mode.CARD -> cardLayer
    }

    /** As camadas ficam presas na borda da tela: o cartão "sai" de dentro da borda. */
    private fun layerParams(w: Int, h: Int) = FrameLayout.LayoutParams(w, h).apply {
        gravity = Gravity.TOP or (if (rightSide) Gravity.END else Gravity.START)
    }

    private fun measure(view: View, width: Int): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.coerceAtMost((screenHeight() * 0.8f).roundToInt())
    }

    private fun animateWindow(w: Int, h: Int, y: Int) {
        sizeAnimator?.cancel()
        val sw = params.width
        val sh = params.height
        val sy = params.y
        if (sw == w && sh == h && sy == y) return
        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = Ui.SPRING_ISLAND
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                params.width = (sw + (w - sw) * t).roundToInt().coerceAtLeast(1)
                params.height = (sh + (h - sh) * t).roundToInt().coerceAtLeast(1)
                params.y = (sy + (y - sy) * t.coerceAtMost(1f)).roundToInt()
                if (attached) wm.updateViewLayout(root, params)
            }
            start()
        }
    }

    // ---------- Posição ----------

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else ctx.resources.displayMetrics.heightPixels

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width()
        else ctx.resources.displayMetrics.widthPixels

    private fun tabY(): Int {
        val top = dp(48)
        val range = (screenHeight() - tabH - dp(96) - top).coerceAtLeast(0)
        return top + (range * prefs.positionY).roundToInt()
    }

    /** Abre centralizado na alcinha, sem sair da tela. */
    private fun windowYFor(h: Int): Int {
        val center = tabY() + tabH / 2
        val minY = dp(36)
        val maxY = (screenHeight() - h - dp(56)).coerceAtLeast(minY)
        return (center - h / 2).coerceIn(minY, maxY)
    }

    private fun cardWidth(): Int = min((screenWidth() * 0.88f).roundToInt(), dp(352))

    private fun applySide() {
        params.gravity = Gravity.TOP or (if (rightSide) Gravity.END else Gravity.START)
        // O pedaço escondido fica do lado da borda; o conteúdo, do lado de dentro.
        root.setPadding(if (rightSide) 0 else hidden, 0, if (rightSide) hidden else 0, 0)
        for (layer in listOf(tabLayer, dockLayer, cardLayer)) {
            val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: continue
            layer.layoutParams = layerParams(lp.width, lp.height)
        }
    }

    private fun updateTabIndicator() {
        val color = when {
            unread -> Ui.BLUE
            isMusicPlaying -> Ui.GREEN
            else -> 0x73FFFFFF
        }
        tabIndicator.background = Ui.rounded(color, Ui.dpf(ctx, 2f))
    }

    // =====================================================================
    // Dock (coluna de status)
    // =====================================================================

    private fun refreshDock() {
        val now = Date()
        dockHour.text = SimpleDateFormat("HH", ptBR).format(now)
        dockMinute.text = SimpleDateFormat("mm", ptBR).format(now)
        dockSignal.level = status.signalLevel()
        dockWifi.alpha = if (status.isWifi()) 1f else 0.28f
        val pct = status.batteryPercent()
        dockBattery.percent = pct
        dockBattery.charging = status.isCharging()
        dockBatteryText.text = "$pct%"
        dockWeekday.text = SimpleDateFormat("EEE", ptBR).format(now).uppercase(ptBR).take(3)
        dockDay.text = SimpleDateFormat("d", ptBR).format(now)
        dockFlash.imageTintList = ColorStateList.valueOf(if (status.torchOn) Ui.YELLOW else Color.WHITE)
        dockMusicRow?.visibility = if (media?.metadata != null) View.VISIBLE else View.GONE
    }

    private fun dockItem(content: View, w: Int, h: Int, rowHeight: Int = 42, onClick: () -> Unit): View =
        FrameLayout(ctx).apply {
            addView(content, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeight))
            Ui.pressable(this) {
                touched()
                onClick()
            }
        }

    private fun buildDock() {
        dockLayer.orientation = LinearLayout.VERTICAL
        dockLayer.gravity = Gravity.CENTER_HORIZONTAL
        dockLayer.setPadding(0, dp(16), 0, dp(14))

        val clock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            for (t in listOf(dockHour, dockMinute)) {
                t.fontFeatureSettings = "tnum"
                t.gravity = Gravity.CENTER
                addView(t)
            }
            (dockMinute.layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
        }
        dockLayer.addView(dockItem(clock, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 52) { showCalendarCard() })
        dockLayer.addView(dockItem(dockSignal, dp(17), dp(13)) { open(status.internetPanelIntent()) })
        dockLayer.addView(dockItem(dockWifi, dp(20), dp(20)) { open(status.wifiPanelIntent()) })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_brightness), dp(21), dp(21)) { showControlsCard() })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_volume), dp(21), dp(21)) { showControlsCard() })

        val batteryBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dockBattery, LinearLayout.LayoutParams(dp(25), dp(12)))
            dockBatteryText.fontFeatureSettings = "tnum"
            addView(dockBatteryText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        dockLayer.addView(dockItem(batteryBox, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 46) { showControlsCard() })

        dockLayer.addView(View(ctx).apply { background = Ui.rounded(0x26FFFFFF, 1f) }, LinearLayout.LayoutParams(dp(22), Math.max(1, dp(1) / 2)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        val calBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.SURFACE, Ui.dpf(ctx, 10f))
            dockWeekday.gravity = Gravity.CENTER
            dockWeekday.letterSpacing = 0.06f
            dockDay.gravity = Gravity.CENTER
            addView(dockWeekday)
            addView(dockDay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        dockLayer.addView(dockItem(calBox, dp(40), dp(40), 50) { showCalendarCard() })
        dockLayer.addView(dockItem(dockFlash, dp(21), dp(21)) {
            if (!status.setTorch(!status.torchOn)) {
                showInfo(R.drawable.ic_flash, Ui.GRAY, "Lanterna", "A câmera está em uso agora.")
            }
            refreshDock()
        })
        dockMusicRow = dockItem(dockMusic, dp(21), dp(21)) { showMusicCard() }.also { dockLayer.addView(it) }
        dockLayer.addView(dockItem(dockOrb, dp(40), dp(40), 54) { startVoice() })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_settings, Ui.GRAY), dp(18), dp(18), 32) {
            open(Intent(ctx, MainActivity::class.java))
        })
    }

    // =====================================================================
    // Cartões
    // =====================================================================

    private fun cardBox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        isClickable = true
        setOnClickListener { touched() }
    }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    /** Linha de título: ícone, título/subtítulo e algo à direita. */
    private fun header(leading: View?, title: String, subtitle: String? = null, trailing: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (leading != null) {
                val w = leading.layoutParams?.width?.takeIf { it > 0 } ?: dp(36)
                val h = leading.layoutParams?.height?.takeIf { it > 0 } ?: w
                addView(leading, LinearLayout.LayoutParams(w, h).apply { marginEnd = dp(12) })
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(ctx, 16f, Color.WHITE, value = title, weight = Ui.Weight.SEMIBOLD).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (!subtitle.isNullOrBlank()) {
                    addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = subtitle).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }, lp(3))
                }
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing)
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

    private fun sectionLabel(text: String) = Ui.text(ctx, 12f, Ui.SECONDARY, value = text.uppercase(ptBR), weight = Ui.Weight.SEMIBOLD).apply {
        letterSpacing = 0.06f
    }

    private fun buildInfoCard(iconRes: Int, color: Int, title: String, body: String): ViewGroup = cardBox().apply {
        addView(header(Ui.iconTile(ctx, iconRes, color, 36, 20), title, body))
    }

    private fun buildNotificationCard(info: NotificationInfo): ViewGroup = cardBox().apply {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val icon = Ui.appIcon(ctx, 40).apply { setImageDrawable(info.icon) }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, 15f, Color.WHITE, value = info.title.ifBlank { info.appName }, weight = Ui.Weight.SEMIBOLD).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, 12f, Ui.SECONDARY, value = relativeTime(info.time)).apply { setPadding(dp(8), 0, 0, 0) })
        }
        texts.addView(top)
        if (info.text.isNotBlank()) {
            texts.addView(Ui.text(ctx, 14f, 0xD9FFFFFF.toInt(), value = info.text).apply {
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(Ui.dpf(ctx, 2f), 1f)
            }, lp(4))
        }
        texts.addView(Ui.text(ctx, 12f, Ui.SECONDARY, value = info.appName), lp(6))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
        val buttons = mutableListOf<View>()
        if (info.contentIntent != null) buttons.add(capsule("Abrir", Ui.ButtonStyle.PRIMARY) { openNotification(info.contentIntent) })
        buttons.add(capsule("Fechar") { showTab() })
        addView(buttonRow(*buttons.toTypedArray()), lp(16))
    }

    private fun relativeTime(ms: Long): String {
        val diff = (System.currentTimeMillis() - ms) / 60_000L
        return when {
            diff < 1 -> "agora"
            diff < 60 -> "há $diff min"
            else -> SimpleDateFormat("HH:mm", ptBR).format(Date(ms))
        }
    }

    private fun buildEventSoonCard(e: CalEvent, minutes: Int): ViewGroup = cardBox().apply {
        val time = SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin))
        val where = if (e.location.isNotBlank()) " · ${e.location}" else ""
        addView(header(Ui.iconTile(ctx, R.drawable.ic_calendar, Ui.RED, 36, 19), e.title, "Em $minutes min · $time$where"))
        addView(buttonRow(
            capsule("Ver evento", Ui.ButtonStyle.PRIMARY) { open(calendar.viewIntent(e)) },
            capsule("Ok") { showTab() },
        ), lp(16))
    }

    // ----- Agenda -----

    private fun showCalendarCard() {
        val box = cardBox()
        val now = Date()
        val dateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(ctx, 13f, Ui.RED, value = SimpleDateFormat("EEEE", ptBR).format(now).uppercase(ptBR), weight = Ui.Weight.BOLD).apply {
                    letterSpacing = 0.04f
                })
                addView(Ui.text(ctx, 44f, Color.WHITE, value = SimpleDateFormat("d", ptBR).format(now), weight = Ui.Weight.DISPLAY), lp(2))
            }
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, 14f, Ui.SECONDARY, value = SimpleDateFormat("MMMM", ptBR).format(now).replaceFirstChar { it.uppercase(ptBR) }, weight = Ui.Weight.MEDIUM).apply {
                setPadding(0, 0, 0, dp(6))
            })
        }
        box.addView(dateRow)

        if (!calendar.canRead()) {
            box.addView(Ui.text(ctx, 14f, 0xD9FFFFFF.toInt(), value = "Permita o acesso à agenda para eu mostrar seus compromissos e avisar antes de cada um.").apply {
                setLineSpacing(Ui.dpf(ctx, 2f), 1f)
            }, lp(12))
            box.addView(buttonRow(capsule("Permitir agenda", Ui.ButtonStyle.PRIMARY) { open(Intent(ctx, MainActivity::class.java)) }), lp(16))
            showCard(Card.CALENDAR, box, 12_000)
            return
        }

        val todayEvents = calendar.today()
        box.addView(sectionLabel("Hoje"), lp(14))
        if (todayEvents.isEmpty()) {
            box.addView(Ui.text(ctx, 15f, 0xD9FFFFFF.toInt(), value = "Nenhum evento. Dia livre!"), lp(8))
        } else {
            todayEvents.take(4).forEach { box.addView(eventRow(it, withDay = false), lp(6)) }
        }

        val tomorrowStart = CalendarRepo.startOfDay(1)
        val later = calendar.upcoming(days = 7, limit = 12).filter { it.begin >= tomorrowStart }.take(3)
        if (later.isNotEmpty()) {
            box.addView(sectionLabel("Próximos dias"), lp(16))
            later.forEach { box.addView(eventRow(it, withDay = true), lp(6)) }
        }

        box.addView(buttonRow(
            capsule("Novo", iconRes = R.drawable.ic_add) { open(calendar.newEventIntent()) },
            capsule("Marcar por voz", Ui.ButtonStyle.PRIMARY, R.drawable.ic_mic) { startVoice() },
        ), lp(18))
        showCard(Card.CALENDAR, box, 15_000)
    }

    private fun eventRow(e: CalEvent, withDay: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.rounded(Ui.SURFACE, Ui.dpf(ctx, 12f))
        val color = if (e.color != 0) e.color or 0xFF000000.toInt() else Ui.BLUE
        addView(View(ctx).apply { background = Ui.rounded(color, Ui.dpf(ctx, 2f)) }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, 15f, Color.WHITE, value = e.title, weight = Ui.Weight.SEMIBOLD).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            val sub = if (withDay) SimpleDateFormat("EEEE, d", ptBR).format(Date(e.begin)).replaceFirstChar { it.uppercase(ptBR) } else e.location
            if (sub.isNotBlank()) {
                addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = sub).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, lp(3))
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val time = if (e.allDay) "dia todo" else SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin))
        addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = time, weight = Ui.Weight.MEDIUM).apply {
            fontFeatureSettings = "tnum"
            setPadding(dp(10), 0, 0, 0)
        })
        Ui.pressable(this) {
            touched()
            open(calendar.viewIntent(e))
        }
    }

    // ----- Controles (estilo Central de Controle) -----

    private fun showControlsCard() {
        val box = cardBox()

        val tiles = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        fun tile(t: ToggleTile) = tiles.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            if (status.setVibrateMode(!status.isVibrateMode())) {
                vibrate.on = status.isVibrateMode()
            } else {
                open(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
        }
        tile(vibrate)
        box.addView(tiles)

        val canBright = status.canChangeBrightness()
        val bright = PillSlider(ctx, R.drawable.ic_brightness).apply {
            value = status.brightnessPercent() / 100f
            enabledLook = canBright
            onTouchActivity = { touched() }
            onChange = { status.setBrightnessPercent((it * 100).roundToInt()) }
            onDisabledTap = { open(status.brightnessPermissionIntent()) }
        }
        box.addView(bright, lp(20, h = dp(50)))
        if (!canBright) {
            box.addView(Ui.text(ctx, 12f, Ui.SECONDARY, value = "Toque na barra de brilho para permitir que eu ajuste o brilho."), lp(6))
        }
        val volume = PillSlider(ctx, R.drawable.ic_speaker).apply {
            value = status.volumePercent() / 100f
            onTouchActivity = { touched() }
            onChange = { status.setVolumePercent((it * 100).roundToInt()) }
        }
        box.addView(volume, lp(10, h = dp(50)))

        // Rodapé: bateria e rede
        val pct = status.batteryPercent()
        val net = when {
            status.isWifi() -> "Wi-Fi"
            status.isMobileData() -> status.carrierName().ifBlank { "Dados móveis" }
            status.isOnline() -> "Conectado"
            else -> "Sem internet"
        }
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(BatteryView(ctx).apply {
                percent = pct
                charging = status.isCharging()
            }, LinearLayout.LayoutParams(dp(25), dp(12)).apply { marginEnd = dp(8) })
            addView(
                Ui.text(ctx, 13f, Color.WHITE, value = "$pct%" + if (status.isCharging()) " · Carregando" else "", weight = Ui.Weight.MEDIUM),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(SignalView(ctx).apply { level = status.signalLevel() }, LinearLayout.LayoutParams(dp(15), dp(11)).apply { marginEnd = dp(6) })
            addView(Ui.text(ctx, 13f, Ui.SECONDARY, value = net))
        }
        box.addView(footer, lp(18))
        showCard(Card.CONTROLS, box, 12_000)
    }

    // ----- Música -----

    private fun showMusicCard() {
        val c = media
        val meta = c?.metadata
        if (c == null || meta == null) {
            showInfo(R.drawable.ic_music, Ui.PINK, "Música", "Nada tocando agora.")
            return
        }
        val box = cardBox()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = ImageView(ctx).apply {
            setImageDrawable(mediaArtwork())
            scaleType = ImageView.ScaleType.CENTER_CROP
            Ui.clipRound(this, Ui.dpf(ctx, 12f), Ui.SURFACE)
        }
        row.addView(art, LinearLayout.LayoutParams(dp(64), dp(64)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, 16f, Color.WHITE, value = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música", weight = Ui.Weight.SEMIBOLD).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(Ui.text(ctx, 14f, Ui.SECONDARY, value = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "").apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, lp(4))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        row.addView(WaveView(ctx).apply {
            color = Ui.GREEN
            active = isMusicPlaying
        }, LinearLayout.LayoutParams(dp(22), dp(16)))
        box.addView(row)

        // Barra de progresso
        val track = FrameLayout(ctx).apply { background = Ui.rounded(0x3DFFFFFF, Ui.dpf(ctx, 3f)) }
        val fill = View(ctx).apply { background = Ui.rounded(0xE6FFFFFF.toInt(), Ui.dpf(ctx, 3f)) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(track, lp(18, h = dp(5)))
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val elapsed = Ui.text(ctx, 11f, Ui.SECONDARY, weight = Ui.Weight.MEDIUM).apply { fontFeatureSettings = "tnum" }
        val remaining = Ui.text(ctx, 11f, Ui.SECONDARY, weight = Ui.Weight.MEDIUM).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
        }
        times.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(remaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(times, lp(6))
        musicProgress = fill
        musicElapsed = elapsed
        musicRemaining = remaining

        // Controles
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(Ui.circle(ctx, R.drawable.ic_prev, 52, bg = Color.TRANSPARENT, iconDp = 30) {
            touched()
            previousTrack()
        })
        val play = Ui.circle(ctx, if (isMusicPlaying) R.drawable.ic_pause else R.drawable.ic_play, 64, bg = Color.TRANSPARENT, iconDp = 42) {
            touched()
            playPause()
        }
        musicPlayIcon = (play as FrameLayout).getChildAt(0) as ImageView
        controls.addView(play, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(26)
            marginEnd = dp(26)
        })
        controls.addView(Ui.circle(ctx, R.drawable.ic_next, 52, bg = Color.TRANSPARENT, iconDp = 30) {
            touched()
            nextTrack()
        })
        box.addView(controls, lp(8))

        showCard(Card.MUSIC, box, 12_000)
        track.post { updateMusicProgress() }
        main.postDelayed(musicTick, 1_000)
    }

    private fun updateMusicProgress() {
        val fill = musicProgress ?: return
        val track = fill.parent as? View ?: return
        val state = media?.playbackState
        val duration = media?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state == null || duration <= 0L) {
            track.visibility = View.INVISIBLE
            musicElapsed?.text = ""
            musicRemaining?.text = ""
            return
        }
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        track.visibility = View.VISIBLE
        val w = (track.width * position.toFloat() / duration).roundToInt()
        fill.layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
        musicElapsed?.text = mmss(position)
        musicRemaining?.text = "-" + mmss(duration - position)
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format(ptBR, "%d:%02d", s / 60, s % 60)
    }

    private fun mediaArtwork(): Drawable? {
        val meta = media?.metadata
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) return BitmapDrawable(ctx.resources, bmp)
        val pkg = media?.packageName ?: return null
        return try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // ----- Assistente -----

    private fun showAssistant(body: String, orbState: OrbView.State, statusText: String, quote: Boolean = false) {
        val box = cardBox()
        val orb = OrbView(ctx).apply { state = orbState }
        val statusView = Ui.text(ctx, 13f, Ui.SECONDARY, value = statusText, weight = Ui.Weight.MEDIUM)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orb, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(ctx, 16f, Color.WHITE, value = prefs.assistantName, weight = Ui.Weight.SEMIBOLD))
                addView(statusView, lp(3))
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(head)
        val bodyView = Ui.text(ctx, 18f, Color.WHITE, weight = Ui.Weight.MEDIUM).apply {
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(Ui.dpf(ctx, 3f), 1f)
        }
        box.addView(bodyView, lp(14))
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.circle(ctx, R.drawable.ic_keyboard, 44, iconDp = 20) {
                touched()
                startTyping()
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(
                Ui.capsule(ctx, "Falar", Ui.ButtonStyle.PRIMARY, R.drawable.ic_mic) { startVoice() },
                LinearLayout.LayoutParams(dp(132), dp(44)),
            )
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.circle(ctx, R.drawable.ic_close, 44, iconDp = 18) {
                assistant.stopSpeaking()
                showTab()
            })
        }
        box.addView(actions, lp(18))
        setBodyText(bodyView, body, quote)
        showCard(Card.ASSISTANT, box, 12_000)
        asstOrb = orb
        asstStatus = statusView
        asstBody = bodyView
    }

    private fun setBodyText(view: TextView, text: String, quote: Boolean) {
        view.text = if (quote && text.isNotBlank()) "“$text”" else text
        view.setTextColor(if (quote) Ui.SECONDARY else Color.WHITE)
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setAssistantBody(text: String, quote: Boolean) {
        val body = asstBody ?: return
        setBodyText(body, text, quote)
        if (!quote) {
            body.alpha = 0f
            body.translationY = Ui.dpf(ctx, 6f)
            body.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(380).setInterpolator(Ui.SPRING_SMOOTH).start()
        }
        resizeCard()
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
        showTab()
    }

    // =====================================================================
    // Montagem e toques
    // =====================================================================

    private fun buildViews() {
        root.background = background
        root.outlineProvider = ViewOutlineProvider.BACKGROUND
        root.clipToOutline = true

        tabLayer.addView(tabIndicator, FrameLayout.LayoutParams(dp(4), dp(38), Gravity.CENTER))
        buildDock()

        for (layer in listOf(tabLayer, dockLayer, cardLayer)) {
            root.addView(layer, layerParams(tabW, tabH))
        }
        dockLayer.visibility = View.INVISIBLE
        cardLayer.visibility = View.INVISIBLE
        updateTabIndicator()

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startY = 0
        var dragging = false
        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    if (mode != Mode.TAB && !busy) showTab()
                    false
                }
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startY = params.y
                    dragging = false
                    touched()
                    mode == Mode.TAB
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode != Mode.TAB) return@setOnTouchListener false
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    val inward = if (rightSide) -dx else dx
                    if (!dragging && inward > slop * 2 && abs(dx) > abs(dy)) {
                        Ui.haptic(v)
                        showDock() // puxou a alcinha para dentro
                        return@setOnTouchListener true
                    }
                    if (dragging || abs(dy) > slop) {
                        dragging = true
                        sizeAnimator?.cancel()
                        params.y = (startY + dy).roundToInt().coerceIn(dp(48), screenHeight() - tabH - dp(48))
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode != Mode.TAB) return@setOnTouchListener false
                    if (dragging) {
                        val top = dp(48)
                        val range = (screenHeight() - tabH - dp(96) - top).coerceAtLeast(1)
                        prefs.positionY = (params.y - top).toFloat() / range
                    } else {
                        Ui.haptic(v)
                        showDock()
                    }
                    true
                }
                else -> mode == Mode.TAB
            }
        }
    }
}
