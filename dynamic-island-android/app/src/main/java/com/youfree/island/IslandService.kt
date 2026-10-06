package com.youfree.island

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager

/**
 * Coração do app, em primeiro plano:
 *  - mostra a ilha (ou deixa o serviço de acessibilidade mostrar, que tem toque perfeito no topo);
 *  - mantém timers vivos;
 *  - escuta "Oi assistente" (com a tela ligada).
 */
class IslandService : Service() {

    private var controller: IslandController? = null
    private var micForeground = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    IslandHub.pauseWake() // economiza bateria com a tela apagada
                    LockActivity.showIfEnabled(context)
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> startWakeIfEnabled()
            }
        }
    }
    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        IslandHub.service = this
        Timers.init(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this) && !IslandHub.accessibilityHost) {
            stopSelf()
            return
        }
        ensureController()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        startWakeIfEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            // O app foi aberto: dá para ligar o microfone em primeiro plano agora.
            ACTION_REFRESH -> {
                startAsForeground()
                startWakeIfEnabled()
            }
        }
        return START_STICKY
    }

    /** Cria a ilha na janela "sobre outros apps", se a acessibilidade não estiver cuidando dela. */
    fun ensureController() {
        if (IslandHub.accessibilityHost || controller != null || !Settings.canDrawOverlays(this)) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        controller = IslandController(this, type).also {
            it.attach()
            IslandHub.controller = it
        }
    }

    /** A acessibilidade assumiu a ilha: some com a nossa. */
    fun dropController() {
        controller?.detach()
        if (IslandHub.controller === controller) IslandHub.controller = null
        controller = null
    }

    // ---------- "Oi assistente" ----------

    fun startWakeIfEnabled() {
        val prefs = Prefs(this)
        if (!prefs.wakeWord || !WakeWord.isModelReady(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!micForeground) return // Android exige serviço de microfone iniciado com o app aberto
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == false) return
        val audio = getSystemService(AudioManager::class.java)
        if (audio?.mode == AudioManager.MODE_IN_CALL || audio?.mode == AudioManager.MODE_IN_COMMUNICATION) return
        val wake = IslandHub.wake ?: WakeWord(applicationContext) { onWakeWord() }.also { IslandHub.wake = it }
        wake.start()
    }

    private fun onWakeWord() {
        // Toquezinho de vibração: "estou ouvindo".
        try {
            val v = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                v?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
        IslandHub.controller?.startVoice()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        receiverRegistered = false
        IslandHub.wake?.release()
        IslandHub.wake = null
        dropController()
        if (IslandHub.service === this) IslandHub.service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ilha ativa", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso fixo exigido pelo Android para a ilha continuar na tela."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Ilha Dinâmica ativa")
            .setContentText(if (Prefs(this).wakeWord) "Diga \"Oi assistente\" ou toque na ilha" else "Toque na ilha para abrir")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_island), "Desligar", stop).build())
            .build()
        val wantMic = Prefs(this).wakeWord &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            micForeground = false
            if (wantMic) {
                try {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                    micForeground = true
                    return
                } catch (_: Exception) {
                    // Iniciado em segundo plano (ex.: ao ligar o celular): sem microfone até abrir o app.
                }
            }
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (wantMic) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micForeground = true
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
            micForeground = wantMic
        }
    }

    companion object {
        private const val CHANNEL = "island"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.youfree.island.STOP"
        private const val ACTION_REFRESH = "com.youfree.island.REFRESH"

        fun start(context: Context) {
            Prefs(context).enabled = true
            context.startForegroundService(Intent(context, IslandService::class.java))
        }

        /** Chamado com o app aberto: religa o microfone do "Oi assistente" se preciso. */
        fun refresh(context: Context) {
            if (!Prefs(context).enabled) return
            context.startForegroundService(Intent(context, IslandService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            Prefs(context).enabled = false
            context.stopService(Intent(context, IslandService::class.java))
        }

        val isRunning: Boolean
            get() = IslandHub.service != null
    }
}
