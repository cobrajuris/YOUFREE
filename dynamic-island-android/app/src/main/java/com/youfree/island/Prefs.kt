package com.youfree.island

import android.content.Context

/** Configurações do usuário, guardadas só no aparelho. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("island", Context.MODE_PRIVATE)

    var assistantName: String
        get() = sp.getString("assistant_name", null)?.takeIf { it.isNotBlank() } ?: "Ilha"
        set(value) = sp.edit().putString("assistant_name", value.trim()).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = sp.getString("model", null)?.takeIf { it.isNotBlank() } ?: ClaudeClient.DEFAULT_MODEL
        set(value) = sp.edit().putString("model", value.trim()).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(value) = sp.edit().putBoolean("speak", value).apply()

    var showNotifications: Boolean
        get() = sp.getBoolean("show_notifications", true)
        set(value) = sp.edit().putBoolean("show_notifications", value).apply()

    /** Distância (dp) do topo da tela até a ilha — ajuste para alinhar com a câmera. */
    var offsetYDp: Int
        get() = sp.getInt("offset_y", 8)
        set(value) = sp.edit().putInt("offset_y", value).apply()

    /** Largura (dp) da ilha em repouso. */
    var idleWidthDp: Int
        get() = sp.getInt("idle_width", 110)
        set(value) = sp.edit().putInt("idle_width", value).apply()

    /** Altura (dp) da ilha em repouso. */
    var idleHeightDp: Int
        get() = sp.getInt("idle_height", 32)
        set(value) = sp.edit().putInt("idle_height", value).apply()
}
