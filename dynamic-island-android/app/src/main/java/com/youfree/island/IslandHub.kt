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
)

/**
 * Ponte entre os componentes do app (serviço de acessibilidade, ouvinte de notificações,
 * tela de voz). Todos rodam no mesmo processo; só acessar na thread principal.
 */
object IslandHub {
    val main = Handler(Looper.getMainLooper())

    /** A ilha visível, ou null se o serviço de acessibilidade estiver desligado. */
    var controller: IslandController? = null

    var notificationWatcher: NotificationWatcher? = null

    /** Últimas notificações, mais recente primeiro. */
    val recent = ArrayDeque<NotificationInfo>()

    fun addNotification(info: NotificationInfo) {
        recent.removeAll { it.packageName == info.packageName && it.title == info.title }
        recent.addFirst(info)
        while (recent.size > 15) recent.removeLast()
    }
}
