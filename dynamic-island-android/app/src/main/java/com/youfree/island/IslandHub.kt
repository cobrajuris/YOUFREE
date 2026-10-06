package com.youfree.island

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

data class NotificationInfo(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val contentIntent: PendingIntent?,
    val time: Long,
    val key: String = "",
)

/**
 * Ponte entre os componentes do app (serviço da ilha, ouvinte de notificações,
 * tela de voz). Todos rodam no mesmo processo; só acessar na thread principal.
 */
object IslandHub {
    val main = Handler(Looper.getMainLooper())

    /** A ilha visível, ou null se a ilha estiver desligada. */
    var controller: IslandController? = null

    var notificationWatcher: NotificationWatcher? = null

    /** Últimas notificações, mais recente primeiro. */
    val recent = ArrayDeque<NotificationInfo>()

    fun removeNotification(key: String) {
        recent.removeAll { it.key == key }
    }

    fun addNotification(info: NotificationInfo) {
        recent.removeAll { it.packageName == info.packageName && it.title == info.title }
        recent.addFirst(info)
        while (recent.size > 15) recent.removeLast()
    }
}
