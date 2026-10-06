package com.youfree.island

import android.content.Context

/** Configurações do usuário, guardadas só no aparelho. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("island", Context.MODE_PRIVATE)

    /** A ilha deve ficar ligada (religa sozinha depois de reiniciar o celular). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    var assistantName: String
        get() = sp.getString("assistant_name", null)?.takeIf { it.isNotBlank() } ?: "Ilha"
        set(value) = sp.edit().putString("assistant_name", value.trim()).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = sp.getString("model", null)?.takeIf { it.isNotBlank() } ?: ClaudeClient.DEFAULT_MODEL
        set(value) = sp.edit().putString("model", value.trim()).apply()

    /** Cidade usada nas buscas na internet (clima, notícias locais...). */
    var city: String
        get() = sp.getString("city", "") ?: ""
        set(value) = sp.edit().putString("city", value.trim()).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(value) = sp.edit().putBoolean("speak", value).apply()

    var showNotifications: Boolean
        get() = sp.getBoolean("show_notifications", true)
        set(value) = sp.edit().putBoolean("show_notifications", value).apply()

    /** Avisar na ilha 10 minutos antes de cada compromisso. */
    var eventReminders: Boolean
        get() = sp.getBoolean("event_reminders", true)
        set(value) = sp.edit().putBoolean("event_reminders", value).apply()

    /** Escutar "Oi assistente" com a tela ligada (precisa baixar o modelo de voz uma vez). */
    var wakeWord: Boolean
        get() = sp.getBoolean("wake_word", true)
        set(value) = sp.edit().putBoolean("wake_word", value).apply()

    /** Tela de bloqueio premium (relógio, agenda e prévias de mensagens sobre o bloqueio do sistema). */
    var lockScreen: Boolean
        get() = sp.getBoolean("lock_screen", true)
        set(value) = sp.edit().putBoolean("lock_screen", value).apply()

    /** Na tela de bloqueio, mostrar só quem mandou, sem o texto da mensagem. */
    var lockHideContent: Boolean
        get() = sp.getBoolean("lock_hide_content", false)
        set(value) = sp.edit().putBoolean("lock_hide_content", value).apply()

    /** Ilha no topo, em volta da câmera frontal (como no iPhone). false = presa na borda lateral. */
    var islandOnTop: Boolean
        get() = sp.getBoolean("island_on_top", true)
        set(value) = sp.edit().putBoolean("island_on_top", value).apply()

    /** true = borda direita da tela; false = borda esquerda. */
    var rightSide: Boolean
        get() = sp.getBoolean("right_side", true)
        set(value) = sp.edit().putBoolean("right_side", value).apply()

    /** Posição vertical da aba, de 0 (topo) a 1 (base). */
    var positionY: Float
        get() = sp.getFloat("position_y", 0.28f)
        set(value) = sp.edit().putFloat("position_y", value.coerceIn(0f, 1f)).apply()
}
