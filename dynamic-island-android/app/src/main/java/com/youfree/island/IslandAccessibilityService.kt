package com.youfree.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * Hospeda a ilha. Uma janela TYPE_ACCESSIBILITY_OVERLAY fica acima da barra de status,
 * então o toque na ilha não puxa a cortina de notificações.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var controller: IslandController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller?.detach()
        controller = IslandController(this).also {
            it.attach()
            IslandHub.controller = it
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
        controller?.detach()
        if (IslandHub.controller === controller) IslandHub.controller = null
        controller = null
    }
}
