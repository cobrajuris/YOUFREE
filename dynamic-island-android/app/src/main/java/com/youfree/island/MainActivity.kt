package com.youfree.island

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Tela de configuração: permissões, chave do Claude e ajustes de posição da ilha. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var micStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var islandStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
        })

        column.addView(text("Ilha Assistente", 28f, Color.WHITE, bold = true))
        column.addView(text("Sua ilha dinâmica no Android: notificações, música e um assistente de voz em cima da câmera.", 15f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        // ---------------- Passos ----------------
        column.addView(section("1. Microfone"))
        micStatus = status()
        column.addView(card(
            "Para conversar por voz com a ilha.",
            micStatus,
            button("Permitir microfone") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            },
        ))

        column.addView(section("2. Notificações e música"))
        notifStatus = status()
        column.addView(card(
            "Mostra mensagens novas na ilha e controla a música que estiver tocando (Spotify, YouTube Music, YouFree...).",
            notifStatus,
            button("Abrir acesso a notificações") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        ))

        column.addView(section("3. Ligar a ilha"))
        islandStatus = status()
        column.addView(card(
            "Em Acessibilidade, toque em \"Ilha Assistente\" e ative. É isso que desenha a ilha por cima da barra de status.",
            islandStatus,
            button("Abrir acessibilidade") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        ))

        column.addView(card(
            "A opção aparece cinza ou diz \"Configuração restrita\"? Isso acontece com apps instalados por APK no Android 13+. " +
                "Abra as informações do app, toque nos ⋮ no canto de cima e escolha \"Permitir configurações restritas\". Depois volte ao passo 2 ou 3.",
            null,
            button("Abrir informações do app", primary = false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        ))

        column.addView(button("Testar a ilha agora") {
            val c = IslandHub.controller
            if (c == null) toast("Ative a ilha no passo 3 primeiro.") else c.showDemo()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) }
        })

        // ---------------- Assistente ----------------
        column.addView(section("Assistente"))
        column.addView(label("Nome da assistente"))
        column.addView(input(prefs.assistantName, "Ilha") { prefs.assistantName = it })

        column.addView(label("Chave da API do Claude (opcional)"))
        column.addView(input(prefs.apiKey, "sk-ant-...", secret = true) { prefs.apiKey = it })
        column.addView(text(
            "Sem chave, a ilha faz os comandos do celular (hora, bateria, lanterna, timer, alarme, música, abrir apps, ler notificações). " +
                "Com a chave, ela responde qualquer pergunta. Crie em console.anthropic.com → API Keys. A chave fica só neste celular.",
            13f, MUTED,
        ).apply { setPadding(0, dp(6), 0, 0) })

        column.addView(label("Modelo do Claude"))
        column.addView(input(prefs.model, ClaudeClient.DEFAULT_MODEL) { prefs.model = it })

        column.addView(switch("Falar as respostas em voz alta", prefs.speakReplies) { prefs.speakReplies = it })
        column.addView(switch("Mostrar notificações na ilha", prefs.showNotifications) { prefs.showNotifications = it })

        // ---------------- Aparência ----------------
        column.addView(section("Posição e tamanho"))
        column.addView(text("Ajuste até a ilha cobrir a câmera frontal do seu celular. As mudanças aparecem na hora.", 13f, MUTED))
        column.addView(slider("Distância do topo", 0, 80, prefs.offsetYDp) { prefs.offsetYDp = it })
        column.addView(slider("Largura", 60, 220, prefs.idleWidthDp) { prefs.idleWidthDp = it })
        column.addView(slider("Altura", 20, 48, prefs.idleHeightDp) { prefs.idleHeightDp = it })

        column.addView(section("Como usar"))
        column.addView(text(
            "• Toque na ilha para abrir.\n" +
                "• Segure a ilha para falar com a assistente.\n" +
                "• Exemplos: \"que horas são\", \"liga a lanterna\", \"timer de 5 minutos\", \"alarme às 7 e 30\", " +
                "\"pausa a música\", \"abre o WhatsApp\", \"lê minhas notificações\".\n" +
                "• Com a chave do Claude: pergunte qualquer coisa.\n" +
                "• A ilha se esconde quando o celular fica na horizontal.",
            14f, 0xFFD6D6DE.toInt(),
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setStatus(micStatus, mic)

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val notif = listeners.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
        setStatus(notifStatus, notif)

        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val island = services.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
        setStatus(islandStatus, island)
    }

    private fun setStatus(view: TextView, ok: Boolean) {
        view.text = if (ok) "✓ Ativado" else "• Pendente"
        view.setTextColor(if (ok) IslandController.GREEN else 0xFFFFB86B.toInt())
    }

    // ---------------- Fábrica de views ----------------

    private fun section(title: String) = text(title, 18f, Color.WHITE, bold = true).apply {
        setPadding(0, dp(26), 0, dp(10))
    }

    private fun label(title: String) = text(title, 14f, 0xFFD6D6DE.toInt()).apply {
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun status() = text("", 14f, MUTED, bold = true)

    private fun card(description: String, status: TextView?, action: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(CARD)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
        if (status != null) addView(status)
        addView(text(description, 14f, 0xFFD6D6DE.toInt()).apply { setPadding(0, dp(4), 0, dp(10)) })
        addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = GradientDrawable().apply {
            cornerRadius = dp(23).toFloat()
            setColor(if (primary) IslandController.ACCENT else 0xFF2A2A35.toInt())
        }
        setOnClickListener { onClick() }
    }

    private fun input(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(0xFF5E5E6A.toInt())
        setTextColor(Color.WHITE)
        isSingleLine = true
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(CARD)
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(14), 0, 0)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val caption = text("$title: $value dp", 14f, 0xFFD6D6DE.toInt())
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    caption.text = "$title: $v dp"
                    if (fromUser) {
                        onChange(v)
                        IslandHub.controller?.applyPrefs()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        box.addView(caption)
        box.addView(bar)
        return box
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.START
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private val BG = 0xFF0B0B10.toInt()
        private val CARD = 0xFF17171F.toInt()
        private val MUTED = 0xFF9A9AA5.toInt()
    }
}
