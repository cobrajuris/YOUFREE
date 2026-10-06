package com.youfree.island

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tela de bloqueio premium. Aparece POR CIMA do bloqueio do Android (como apps de
 * despertador), sem substituir a segurança: para abrir o celular continua sendo preciso
 * o PIN / digital / rosto do sistema.
 *
 * Mostra relógio grande, data, widgets (bateria e próximo compromisso), a semana com a
 * agenda de hoje e só PRÉVIAS das mensagens. Deslize para cima para desbloquear.
 */
class LockActivity : Activity() {

    private val ptBR = Locale.forLanguageTag("pt-BR")
    private lateinit var prefs: Prefs
    private lateinit var status: DeviceStatus
    private lateinit var calendar: CalendarRepo

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var dateView: TextView
    private lateinit var clockView: TextView
    private lateinit var widgets: LinearLayout
    private lateinit var calendarCard: LinearLayout
    private lateinit var notifications: LinearLayout
    private lateinit var torchButton: View

    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var unlocking = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateClock()
                // Desbloqueou pela digital/rosto direto: sai da frente.
                Intent.ACTION_USER_PRESENT -> fadeOutAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        status = DeviceStatus(this)
        calendar = CalendarRepo(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 15+ já desenha de ponta a ponta; isto cobre o 11 a 14.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        buildUi()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        unlocking = false
        content.translationY = 0f
        content.alpha = 1f
        refresh()
        enterAnimation()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        status.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Voltar = desbloquear, como deslizar.
        unlock()
    }

    // =====================================================================
    // Montagem
    // =====================================================================

