package com.youfree.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/**
 * Mantém a ilha na tela. É um serviço em primeiro plano com uma janela
 * "sobre outros apps" — não precisa de acessibilidade.
 */
class IslandService : Service() {

    private var controller: IslandController? = null

    /** Tela apagou: prepara a tela de bloqueio premium para quando acender. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) LockActivity.showIfEnabled(context)
        }
    }
    private var screenReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        controller = IslandController(this).also {
            it.attach()
            IslandHub.controller = it
        }
        // SCREEN_OFF só chega para receivers registrados em código.
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
        controller?.detach()
        if (IslandHub.controller === controller) IslandHub.controller = null
        controller = null
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
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Ilha Assistente ativa")
            .setContentText("Toque para configurar")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_island), "Desligar", stop).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "island"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.youfree.island.STOP"

        fun start(context: Context) {
            Prefs(context).enabled = true
            context.startForegroundService(Intent(context, IslandService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).enabled = false
            context.stopService(Intent(context, IslandService::class.java))
        }

        val isRunning: Boolean
            get() = IslandHub.controller != null
    }
}
