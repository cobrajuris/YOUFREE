package com.youfree.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Modo "toque perfeito": com a acessibilidade ligada, a ilha fica ACIMA da barra de status
 * (e também aparece na tela de bloqueio, só com prévias). Sem ela, a ilha funciona
 * do mesmo jeito, mas a barra de status rouba os toques no topo.
 * Não lê o conteúdo da tela.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var controller: IslandController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandHub.accessibilityHost = true
        IslandHub.service?.dropController()
        Timers.init(this)
        controller?.detach()
        controller = IslandController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also {
            it.attach()
            IslandHub.controller = it
        }
        // O serviço em primeiro plano continua para timers e "Oi assistente".
        if (Prefs(this).enabled && IslandHub.service == null) {
            try {
                IslandService.start(this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        if (!IslandHub.accessibilityHost && controller == null) return
        controller?.detach()
        if (IslandHub.controller === controller) IslandHub.controller = null
        controller = null
        IslandHub.accessibilityHost = false
        // Volta para a ilha comum.
        IslandHub.service?.ensureController()
    }
}