    private fun buildUi() {
        root = FrameLayout(this)
        // Película escura em cima e embaixo para o texto ler bem em qualquer papel de parede.
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x8C000000.toInt(), 0x26000000, 0x1A000000, 0x99000000.toInt()),
        )

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Cadeado
        content.addView(Ui.icon(this, R.drawable.ic_lock, 0xE6FFFFFF.toInt()), LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(18) })

        // Data e relógio
        dateView = Ui.text(this, 19f, 0xF2FFFFFF.toInt(), weight = Ui.Weight.SEMIBOLD).apply { gravity = Gravity.CENTER }
        content.addView(dateView, wrap(top = 14))
        clockView = Ui.text(this, 96f, Color.WHITE, weight = Ui.Weight.DISPLAY).apply {
            gravity = Gravity.CENTER
            letterSpacing = -0.035f
            setShadowLayer(dpf(18f), 0f, dpf(2f), 0x40000000)
        }
        content.addView(clockView, wrap(top = -4))

        // Widgets
        widgets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(widgets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74)).apply {
            topMargin = dp(10)
            marginStart = dp(20)
            marginEnd = dp(20)
        })

        // Semana + agenda
        calendarCard = glassCard()
        content.addView(calendarCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        // Mensagens (só prévias)
        notifications = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        content.addView(notifications, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        // Rodapé: lanterna, "deslize para cima", câmera
        val bottom = FrameLayout(this)
        torchButton = glassCircle(R.drawable.ic_flash) {
            status.setTorch(!status.torchOn)
            paintTorch()
        }
        bottom.addView(torchButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
        bottom.addView(glassCircle(R.drawable.ic_camera) { openCamera() }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val chevron = Ui.icon(this@LockActivity, R.drawable.ic_chevron_up, 0xB3FFFFFF.toInt())
            addView(chevron, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(Ui.text(this@LockActivity, 13f, 0xB3FFFFFF.toInt(), value = "Deslize para cima para abrir", weight = Ui.Weight.MEDIUM))
            // Setinha "respirando"
            chevron.animate().translationY(-dpf(5f)).setDuration(900).withEndAction(object : Runnable {
                override fun run() {
                    val up = chevron.translationY < -1f
                    chevron.animate().translationY(if (up) 0f else -dpf(5f)).setDuration(900).withEndAction(this).start()
                }
            }).start()
        }
        bottom.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
            marginStart = dp(28)
            marginEnd = dp(28)
        })

        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottomInset = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            content.setPadding(0, top, 0, bottomInset)
            insets
        }
        setContentView(root)
    }

    // =====================================================================
    // Conteúdo
    // =====================================================================

    private fun refresh() {
        updateClock()
        buildWidgets()
        buildCalendar()
        buildNotifications()
        paintTorch()
    }

    private fun updateClock() {
        val now = Date()
        clockView.text = SimpleDateFormat("HH:mm", ptBR).format(now)
        dateView.text = SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(now).replaceFirstChar { it.uppercase(ptBR) }
    }

    private fun buildWidgets() {
        widgets.removeAllViews()

        // Bateria em anel
        val pct = status.batteryPercent()
        val charging = status.isCharging()
        val battery = FrameLayout(this).apply {
            background = glass(dpf(22f))
            val ring = RingView(this@LockActivity).apply {
                percent = pct
                color = if (charging) Ui.GREEN else if (pct <= 20) Ui.RED else Color.WHITE
            }
            addView(ring, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
            val label = if (charging) Ui.icon(this@LockActivity, R.drawable.ic_flash, Ui.GREEN).also {
                it.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            } else Ui.text(this@LockActivity, 14f, Color.WHITE, value = "$pct", weight = Ui.Weight.DISPLAY_SEMIBOLD).also {
                it.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            addView(label)
        }
        widgets.addView(battery, LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })

        // Próximo compromisso
        val next = if (calendar.canRead()) calendar.upcoming(days = 7, limit = 1).firstOrNull() else null
        val nextTile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(dpf(22f))
            setPadding(dp(14), 0, dp(14), 0)
            val bar = View(this@LockActivity).apply {
                val c = next?.color?.takeIf { it != 0 }?.let { it or 0xFF000000.toInt() } ?: Ui.RED
                background = Ui.rounded(c, dpf(2f))
            }
            addView(bar, LinearLayout.LayoutParams(dp(4), dp(40)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(this@LockActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this@LockActivity, 11f, 0xB3FFFFFF.toInt(), value = (if (next != null) whenLabel(next) else "AGENDA").uppercase(ptBR), weight = Ui.Weight.SEMIBOLD).apply {
                letterSpacing = 0.06f
            })
            texts.addView(Ui.text(this@LockActivity, 16f, Color.WHITE, value = next?.title ?: "Nenhum compromisso", weight = Ui.Weight.SEMIBOLD).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, wrap(top = 4))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        widgets.addView(nextTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun whenLabel(e: CalEvent): String {
        val day = when (e.begin) {
            in CalendarRepo.startOfDay(0) until CalendarRepo.startOfDay(1) -> "Hoje"
            in CalendarRepo.startOfDay(1) until CalendarRepo.startOfDay(2) -> "Amanhã"
            else -> SimpleDateFormat("EEE, d", ptBR).format(Date(e.begin))
        }
        return if (e.allDay) "$day · dia todo" else "$day · ${SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin))}"
    }

    private fun buildCalendar() {
        calendarCard.removeAllViews()
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)

        // Cabeçalho: mês
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@LockActivity, 13f, Ui.RED, value = SimpleDateFormat("MMMM", ptBR).format(cal.time).uppercase(ptBR), weight = Ui.Weight.BOLD).apply {
                letterSpacing = 0.06f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(this@LockActivity, 13f, 0x99FFFFFF.toInt(), value = SimpleDateFormat("yyyy", ptBR).format(cal.time), weight = Ui.Weight.MEDIUM))
        }
        calendarCard.addView(head)

        // Faixa da semana (domingo a sábado) com bolinha nos dias que têm compromisso
        val weekStart = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        }
        val busyDays = HashSet<Int>()
        if (calendar.canRead()) {
            val from = weekStart.timeInMillis
            for (e in calendar.between(from, from + 7 * CalendarRepo.DAY, limit = 60)) {
                val c = Calendar.getInstance().apply { timeInMillis = e.begin }
                busyDays.add(c.get(Calendar.DAY_OF_YEAR))
            }
        }
        val letters = listOf("D", "S", "T", "Q", "Q", "S", "S")
        val week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val day = weekStart.clone() as Calendar
        for (i in 0 until 7) {
            val isToday = day.get(Calendar.DAY_OF_MONTH) == today && day.get(Calendar.MONTH) == cal.get(Calendar.MONTH)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(Ui.text(this@LockActivity, 11f, if (isToday) Color.WHITE else 0x80FFFFFF.toInt(), value = letters[i], weight = Ui.Weight.SEMIBOLD))
                val num = Ui.text(this@LockActivity, 16f, if (isToday) Color.WHITE else 0xE6FFFFFF.toInt(), value = day.get(Calendar.DAY_OF_MONTH).toString(),
                    weight = if (isToday) Ui.Weight.BOLD else Ui.Weight.MEDIUM).apply {
                    gravity = Gravity.CENTER
                    if (isToday) background = Ui.oval(Ui.RED)
                }
                addView(num, LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(6) })
                val dot = View(this@LockActivity).apply {
                    background = Ui.oval(if (busyDays.contains(day.get(Calendar.DAY_OF_YEAR))) 0xCCFFFFFF.toInt() else Color.TRANSPARENT)
                }
                addView(dot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(5) })
            }
            week.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        calendarCard.addView(week, wrap(top = 12, matchWidth = true))

        // Agenda de hoje
        val events = if (calendar.canRead()) calendar.today().take(2) else emptyList()
        calendarCard.addView(View(this).apply { setBackgroundColor(0x26FFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2)).apply {
            topMargin = dp(12)
            bottomMargin = dp(4)
        })
        if (!calendar.canRead()) {
            calendarCard.addView(Ui.text(this, 14f, 0xB3FFFFFF.toInt(), value = "Permita a agenda no app Ilha para ver seus compromissos aqui."), wrap(top = 8, matchWidth = true))
        } else if (events.isEmpty()) {
            calendarCard.addView(Ui.text(this, 14f, 0xB3FFFFFF.toInt(), value = "Nada marcado para hoje."), wrap(top = 8, matchWidth = true))
        } else {
            for (e in events) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    val c = if (e.color != 0) e.color or 0xFF000000.toInt() else Ui.BLUE
                    addView(View(this@LockActivity).apply { background = Ui.rounded(c, dpf(2f)) }, LinearLayout.LayoutParams(dp(3), dp(28)).apply { marginEnd = dp(10) })
                    addView(Ui.text(this@LockActivity, 15f, Color.WHITE, value = e.title, weight = Ui.Weight.SEMIBOLD).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Ui.text(this@LockActivity, 13f, 0xB3FFFFFF.toInt(),
                        value = if (e.allDay) "dia todo" else SimpleDateFormat("HH:mm", ptBR).format(Date(e.begin)),
                        weight = Ui.Weight.MEDIUM).apply { fontFeatureSettings = "tnum" })
                }
                calendarCard.addView(row, wrap(matchWidth = true))
            }
        }
    }

    /** Só prévias: quem mandou e uma linha do texto (ou nem isso, se escolher esconder). */
    private fun buildNotifications() {
        notifications.removeAllViews()
        val since = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        val list = IslandHub.recent.filter { it.time >= since }
        if (list.isEmpty()) return
        val hide = prefs.lockHideContent

        notifications.addView(Ui.text(this, 13f, 0xB3FFFFFF.toInt(), value = "MENSAGENS", weight = Ui.Weight.SEMIBOLD).apply {
            letterSpacing = 0.06f
            setPadding(dp(6), 0, 0, dp(8))
        })
        for (info in list.take(4)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(dpf(22f))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(Ui.appIcon(this, 36).apply { setImageDrawable(info.icon) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.text(this@LockActivity, 15f, Color.WHITE, value = if (hide) info.appName else info.title.ifBlank { info.appName }, weight = Ui.Weight.SEMIBOLD).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(this@LockActivity, 12f, 0x99FFFFFF.toInt(), value = relativeTime(info.time)).apply { setPadding(dp(8), 0, 0, 0) })
            }
            texts.addView(top)
            val preview = when {
                hide -> "Nova notificação"
                info.text.isNotBlank() -> info.text
                else -> info.appName
            }
            texts.addView(Ui.text(this, 14f, 0xCCFFFFFF.toInt(), value = preview).apply {
                isSingleLine = true // só a prévia: uma linha
                ellipsize = TextUtils.TruncateAt.END
            }, wrap(top = 3, matchWidth = true))
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Ui.pressable(card) { unlock { openNotification(info) } }
            notifications.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        // Se não couber tudo, esconde as mais antigas e mostra "+N".
        notifications.post {
            var hidden = 0
            for (i in notifications.childCount - 1 downTo 1) {
                val c = notifications.getChildAt(i)
                if (c.bottom > notifications.height) {
                    c.visibility = View.GONE
                    hidden++
                }
            }
            hidden += (list.size - 4).coerceAtLeast(0)
            if (hidden > 0) {
                notifications.addView(Ui.text(this, 13f, 0xB3FFFFFF.toInt(), value = "+ $hidden mais", weight = Ui.Weight.MEDIUM).apply {
                    gravity = Gravity.CENTER
                }, wrap(matchWidth = true))
            }
            if (notifications.getChildAt(1)?.visibility == View.GONE) notifications.visibility = View.INVISIBLE
        }
    }

    private fun relativeTime(ms: Long): String {
        val diff = (System.currentTimeMillis() - ms) / 60_000L
        return when {
            diff < 1 -> "agora"
            diff < 60 -> "$diff min"
            else -> SimpleDateFormat("HH:mm", ptBR).format(Date(ms))
        }
    }

    private fun paintTorch() {
        torchButton.background = if (status.torchOn) Ui.oval(Color.WHITE) else glassOval()
        ((torchButton as FrameLayout).getChildAt(0) as android.widget.ImageView).imageTintList =
            android.content.res.ColorStateList.valueOf(if (status.torchOn) Color.BLACK else Color.WHITE)
    }

    // =====================================================================
    // Ações
    // =====================================================================

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            unlock { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun openNotification(info: NotificationInfo) {
        val intent = info.contentIntent ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
        } catch (_: Exception) {
        }
    }

    /** Pede o desbloqueio do sistema (PIN/digital/rosto). Depois roda [after] e sai. */
    private fun unlock(after: (() -> Unit)? = null) {
        if (unlocking) return
        unlocking = true
        content.animate().translationY(-dpf(80f)).alpha(0f).setDuration(260).setInterpolator(Ui.SPRING_FAST).start()
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            after?.invoke()
            fadeOutAndFinish()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                after?.invoke()
                fadeOutAndFinish()
            }

            override fun onDismissCancelled() = springBack()
            override fun onDismissError() = springBack()
        })
    }

    private fun springBack() {
        unlocking = false
        content.animate().translationY(0f).alpha(1f).setDuration(520).setInterpolator(Ui.SPRING_BOUNCY).start()
    }

    private fun fadeOutAndFinish() {
        root.animate().alpha(0f).setDuration(200).withEndAction {
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }.start()
    }

    // =====================================================================
    // Gesto de deslizar para cima
    // =====================================================================

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (unlocking) return true
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.rawY - downY
                if (!dragging && dy < -slop * 2) {
                    dragging = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    val t = min(0f, dy)
                    content.translationY = t * 0.9f
                    content.alpha = (1f + t / (root.height * 0.45f)).coerceIn(0f, 1f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    val dy = ev.rawY - downY
                    dragging = false
                    if (dy < -root.height * 0.16f || vy < -1400f) {
                        Ui.haptic(root)
                        unlock()
                    } else {
                        springBack()
                    }
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // =====================================================================
    // Visual
    // =====================================================================

    private fun enterAnimation() {
        clockView.alpha = 0f
        clockView.scaleX = 0.94f
        clockView.scaleY = 0.94f
        clockView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(Ui.SPRING_SMOOTH).start()
        val parts = listOf(dateView, widgets, calendarCard, notifications)
        parts.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dpf(16f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L + i * 60L).setDuration(560).setInterpolator(Ui.SPRING_SMOOTH).start()
        }
    }

    /** "Vidro" escuro: película translúcida com um fio de luz na borda. */
    private fun glass(radius: Float) = Ui.rounded(0x52141418, radius, 0x29FFFFFF, max(1, dp(1) / 2))

    private fun glassOval() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x52141418)
        setStroke(max(1, dp(1) / 2), 0x29FFFFFF)
    }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(dpf(24f))
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun glassCircle(iconRes: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = glassOval()
        addView(Ui.icon(this@LockActivity, iconRes), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        Ui.pressable(this) { onClick() }
    }

    private fun wrap(top: Int = 0, matchWidth: Boolean = false) = LinearLayout.LayoutParams(
        if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun dpf(v: Float) = Ui.dpf(this, v)

    companion object {
        /** Chamado quando a tela apaga: deixa a tela de bloqueio pronta para quando acender. */
        fun showIfEnabled(context: Context) {
            val prefs = Prefs(context)
            if (!prefs.lockScreen) return
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val inCall = audio?.mode == android.media.AudioManager.MODE_IN_CALL ||
                audio?.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            if (inCall) return
            try {
                context.startActivity(
                    Intent(context, LockActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }
}
