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
import android.os.Build
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

/** Tela de configuração: ligar a ilha, permissões, chave do Claude e posição. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var islandSwitch: Switch
    private lateinit var overlayStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var agendaStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var brightStatus: TextView
    private lateinit var sideRight: Button
    private lateinit var sideLeft: Button
    private var updatingSwitch = false

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
        column.addView(text("Uma ilha na borda da tela com hora, sinal, Wi-Fi, brilho, volume, bateria, agenda, mensagens e uma assistente de voz.", 15f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        // ---------------- Ligar ----------------
        @Suppress("DEPRECATION")
        islandSwitch = Switch(this).apply {
            text = "Ilha ligada"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setOnCheckedChangeListener { _, on -> if (!updatingSwitch) toggleIsland(on) }
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(CARD)
            addView(islandSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })
        column.addView(button("Testar a ilha") {
            val c = IslandHub.controller
            if (c == null) toast("Ligue a ilha primeiro.") else c.showDemo()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
        })

        // ---------------- Permissões ----------------
        column.addView(section("1. Mostrar sobre outros apps (obrigatório)"))
        overlayStatus = status()
        column.addView(card(
            "É o que deixa a ilha aparecer na borda da tela. Na lista, ache \"Ilha Assistente\" e ative.",
            overlayStatus,
            button("Permitir sobreposição") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
        ))

        column.addView(section("2. Microfone"))
        micStatus = status()
        column.addView(card(
            "Para conversar por voz com a assistente.",
            micStatus,
            button("Permitir microfone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10) },
        ))

        column.addView(section("3. Agenda e contatos"))
        agendaStatus = status()
        column.addView(card(
            "Mostra seus compromissos, avisa 10 minutos antes, marca eventos por voz e liga ou manda WhatsApp para seus contatos.",
            agendaStatus,
            button("Permitir agenda e contatos") {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS),
                    11,
                )
            },
        ))

        column.addView(section("4. Mensagens e música (opcional)"))
        notifStatus = status()
        column.addView(card(
            "Mostra as mensagens que chegam (WhatsApp, Instagram...) num cartão na borda e controla a música tocando.",
            notifStatus,
            button("Abrir acesso a notificações") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        ))
        column.addView(card(
            "Se aparecer cinza ou \"Configuração restrita\": toque no botão abaixo, depois nos ⋮ no canto de cima e em " +
                "\"Permitir configurações restritas\". Volte e ative de novo.",
            null,
            button("Abrir informações do app", primary = false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        ))

        column.addView(section("5. Brilho (opcional)"))
        brightStatus = status()
        column.addView(card(
            "Deixa a ilha e a assistente mudarem o brilho da tela.",
            brightStatus,
            button("Permitir mudar o brilho") {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            },
        ))

        // ---------------- Posição ----------------
        column.addView(section("Posição"))
        sideRight = button("Borda direita", primary = prefs.rightSide) { setSide(true) }
        sideLeft = button("Borda esquerda", primary = !prefs.rightSide) { setSide(false) }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sideLeft, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(sideRight, LinearLayout.LayoutParams(0, dp(46), 1f))
        })
        column.addView(slider("Altura na tela", (prefs.positionY * 100).roundToInt()) {
            prefs.positionY = it / 100f
            IslandHub.controller?.applyPrefs()
        })
        column.addView(text("Dica: você também pode arrastar a alcinha para cima e para baixo.", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })

        // ---------------- Assistente ----------------
        column.addView(section("Assistente"))
        column.addView(label("Nome da assistente"))
        column.addView(input(prefs.assistantName, "Ilha") { prefs.assistantName = it })
        column.addView(label("Sua cidade (para clima e notícias)"))
        column.addView(input(prefs.city, "Ex.: São Paulo") { prefs.city = it })
        column.addView(label("Chave da API do Claude (opcional)"))
        column.addView(input(prefs.apiKey, "sk-ant-...", secret = true) { prefs.apiKey = it })
        column.addView(text(
            "Sem chave, a assistente já faz os comandos do celular. Com a chave, responde qualquer pergunta e busca clima, " +
                "notícias e resultados na internet. Crie em console.anthropic.com → API Keys (uso pago). A chave fica só neste celular.",
            13f, MUTED,
        ).apply { setPadding(0, dp(6), 0, 0) })
        column.addView(label("Modelo do Claude"))
        column.addView(input(prefs.model, ClaudeClient.DEFAULT_MODEL) { prefs.model = it })

        column.addView(switch("Falar as respostas em voz alta", prefs.speakReplies) { prefs.speakReplies = it })
        column.addView(switch("Mostrar mensagens na ilha", prefs.showNotifications) { prefs.showNotifications = it })
        column.addView(switch("Avisar 10 min antes dos compromissos", prefs.eventReminders) { prefs.eventReminders = it })

        column.addView(section("Como usar"))
        column.addView(text(
            "• Toque na alcinha da borda (ou puxe para dentro) para abrir a coluna.\n" +
                "• Toque na hora ou no calendário para ver a agenda; no brilho, volume ou bateria para os controles; " +
                "no Wi-Fi ou no sinal para as conexões; no raio para a lanterna; no microfone para falar.\n" +
                "• Exemplos para falar: \"marca dentista sexta às 10\", \"o que eu tenho amanhã?\", \"liga para a Maria\", " +
                "\"manda mensagem pro João dizendo já estou chegando\", \"aumenta o volume\", \"brilho em 50\", " +
                "\"modo vibrar\", \"timer de 5 minutos\", \"alarme às 7 e 30\", \"pausa a música\", \"vai chover hoje?\".\n" +
                "• A ilha some na horizontal (vídeos e jogos).",
            14f, 0xFFD6D6DE.toInt(),
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Voltou de dar a permissão de sobreposição: liga sozinho.
        if (prefs.enabled && Settings.canDrawOverlays(this) && !IslandService.isRunning) {
            startIsland()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun toggleIsland(on: Boolean) {
        if (!on) {
            IslandService.stop(this)
            return
        }
        prefs.enabled = true
        if (!Settings.canDrawOverlays(this)) {
            toast("Primeiro permita \"Mostrar sobre outros apps\".")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startIsland()
    }

    private fun startIsland() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        try {
            IslandService.start(this)
        } catch (e: Exception) {
            toast("Não consegui ligar a ilha: ${e.message}")
        }
        islandSwitch.postDelayed({ refreshStatus() }, 400)
    }

    private fun setSide(right: Boolean) {
        prefs.rightSide = right
        paintButton(sideRight, right)
        paintButton(sideLeft, !right)
        IslandHub.controller?.applyPrefs()
    }

    private fun refreshStatus() {
        updatingSwitch = true
        islandSwitch.isChecked = prefs.enabled && Settings.canDrawOverlays(this)
        updatingSwitch = false

        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(micStatus, granted(Manifest.permission.RECORD_AUDIO))
        setStatus(agendaStatus, granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.READ_CONTACTS))
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        setStatus(notifStatus, listeners.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName })
        setStatus(brightStatus, Settings.System.canWrite(this))
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(view: TextView, ok: Boolean) {
        view.text = if (ok) "✓ Ativado" else "• Pendente"
        view.setTextColor(if (ok) Ui.GREEN else 0xFFFFB86B.toInt())
    }

    // ---------------- Fábrica de views ----------------

    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

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
        background = rounded(CARD)
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
        paintButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun paintButton(b: Button, primary: Boolean) {
        b.background = rounded(if (primary) Ui.ACCENT else 0xFF2A2A35.toInt(), 23)
    }

    private fun input(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(0xFF5E5E6A.toInt())
        setTextColor(Color.WHITE)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(CARD, 14)
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

    private fun slider(title: String, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        box.addView(text(title, 14f, 0xFFD6D6DE.toInt()))
        box.addView(SeekBar(this).apply {
            max = 100
            progress = value.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
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
