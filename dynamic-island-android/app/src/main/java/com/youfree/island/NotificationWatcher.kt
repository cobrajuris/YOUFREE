package com.youfree.island

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Leva as notificações novas para a ilha e libera o acesso às sessões de música. */
class NotificationWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandHub.notificationWatcher = this
        IslandHub.controller?.refreshMedia()
    }

    override fun onListenerDisconnected() {
        if (IslandHub.notificationWatcher === this) IslandHub.notificationWatcher = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return // player de música: a ilha já mostra

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val pm = packageManager
        val appInfo = try {
            pm.getApplicationInfo(sbn.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val info = NotificationInfo(
            packageName = sbn.packageName,
            appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: sbn.packageName,
            title = title,
            text = text,
            icon = appInfo?.let { pm.getApplicationIcon(it) },
            contentIntent = n.contentIntent,
            time = sbn.postTime,
        )
        IslandHub.addNotification(info)
        IslandHub.controller?.showNotification(info)
    }
}
