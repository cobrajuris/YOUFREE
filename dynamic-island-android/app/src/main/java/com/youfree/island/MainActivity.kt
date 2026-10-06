package com.youfree.island

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Tela de configuração no estilo dos Ajustes do iPhone. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var islandSwitch: IosSwitch
    private val statusViews = HashMap<String, LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(48))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = false
            addView(column)
        })

        column.addView(Ui.text(this, 34f, Color.WHITE, value = "Ilha", weight = Ui.Weight.DISPLAY), lp(top = 8, side = 4))
        column.addView(hero(), lp(top = 16))

        // ---------------- Ligar ----------------
        islandSwitch = IosSwitch(this).apply {
            setChecked(prefs.enabled && Settings.canDrawOverlays(this@MainActivity), animate = false)
            onChange = { toggleIsland(it) }
        }
        column.addView(group(null, null, listOf(
            row(R.drawable.ic_power, Ui.GREEN, "Ilha ligada", "Fica na borda da tela", islandSwitch),
            row(R.drawable.ic_sparkle, Ui.INDIGO, "Testar a ilha", null, chevron()) {
                val c = IslandHub.controller
                if (c == null) toast("Ligue a ilha primeiro.") else c.showDemo()
            },
        )), lp(top = 24))

        // ---------------- Permissões ----------------
        column.addView(group(
            "Permissões",
            "Se \"Mensagens e música\" aparecer cinza ou como \"configuração restrita\", toque em \"Configurações restritas\", " +
                "depois nos ⋮ no canto de cima e em \"Permitir configurações restritas\".",
            listOf(
                statusRow("overlay", R.drawable.ic_layers, Ui.BLUE, "Mostrar sobre outros apps", "Obrigatório") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                },
                statusRow("mic", R.drawable.ic_mic, Ui.ORANGE, "Microfone", "Para falar com a assistente") {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
                },
                statusRow("agenda", R.drawable.ic_calendar, Ui.RED, "Agenda e contatos", "Compromissos, ligações e WhatsApp") {
                    requestPermissions(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS),
                        11,
                    )
                },
                statusRow("notif", R.drawable.ic_chat, Ui.GREEN, "Mensagens e música", "Opcional") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                statusRow("bright", R.drawable.ic_brightness, Ui.BLUE, "Brilho da tela", "Opcional") {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                },
                row(R.drawable.ic_shield, Ui.GRAY, "Configurações restritas", null, chevron()) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
            ),
        ), lp(top = 28))

        // ---------------- Aparência ----------------
        val segmented = Segmented(this, listOf("Esquerda", "Direita"), if (prefs.rightSide) 1 else 0) { i ->
            prefs.rightSide = i == 1
            IslandHub.controller?.applyPrefs()
        }
        val height = PillSlider(this, R.drawable.ic_updown).apply {
            value = prefs.positionY
            onChange = {
                prefs.positionY = it
                IslandHub.controller?.applyPrefs()
            }
        }
        val appearance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(Ui.text(this@MainActivity, 15f, Color.WHITE, value = "Borda da tela"))
            addView(segmented, lp(top = 10, h = dp(34)))
            addView(Ui.text(this@MainActivity, 15f, Color.WHITE, value = "Altura na tela"), lp(top = 18))
            addView(height, lp(top = 10, h = dp(44)))
        }
        column.addView(group("Aparência", "Você também pode arrastar a alcinha para cima e para baixo direto na tela.", listOf(appearance)), lp(top = 28))

        // ---------------- Assistente ----------------
        column.addView(group(
            "Assistente",
            "Sem chave, a assistente já faz os comandos do celular. Com a chave do Claude (console.anthropic.com → API Keys, uso pago), " +
                "responde qualquer pergunta e busca clima e notícias. A chave fica só neste celular.",
            listOf(
                inputRow(R.drawable.ic_person, Ui.PURPLE, "Nome", prefs.assistantName, "Ilha") { prefs.assistantName = it },
                inputRow(R.drawable.ic_place, Ui.RED, "Cidade", prefs.city, "São Paulo") { prefs.city = it },
                inputRow(R.drawable.ic_key, Ui.GRAY, "Chave", prefs.apiKey, "sk-ant-…", secret = true) { prefs.apiKey = it },
                inputRow(R.drawable.ic_sparkle, Ui.INDIGO, "Modelo", prefs.model, ClaudeClient.DEFAULT_MODEL) { prefs.model = it },
            ),
        ), lp(top = 28))

        column.addView(group(null, null, listOf(
            switchRow(R.drawable.ic_speaker, Ui.PINK, "Falar as respostas", prefs.speakReplies) { prefs.speakReplies = it },
            switchRow(R.drawable.ic_bell, Ui.RED, "Mostrar mensagens", prefs.showNotifications) { prefs.showNotifications = it },
            switchRow(R.drawable.ic_calendar, Ui.ORANGE, "Avisar antes dos compromissos", prefs.eventReminders) { prefs.eventReminders = it },
        )), lp(top = 20))

        // ---------------- Dicas ----------------
        val tips = listOf(
            "Toque na alcinha da borda, ou puxe para dentro, para abrir.",
            "Toque na hora ou no calendário para ver sua agenda.",
            "Brilho, volume ou bateria abrem os controles.",
            "\"Marca dentista sexta às 10\"",
            "\"O que eu tenho amanhã?\"",
            "\"Manda mensagem pro João dizendo já estou chegando\"",
            "\"Vai chover hoje?\"",
        )
        column.addView(group("Como usar", "A ilha some sozinha na horizontal, em vídeos e jogos.", tips.map { tipRow(it) }), lp(top = 28))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Voltou de dar a permissão de sobreposição: liga sozinho.
        if (prefs.enabled && Settings.canDrawOverlays(this) && !IslandService.isRunning) startIsland()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        try {
            IslandService.start(this)
        } catch (e: Exception) {
            toast("Não consegui ligar a ilha: ${e.message}")
        }
    }

    private fun refreshStatus() {
        islandSwitch.setChecked(prefs.enabled && Settings.canDrawOverlays(this), animate = false)
        setStatus("overlay", Settings.canDrawOverlays(this))
        setStatus("mic", granted(Manifest.permission.RECORD_AUDIO))
        setStatus("agenda", granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.READ_CONTACTS))
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        setStatus("notif", listeners.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName })
        setStatus("bright", Settings.System.canWrite(this))
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(key: String, ok: Boolean) {
        val box = statusViews[key] ?: return
        val icon = box.getChildAt(0) as ImageView
        val label = box.getChildAt(1) as TextView
        if (ok) {
            icon.setImageResource(R.drawable.ic_check)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
            icon.visibility = View.VISIBLE
            label.text = "Ativado"
            label.setTextColor(Ui.SECONDARY)
        } else {
            icon.visibility = View.GONE
            label.text = "Ativar"
            label.setTextColor(Ui.BLUE)
        }
    }

    // ---------------- Componentes ----------------

    /** Prévia da ilha sobre um "papel de parede", como na imagem de referência. */
    private fun hero(): View {
        val frame = FrameLayout(this).apply {
            background = Ui.gradient(Ui.dpf(this@MainActivity, 26f), 0xFF2B1B5C.toInt(), 0xFF6A3FC4.toInt(), 0xFFB37BE8.toInt(), 0xFF7FA8FF.toInt())
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@MainActivity, 13f, 0xCCFFFFFF.toInt(), value = "ILHA ASSISTENTE", weight = Ui.Weight.SEMIBOLD).apply { letterSpacing = 0.08f })
            addView(Ui.text(this@MainActivity, 26f, Color.WHITE, value = "Tudo à mão,\nna borda da tela.", weight = Ui.Weight.DISPLAY).apply {
                setLineSpacing(0f, 1.05f)
            }, lp(top = 8))
            addView(Ui.text(this@MainActivity, 14f, 0xD9FFFFFF.toInt(), value = "Agenda, mensagens, música,\ncontroles e uma assistente de voz."), lp(top = 10))
        }
        frame.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(22)
        })

        // Mini coluna da ilha presa na borda direita
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), dp(14), dp(12))
            background = Ui.rounded(Color.BLACK, Ui.dpf(this@MainActivity, 22f), Ui.HAIRLINE, 1)
            addView(Ui.text(this@MainActivity, 13f, Color.WHITE, value = "9:41", weight = Ui.Weight.DISPLAY))
            addView(SignalView(this@MainActivity).apply { level = 4 }, LinearLayout.LayoutParams(dp(14), dp(10)).apply { topMargin = dp(12) })
            for (res in listOf(R.drawable.ic_wifi, R.drawable.ic_brightness, R.drawable.ic_volume)) {
                addView(Ui.icon(this@MainActivity, res), LinearLayout.LayoutParams(dp(15), dp(15)).apply { topMargin = dp(12) })
            }
            addView(BatteryView(this@MainActivity).apply { percent = 80 }, LinearLayout.LayoutParams(dp(20), dp(10)).apply { topMargin = dp(12) })
        }
        frame.addView(dock, FrameLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = -dp(12)
        })
        return frame
    }

    private fun group(title: String?, footer: String?, rows: List<View>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (title != null) {
            addView(Ui.text(this@MainActivity, 13f, Ui.SECONDARY, value = title.uppercase(), weight = Ui.Weight.MEDIUM).apply {
                letterSpacing = 0.04f
            }, lp(side = 16).apply { bottomMargin = dp(8) })
        }
        val card = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, Ui.dpf(this@MainActivity, 14f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                card.addView(View(this@MainActivity).apply { setBackgroundColor(Ui.SEPARATOR) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)).apply { marginStart = dp(58) })
            }
            card.addView(r)
        }
        addView(card)
        if (footer != null) {
            addView(Ui.text(this@MainActivity, 13f, Ui.SECONDARY, value = footer).apply {
                setLineSpacing(Ui.dpf(this@MainActivity, 2f), 1f)
            }, lp(top = 8, side = 16))
        }
    }

    private fun row(iconRes: Int, color: Int, title: String, subtitle: String?, trailing: View?, onClick: (() -> Unit)? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(Ui.iconTile(this@MainActivity, iconRes, color, 30, 18), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(this@MainActivity, 16f, Color.WHITE, value = title))
                if (subtitle != null) addView(Ui.text(this@MainActivity, 13f, Ui.SECONDARY, value = subtitle), lp(top = 3))
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing)
            if (onClick != null) {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, android.graphics.drawable.ColorDrawable(Color.WHITE),
                )
                setOnClickListener {
                    Ui.haptic(it)
                    onClick()
                }
            }
        }

    private fun chevron() = Ui.icon(this, R.drawable.ic_chevron, Ui.TERTIARY).apply {
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }

    private fun statusRow(key: String, iconRes: Int, color: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) })
            addView(Ui.text(this@MainActivity, 15f, Ui.SECONDARY))
            addView(chevron())
        }
        statusViews[key] = trailing
        return row(iconRes, color, title, subtitle, trailing, onClick)
    }

    private fun switchRow(iconRes: Int, color: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = IosSwitch(this).apply {
            setChecked(checked, animate = false)
            this.onChange = onChange
        }
        return row(iconRes, color, title, null, sw)
    }

    private fun inputRow(iconRes: Int, color: Int, label: String, value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit): View {
        val input = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Ui.TERTIARY)
            setTextColor(Ui.SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Ui.font(this@MainActivity, Ui.Weight.REGULAR)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            background = null
            setPadding(0, 0, 0, 0)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
            layoutParams = LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return row(iconRes, color, label, null, input)
    }

    private fun tipRow(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(13), dp(16), dp(13))
        addView(View(this@MainActivity).apply { background = Ui.oval(Ui.BLUE) }, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            marginStart = dp(12)
            marginEnd = dp(26)
        })
        addView(Ui.text(this@MainActivity, 15f, Color.WHITE, value = text).apply {
            setLineSpacing(Ui.dpf(this@MainActivity, 2f), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun lp(top: Int = 0, side: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = dp(top)
            marginStart = dp(side)
            marginEnd = dp(side)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
