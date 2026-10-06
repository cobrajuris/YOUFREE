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
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
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
 * Tudo roda na thread principal.
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
    private val hidden = dp(28) // pedaço que fica fora da tela, para esconder os cantos externos
    private val tabW = dp(10)
    private val tabH = dp(76)
    private val dockW = dp(62)

    // ---------- Views ----------
    private val background = Ui.rounded(Ui.BLACK, hidden.toFloat())
    private val root = FrameLayout(ctx)
    private val tabLayer = FrameLayout(ctx)
    private val tabIndicator = View(ctx)
    private val dockLayer = LinearLayout(ctx)
    private val cardLayer = FrameLayout(ctx)

    private val dockTime = Ui.text(ctx, 17f, Color.WHITE, bold = true)
    private val dockSignal = SignalView(ctx)
    private val dockWifi = Ui.icon(ctx, R.drawable.ic_wifi)
    private val dockBattery = BatteryView(ctx)
    private val dockBatteryText = Ui.text(ctx, 11f, Ui.TEXT, bold = true)
    private val dockWeekday = Ui.text(ctx, 9f, Ui.RED, bold = true)
    private val dockDay = Ui.text(ctx, 16f, Color.WHITE, bold = true)
    private val dockFlash = Ui.icon(ctx, R.drawable.ic_flash)
    private val dockMusic = Ui.icon(ctx, R.drawable.ic_music, Ui.GREEN)
    private var dockMusicRow: View? = null

    // Cartão do assistente (reaproveitado enquanto ele está aberto)
    private var asstBody: TextView? = null
    private var asstWave: WaveView? = null

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
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
                Intent.ACTION_POWER_CONNECTED -> showInfo("⚡ Carregando", "Bateria em $pct%", Ui.GREEN)
                Intent.ACTION_POWER_DISCONNECTED -> showInfo("Carregador desconectado", "Bateria em $pct%", Ui.MUTED)
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
        showInfo(prefs.assistantName, "Estou aqui na borda! Toque na alcinha para abrir.", Ui.ACCENT)
    }

    fun detach() {
        if (!attached) return
        attached = false
        main.removeCallbacks(autoHide)
        main.removeCallbacks(dockTick)
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
            return
        }
        showCard(Card.NOTIFICATION, buildNotificationCard(info), autoHideMs = 6_000)
    }

    fun showDemo() {
        showInfo("Ilha Assistente", "Funcionando! Toque na alcinha da borda para abrir.", Ui.ACCENT)
    }

    private fun showInfo(title: String, body: String, accent: Int) {
        if (mode == Mode.CARD && card != Card.INFO && card != Card.NOTIFICATION) return
        showCard(Card.INFO, buildInfoCard(title, body, accent), autoHideMs = 3_500)
    }

    fun startVoice() {
        busy = true
        assistant.stopSpeaking()
        showAssistant("Ouvindo…", listening = true)
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
        asstWave?.apply {
            color = Ui.PURPLE
            active = true
            visibility = View.VISIBLE
        }
    }

    fun onVoiceLevel(rmsDb: Float) {
        asstWave?.level = (rmsDb + 2f) / 12f
    }

    fun onPartial(text: String) {
        if (text.isNotBlank()) setAssistantBody("“$text”")
    }

    fun onUserSaid(text: String) {
        busy = true
        if (card != Card.ASSISTANT || mode != Mode.CARD) showAssistant("", listening = false)
        setAssistantBody("“$text”\n\nPensando…")
        asstWave?.apply {
            color = Ui.ACCENT
            active = true
            visibility = View.VISIBLE
        }
        assistant.handle(text) { reply ->
            busy = false
            if (card == Card.ASSISTANT && mode == Mode.CARD) {
                setAssistantBody(reply)
                asstWave?.active = false
            } else {
                showAssistant(reply, listening = false)
            }
            if (prefs.speakReplies) assistant.speak(reply)
            scheduleHide((reply.length * 70L).coerceIn(8_000L, 25_000L))
        }
    }

    fun onVoiceError(message: String) {
        busy = false
        if (card == Card.ASSISTANT && mode == Mode.CARD) setAssistantBody(message) else showAssistant(message, false)
        asstWave?.active = false
        scheduleHide(4_000)
    }

    fun onVoiceCancelled() {
        busy = false
        showTab()
    }

    fun onSpeaking(speaking: Boolean) {
        if (card == Card.ASSISTANT && mode == Mode.CARD && !busy) {
            asstWave?.apply {
                color = Ui.ACCENT
                active = speaking
                visibility = if (speaking) View.VISIBLE else View.INVISIBLE
            }
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
        if (mode == Mode.CARD && card == Card.MUSIC) showMusicCard()
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
        busy = false
        card = Card.NONE
        asstBody = null
        asstWave = null
        updateTabIndicator()
        transitionTo(Mode.TAB, tabW, tabH)
    }

    private fun showDock() {
        unread = false
        card = Card.NONE
        refreshDock()
        val h = measure(dockLayer, dockW)
        transitionTo(Mode.DOCK, dockW, h)
        main.removeCallbacks(dockTick)
        main.postDelayed(dockTick, 3_000)
        scheduleHide(7_000)
    }

    private fun showCard(kind: Card, content: View, autoHideMs: Long) {
        card = kind
        if (kind != Card.ASSISTANT) {
            asstBody = null
            asstWave = null
        }
        main.removeCallbacks(dockTick)
        cardLayer.removeAllViews()
        cardLayer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = cardWidth()
        transitionTo(Mode.CARD, w, measure(cardLayer, w))
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
                other.animate().setStartDelay(0).alpha(0f).setDuration(90).withEndAction {
                    if (other !== currentLayer()) other.visibility = View.INVISIBLE
                }.start()
            }
            layer.visibility = View.VISIBLE
            layer.alpha = 0f
        }
        layer.animate().cancel()
        layer.animate().setStartDelay(if (target == Mode.TAB) 0 else 110).alpha(1f).setDuration(180).start()
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
            duration = 380
            interpolator = OvershootInterpolator(0.7f)
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

    private fun cardWidth(): Int = min((screenWidth() * 0.86f).roundToInt(), dp(340))

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
        tabIndicator.background = Ui.rounded(
            when {
                unread -> Ui.ACCENT
                isMusicPlaying -> Ui.GREEN
                else -> 0x88FFFFFF.toInt()
            },
            dp(2).toFloat(),
        )
    }

    // =====================================================================
    // Dock (coluna de status)
    // =====================================================================

    private fun refreshDock() {
        val now = Date()
        dockTime.text = SimpleDateFormat("HH\nmm", ptBR).format(now)
        dockSignal.level = status.signalLevel()
        dockWifi.alpha = if (status.isWifi()) 1f else 0.3f
        val pct = status.batteryPercent()
        dockBattery.percent = pct
        dockBattery.charging = status.isCharging()
        dockBatteryText.text = "$pct"
        dockWeekday.text = SimpleDateFormat("EEE", ptBR).format(now).uppercase(ptBR).take(3)
        dockDay.text = SimpleDateFormat("d", ptBR).format(now)
        dockFlash.imageTintList = android.content.res.ColorStateList.valueOf(if (status.torchOn) Ui.YELLOW else Color.WHITE)
        dockMusicRow?.visibility = if (media?.metadata != null) View.VISIBLE else View.GONE
    }

    private fun dockItem(content: View, w: Int, h: Int, rowHeight: Int = 40, onClick: () -> Unit): View =
        FrameLayout(ctx).apply {
            addView(content, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
            setOnClickListener {
                touched()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeight))
        }

    private fun buildDock() {
        dockLayer.orientation = LinearLayout.VERTICAL
        dockLayer.gravity = Gravity.CENTER_HORIZONTAL
        dockLayer.setPadding(0, dp(14), 0, dp(14))

        dockTime.gravity = Gravity.CENTER
        dockTime.setLineSpacing(0f, 0.95f)
        dockLayer.addView(dockItem(dockTime, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 50) { showCalendarCard() })
        dockLayer.addView(dockItem(dockSignal, dp(18), dp(14)) { open(status.internetPanelIntent()) })
        dockLayer.addView(dockItem(dockWifi, dp(21), dp(21)) { open(status.wifiPanelIntent()) })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_brightness), dp(22), dp(22)) { showControlsCard() })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_volume), dp(22), dp(22)) { showControlsCard() })

        val batteryBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dockBattery, LinearLayout.LayoutParams(dp(26), dp(13)))
            addView(dockBatteryText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        dockLayer.addView(dockItem(batteryBox, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 44) { showControlsCard() })

        dockLayer.addView(View(ctx).apply { background = Ui.rounded(0x33FFFFFF, 1f) }, LinearLayout.LayoutParams(dp(26), dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        val calBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.CHIP, dp(9).toFloat())
            dockWeekday.gravity = Gravity.CENTER
            dockDay.gravity = Gravity.CENTER
            addView(dockWeekday)
            addView(dockDay)
        }
        dockLayer.addView(dockItem(calBox, dp(38), dp(38), 46) { showCalendarCard() })
        dockLayer.addView(dockItem(dockFlash, dp(22), dp(22)) {
            if (!status.setTorch(!status.torchOn)) showInfo("Lanterna", "Não consegui ligar a lanterna agora.", Ui.MUTED)
            refreshDock()
        })
        dockMusicRow = dockItem(dockMusic, dp(22), dp(22)) { showMusicCard() }.also { dockLayer.addView(it) }

        val mic = FrameLayout(ctx).apply {
            background = Ui.oval(Ui.ACCENT)
            addView(Ui.icon(ctx, R.drawable.ic_mic), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        dockLayer.addView(dockItem(mic, dp(40), dp(40), 50) { startVoice() })
        dockLayer.addView(dockItem(Ui.icon(ctx, R.drawable.ic_more, Ui.MUTED), dp(20), dp(20), 30) {
            open(Intent(ctx, MainActivity::class.java))
        })
    }

    // =====================================================================
    // Cartões
    // =====================================================================

    private fun cardBox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isClickable = true
        setOnClickListener { touched() }
    }

    private fun header(iconView: View?, title: String, trailing: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (iconView != null) addView(iconView, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            addView(Ui.text(ctx, 15f, Color.WHITE, bold = true, value = title).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing)
        }

    private fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun pill(label: String, color: Int = Ui.CHIP, onClick: () -> Unit) = Ui.pill(ctx, label, color) {
        touched()
        onClick()
    }

    private fun spaced(view: View, top: Int): View {
        view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
        return view
    }

    private fun buildInfoCard(title: String, body: String, accent: Int): View = cardBox().apply {
        addView(header(View(ctx).apply { background = Ui.oval(accent) }.let { dot ->
            FrameLayout(ctx).apply { addView(dot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER)) }
        }, title))
        addView(spaced(Ui.text(ctx, 14f, Ui.TEXT, value = body), 8))
    }

    private fun buildNotificationCard(info: NotificationInfo): View = cardBox().apply {
        val iconView = ImageView(ctx).apply {
            setImageDrawable(info.icon)
            Ui.clipRound(this, dp(7).toFloat())
        }
        val time = Ui.text(ctx, 12f, Ui.MUTED, value = SimpleDateFormat("HH:mm", ptBR).format(Date(info.time)))
        addView(header(iconView, info.appName, time))
        if (info.title.isNotBlank()) {
            addView(spaced(Ui.text(ctx, 15f, Color.WHITE, bold = true, value = info.title).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, 10))
        }
        if (info.text.isNotBlank()) {
            addView(spaced(Ui.text(ctx, 14f, Ui.TEXT, value = info.text).apply {
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
            }, 4))
        }
        val buttons = mutableListOf<View>()
        if (info.contentIntent != null) buttons.add(pill("Abrir", Ui.ACCENT) { openNotification(info.contentIntent) })
        buttons.add(pill("Fechar") { showTab() })
        addView(spaced(buttonRow(*buttons.toTypedArray()), 12))
    }

    private fun buildEventSoonCard(e: CalEvent, minutes: Int): View = cardBox().apply {
        addView(header(calendarBadge(e.color), "Daqui a $minutes min"))
        addView(spaced(Ui.text(ctx, 16f, Color.WHITE, bold = true, value = e.title), 10))
        val time = SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin))
        val where = if (e.location.isNotBlank()) " · ${e.location}" else ""
        addView(spaced(Ui.text(ctx, 13f, Ui.MUTED, value = "Às $time$where"), 4))
        addView(spaced(buttonRow(pill("Ver", Ui.ACCENT) { open(calendar.viewIntent(e)) }, pill("Ok") { showTab() }), 12))
    }

    private fun calendarBadge(color: Int): View = FrameLayout(ctx).apply {
        background = Ui.rounded(Ui.CHIP, dp(7).toFloat())
        addView(View(ctx).apply { background = Ui.oval(if (color != 0) color or 0xFF000000.toInt() else Ui.RED) }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
    }

    // ----- Agenda -----

    private fun showCalendarCard() {
        val box = cardBox()
        val today = SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date()).replaceFirstChar { it.uppercase(ptBR) }
        box.addView(header(calendarBadge(Ui.RED), today))

        if (!calendar.canRead()) {
            box.addView(spaced(Ui.text(ctx, 14f, Ui.TEXT, value = "Permita o acesso à agenda para eu mostrar seus compromissos e avisar antes de cada um."), 10))
            box.addView(spaced(buttonRow(pill("Permitir", Ui.ACCENT) { open(Intent(ctx, MainActivity::class.java)) }), 12))
            showCard(Card.CALENDAR, box, 12_000)
            return
        }

        val todayEvents = calendar.today()
        box.addView(spaced(Ui.text(ctx, 12f, Ui.MUTED, bold = true, value = "HOJE"), 14))
        if (todayEvents.isEmpty()) {
            box.addView(spaced(Ui.text(ctx, 14f, Ui.TEXT, value = "Nada marcado. Dia livre!"), 6))
        } else {
            todayEvents.take(5).forEach { box.addView(eventRow(it, withDay = false)) }
        }

        val tomorrowStart = CalendarRepo.startOfDay(1)
        val later = calendar.upcoming(days = 7, limit = 12).filter { it.begin >= tomorrowStart }.take(4)
        if (later.isNotEmpty()) {
            box.addView(spaced(Ui.text(ctx, 12f, Ui.MUTED, bold = true, value = "PRÓXIMOS DIAS"), 14))
            later.forEach { box.addView(eventRow(it, withDay = true)) }
        }

        box.addView(spaced(buttonRow(
            pill("+ Novo") { open(calendar.newEventIntent()) },
            pill("Marcar por voz", Ui.ACCENT) { startVoice() },
        ), 14))
        showCard(Card.CALENDAR, box, 15_000)
    }

    private fun eventRow(e: CalEvent, withDay: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(View(ctx).apply {
            background = Ui.rounded(if (e.color != 0) e.color or 0xFF000000.toInt() else Ui.ACCENT, dp(2).toFloat())
        }, LinearLayout.LayoutParams(dp(4), dp(30)).apply { marginEnd = dp(10) })
        val whenText = buildString {
            if (withDay) append(SimpleDateFormat("EEE d", ptBR).format(Date(e.begin)).replaceFirstChar { it.uppercase(ptBR) }).append(" · ")
            append(if (e.allDay) "Dia todo" else SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin)))
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, 14f, Color.WHITE, bold = true, value = e.title).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(Ui.text(ctx, 12f, Ui.MUTED, value = whenText).apply { setPadding(0, dp(3), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            touched()
            open(calendar.viewIntent(e))
        }
    }

    // ----- Controles -----

    private fun showControlsCard() {
        val box = cardBox()
        val pct = status.batteryPercent()
        val carrier = status.carrierName()
        val net = when {
            status.isWifi() -> "Wi-Fi"
            status.isMobileData() -> "Dados móveis"
            status.isOnline() -> "Conectado"
            else -> "Sem internet"
        }
        val battery = BatteryView(ctx).apply {
            percent = pct
            charging = status.isCharging()
        }
        box.addView(header(
            FrameLayout(ctx).apply { addView(battery, FrameLayout.LayoutParams(dp(24), dp(12), Gravity.CENTER)) },
            "$pct%" + (if (status.isCharging()) " · carregando" else ""),
            Ui.text(ctx, 12f, Ui.MUTED, value = listOf(carrier, net).filter { it.isNotBlank() }.joinToString(" · ")),
        ))

        // Brilho
        val canBright = status.canChangeBrightness()
        box.addView(spaced(sliderRow(R.drawable.ic_brightness, status.brightnessPercent(), enabled = canBright) { v ->
            status.setBrightnessPercent(v)
        }, 14))
        if (!canBright) {
            box.addView(spaced(Ui.text(ctx, 12f, Ui.MUTED, value = "Toque em \"Permitir brilho\" para eu poder mudar o brilho."), 2))
        }
        // Volume
        box.addView(spaced(sliderRow(R.drawable.ic_volume, status.volumePercent(), enabled = true) { v ->
            status.setVolumePercent(v)
        }, 6))

        val torch = pill(if (status.torchOn) "Lanterna ✓" else "Lanterna", if (status.torchOn) 0xFF5A4A12.toInt() else Ui.CHIP) {
            status.setTorch(!status.torchOn)
            showControlsCard()
        }
        val vibrate = pill(if (status.isVibrateMode()) "Vibrar ✓" else "Vibrar", if (status.isVibrateMode()) 0xFF2E2560.toInt() else Ui.CHIP) {
            if (!status.setVibrateMode(!status.isVibrateMode())) {
                open(Intent(Settings.ACTION_SOUND_SETTINGS))
            } else {
                showControlsCard()
            }
        }
        box.addView(spaced(buttonRow(torch, vibrate), 12))
        val second = mutableListOf<View>(
            pill("Wi-Fi") { open(status.wifiPanelIntent()) },
            pill("Bluetooth") { open(status.bluetoothIntent()) },
        )
        if (!canBright) second.add(0, pill("Permitir brilho", Ui.ACCENT) { open(status.brightnessPermissionIntent()) })
        box.addView(spaced(buttonRow(*second.toTypedArray()), 8))
        showCard(Card.CONTROLS, box, 12_000)
    }

    private fun sliderRow(iconRes: Int, value: Int, enabled: Boolean, onChange: (Int) -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.icon(ctx, iconRes, if (enabled) Color.WHITE else Ui.MUTED), LinearLayout.LayoutParams(dp(22), dp(22)))
            val bar = SeekBar(ctx).apply {
                max = 100
                progress = value
                isEnabled = enabled
                progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            touched()
                            onChange(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = touched()
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = touched()
                })
            }
            addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        }

    // ----- Música -----

    private fun showMusicCard() {
        val c = media
        val meta = c?.metadata
        if (c == null || meta == null) {
            showInfo("Música", "Nada tocando agora.", Ui.MUTED)
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
            Ui.clipRound(this, dp(12).toFloat())
        }
        row.addView(art, LinearLayout.LayoutParams(dp(58), dp(58)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, 15f, Color.WHITE, bold = true, value = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Música").apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(Ui.text(ctx, 13f, Ui.MUTED, value = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "").apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        row.addView(WaveView(ctx).apply {
            color = Ui.GREEN
            active = isMusicPlaying
        }, LinearLayout.LayoutParams(dp(24), dp(18)))
        box.addView(row)
        box.addView(spaced(buttonRow(
            pill("⏮") { previousTrack(); refreshMusicSoon() },
            pill(if (isMusicPlaying) "⏸" else "▶", Ui.ACCENT) { playPause(); refreshMusicSoon() },
            pill("⏭") { nextTrack(); refreshMusicSoon() },
        ), 14))
        showCard(Card.MUSIC, box, 10_000)
    }

    private fun refreshMusicSoon() {
        main.postDelayed({ if (mode == Mode.CARD && card == Card.MUSIC) showMusicCard() }, 350)
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

    private fun showAssistant(body: String, listening: Boolean) {
        val box = cardBox()
        val wave = WaveView(ctx).apply {
            color = if (listening) Ui.PURPLE else Ui.ACCENT
            active = listening
            visibility = if (listening) View.VISIBLE else View.INVISIBLE
        }
        val micBadge = FrameLayout(ctx).apply {
            background = Ui.oval(Ui.ACCENT)
            addView(Ui.icon(ctx, R.drawable.ic_mic), FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
        }
        box.addView(header(micBadge, prefs.assistantName, wave.also { it.layoutParams = LinearLayout.LayoutParams(dp(30), dp(18)) }))
        val bodyView = Ui.text(ctx, 15f, Ui.TEXT, value = body).apply {
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = if (body.isBlank()) View.GONE else View.VISIBLE
        }
        box.addView(spaced(bodyView, 10))
        box.addView(spaced(buttonRow(
            pill("Falar", Ui.ACCENT) { startVoice() },
            pill("Digitar") { startTyping() },
            pill("✕") {
                assistant.stopSpeaking()
                showTab()
            },
        ), 14))
        showCard(Card.ASSISTANT, box, 12_000)
        asstBody = bodyView
        asstWave = wave
    }

    private fun setAssistantBody(text: String) {
        val body = asstBody ?: return
        body.text = text
        body.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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

        tabLayer.addView(tabIndicator, FrameLayout.LayoutParams(dp(3), dp(30), Gravity.CENTER))
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
        root.setOnTouchListener { _, e ->
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
                        showDock()
                    }
                    true
                }
                else -> mode == Mode.TAB
            }
        }
    }
}
