package com.youfree.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Religa a ilha depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).enabled || !Settings.canDrawOverlays(context)) return
        try {
            IslandService.start(context)
        } catch (_: Exception) {
            // Alguns fabricantes bloqueiam início automático; a pessoa liga pelo app.
        }
    }
}
