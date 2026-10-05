package com.youfree.island

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * O cérebro da ilha: resolve comandos do celular localmente (hora, lanterna, música,
 * timer, abrir apps...) e manda o resto para o Claude, se houver chave configurada.
 */
class Assistant(private val ctx: Context, private val island: IslandController) {

    private val prefs = Prefs(ctx)
    private val executor = Executors.newSingleThreadExecutor()
    private val history = ArrayList<ClaudeClient.Turn>()
    private var claude: ClaudeClient? = null
    private var claudeConfig = ""
    private val ptBR = Locale.forLanguageTag("pt-BR")

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = ptBR
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        IslandHub.main.post { island.onSpeaking(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        IslandHub.main.post { island.onSpeaking(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        IslandHub.main.post { island.onSpeaking(false) }
                    }
                })
            }
        }
    }

    // ---------------------------------------------------------------------

    /** Responde [raw]; [done] é chamado na thread principal. */
    fun handle(raw: String, done: (String) -> Unit) {
        val local = try {
            localCommand(raw)
        } catch (e: Exception) {
            "Não consegui fazer isso: ${e.message ?: e.javaClass.simpleName}"
        }
        if (local != null) {
            remember(raw, local)
            done(local)
            return
        }

        val key = prefs.apiKey
        if (key.isBlank()) {
            done(
                "Ainda não sei responder isso sozinha. Coloque sua chave da API do Claude no app " +
                    "Ilha Assistente para eu responder qualquer pergunta.",
            )
            return
        }
        val client = clientFor(key, prefs.model)
        val past = history.toList()
        val system = systemPrompt()
        executor.execute {
            val reply = try {
                client.reply(system, past, raw)
            } catch (e: Exception) {
                "Algo deu errado: ${e.message ?: e.javaClass.simpleName}"
            }
            IslandHub.main.post {
                remember(raw, reply)
                done(reply)
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        executor.shutdownNow()
        claude?.close()
        claude = null
    }

    // ---------------------------------------------------------------------

    private fun clientFor(key: String, model: String): ClaudeClient {
        val config = "$key|$model"
        val current = claude
        if (current != null && config == claudeConfig) return current
        current?.close()
        return ClaudeClient(key, model).also {
            claude = it
            claudeConfig = config
        }
    }

    private fun remember(user: String, reply: String) {
        history.add(ClaudeClient.Turn(fromUser = true, text = user))
        history.add(ClaudeClient.Turn(fromUser = false, text = reply))
        while (history.size > 12) {
            history.removeAt(0)
            history.removeAt(0)
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", ptBR).format(Date())
        return """
            Você é ${prefs.assistantName}, uma assistente pessoal que vive na ilha dinâmica do celular Android do usuário.
            Suas respostas aparecem num balão pequeno no topo da tela e muitas vezes são lidas em voz alta, então:
            responda em português do Brasil, de forma curta (1 a 3 frases), natural e simpática, sem markdown, listas, links ou emojis.
            Se a pergunta exigir algo que você não consegue fazer no celular, diga isso em uma frase e sugira o que a pessoa pode fazer.
            Agora é $now.
        """.trimIndent()
    }

    // ---------------------------------------------------------------------
    // Comandos locais
    // ---------------------------------------------------------------------

    private fun localCommand(raw: String): String? {
        val n = normalize(raw)
        if (n.isEmpty()) return "Não entendi. Pode repetir?"

        when {
            n.matches(Regex("^(ajuda|o que voce (faz|sabe fazer)|comandos)$")) ->
                return "Posso dizer a hora, a bateria, ligar a lanterna, controlar a música, criar timer e alarme, " +
                    "abrir apps e ler suas notificações. Com a chave do Claude, respondo qualquer pergunta."

            n.matches(Regex("^(esquece|esqueca|nova conversa|limpa(r)? (a )?conversa).*")) -> {
                history.clear()
                return "Pronto, comecei uma conversa nova."
            }

            n.contains("que horas") || n == "horas" || n.contains("hora agora") ->
                return "Agora são ${SimpleDateFormat("HH:mm", ptBR).format(Date())}."

            n.contains("que dia") || n.contains("data de hoje") ->
                return "Hoje é ${SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date())}."

            n.contains("bateria") -> {
                val pct = island.batteryPercent()
                return if (island.isCharging()) "A bateria está em $pct% e carregando." else "A bateria está em $pct%."
            }

            n.contains("lanterna") -> {
                val on = !(n.contains("deslig") || n.contains("apag"))
                return setTorch(on)
            }

            n.contains("notifica") -> return readNotifications()
        }

        timerCommand(n)?.let { return it }
        alarmCommand(n)?.let { return it }
        mediaCommand(n)?.let { return it }
        openAppCommand(n)?.let { return it }
        return null
    }

    private fun setTorch(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)!!
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Este celular não tem lanterna."
            cm.setTorchMode(id, on)
            if (on) "Lanterna ligada." else "Lanterna desligada."
        } catch (e: CameraAccessException) {
            "A câmera está em uso, não consegui mexer na lanterna."
        }
    }

    private fun readNotifications(): String {
        val list = IslandHub.recent.take(3)
        if (list.isEmpty()) {
            return if (IslandHub.notificationWatcher == null) {
                "Preciso de acesso às notificações. Ative no app Ilha Assistente."
            } else {
                "Nenhuma notificação nova."
            }
        }
        return list.joinToString(" ") { info ->
            val body = listOf(info.title, info.text).filter { it.isNotBlank() }.joinToString(": ")
            "${info.appName}: $body."
        }
    }

    private fun timerCommand(n: String): String? {
        if (!Regex("\\b(timer|temporizador|cronometro|contagem)\\b").containsMatchIn(n)) return null
        val m = Regex("(\\d+|um|uma|dois|duas|tres|meia)\\s*(segundo|minuto|hora)").find(n)
            ?: return "Quanto tempo? Diga por exemplo: timer de 5 minutos."
        val amount = when (val word = m.groupValues[1]) {
            "um", "uma" -> 1
            "dois", "duas" -> 2
            "tres" -> 3
            "meia" -> 30
            else -> word.toInt()
        }
        val seconds = when (m.groupValues[2]) {
            "segundo" -> amount
            "minuto" -> amount * 60
            else -> if (m.groupValues[1] == "meia") 30 * 60 else amount * 3600
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, prefs.assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Timer de ${m.value} começando agora." else "Não achei um app de relógio para o timer."
    }

    private fun alarmCommand(n: String): String? {
        if (!Regex("\\b(alarme|despertador|me acord\\w*)\\b").containsMatchIn(n)) return null
        val m = Regex("(\\d{1,2})(?:\\s*(?:h|:|horas?)\\s*(\\d{2})?)?").find(n)
            ?: return "Para que horas? Diga por exemplo: alarme às 7 e 30."
        var hour = m.groupValues[1].toInt()
        val minutes = m.groupValues[2].toIntOrNull()
            ?: Regex("\\be (\\d{1,2})\\b").find(n.substring(m.range.last + 1))?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        if (Regex("\\b(da tarde|da noite)\\b").containsMatchIn(n) && hour < 12) hour += 12
        if (hour > 23 || minutes > 59) return "Esse horário não existe."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            .putExtra(AlarmClock.EXTRA_MESSAGE, prefs.assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val time = String.format(ptBR, "%02d:%02d", hour, minutes)
        return if (start(intent)) "Alarme marcado para $time." else "Não achei um app de relógio para o alarme."
    }

    private fun mediaCommand(n: String): String? {
        val words = n.split(" ").size
        val aboutMusic = n.contains("musica") || words <= 2
        return when {
            n.contains("o que") && n.contains("tocando") ->
                island.nowPlaying?.let { "Está tocando $it." } ?: "Nada tocando agora."
            aboutMusic && Regex("^(pausa|pause|pausar|para|parar|pare)\\b").containsMatchIn(n) ->
                if (island.pauseMusic()) "Música pausada." else NO_MUSIC
            aboutMusic && Regex("^(proxima|pula|pular|avanca|passa)\\b").containsMatchIn(n) ->
                if (island.nextTrack()) "Próxima música." else NO_MUSIC
            aboutMusic && Regex("^(anterior|volta|voltar)\\b").containsMatchIn(n) ->
                if (island.previousTrack()) "Voltando a música." else NO_MUSIC
            aboutMusic && Regex("^(toca|tocar|continua|continuar|play|despausa)\\b").containsMatchIn(n) ->
                if (island.playMusic()) "Tocando." else NO_MUSIC
            else -> null
        }
    }

    private fun openAppCommand(n: String): String? {
        val m = Regex("^(abr[aei]|abrir|inicia|iniciar|abre pra mim)\\s+(o |a |os |as )?(app |aplicativo )?(.+)$").find(n)
            ?: return null
        val query = m.groupValues[4].trim()
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        val best = apps
            .map { it to normalize(it.loadLabel(pm).toString()) }
            .filter { (_, label) -> label.isNotEmpty() && (label == query || label.contains(query) || query.contains(label)) }
            .minByOrNull { (_, label) -> if (label == query) 0 else label.length }
            ?: return "Não achei nenhum app chamado $query."
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "Não consegui abrir ${best.first.loadLabel(pm)}."
        return if (start(intent)) "Abrindo ${best.first.loadLabel(pm)}." else "Não consegui abrir ${best.first.loadLabel(pm)}."
    }

    private fun start(intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val NO_MUSIC = "Não tem nenhum player de música aberto (ou falta o acesso às notificações)."

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9: ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
