package com.youfree.island

import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * O cérebro da ilha: resolve na hora os comandos do celular (agenda, ligações, WhatsApp,
 * lanterna, volume, brilho, música, timer, alarme, abrir apps...) e manda o resto para o
 * Claude, se houver chave configurada (com busca na internet para clima, notícias etc.).
 */
class Assistant(private val ctx: Context, private val island: IslandController) {

    private val prefs = Prefs(ctx)
    private val contacts = Contacts(ctx)
    private val executor = Executors.newSingleThreadExecutor()
    private val history = ArrayList<ClaudeClient.Turn>()
    private var claude: ClaudeClient? = null
    private var claudeConfig = ""
    private val ptBR = Locale.forLanguageTag("pt-BR")

    private val status get() = island.status
    private val calendar get() = island.calendar

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx) { result ->
            if (result == TextToSpeech.SUCCESS) {
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
                    "Ilha Assistente para eu responder qualquer pergunta, até clima e notícias.",
            )
            return
        }
        val client = clientFor(key, prefs.model)
        val past = history.toList()
        val system = systemPrompt()
        val city = prefs.city
        executor.execute {
            val reply = try {
                client.reply(system, past, raw, city)
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
        val city = prefs.city.takeIf { it.isNotBlank() }?.let { " O usuário mora em $it." } ?: ""
        return """
            Você é ${prefs.assistantName}, uma assistente pessoal que vive numa ilha na borda da tela do celular Android do usuário.
            Suas respostas aparecem num cartão pequeno e muitas vezes são lidas em voz alta, então:
            responda em português do Brasil, de forma curta (1 a 3 frases), natural e simpática, sem markdown, listas, links ou emojis.
            Para clima, notícias, resultados, preços ou qualquer coisa atual, use a busca na internet e responda com o dado concreto.
            O próprio app já cuida de agenda, ligações, WhatsApp, lanterna, volume, brilho, música, timer e alarme; se a pessoa pedir
            algo assim de um jeito que chegou até você, explique em uma frase como falar o comando (ex.: "marca dentista sexta às 10").
            Agora é $now.$city
        """.trimIndent()
    }

    // ---------------------------------------------------------------------
    // Comandos locais
    // ---------------------------------------------------------------------

    private fun localCommand(raw: String): String? {
        val n = normalize(raw)
        if (n.isEmpty()) return "Não entendi. Pode repetir?"

        when {
            n.matches(Regex("^(ajuda|o que voce (faz|sabe fazer)|comandos)$")) -> return HELP

            n.matches(Regex("^(esquece|esqueca|nova conversa|limpa(r)? (a )?conversa).*")) -> {
                history.clear()
                return "Pronto, comecei uma conversa nova."
            }

            n.contains("que horas") || n == "horas" || n.contains("hora agora") ->
                return "Agora são ${SimpleDateFormat("HH:mm", ptBR).format(Date())}."

            (n.contains("que dia") && !n.contains("tenho")) || n.contains("data de hoje") ->
                return "Hoje é ${SimpleDateFormat("EEEE, d 'de' MMMM", ptBR).format(Date())}."

            n.contains("bateria") -> {
                val pct = status.batteryPercent()
                return if (status.isCharging()) "A bateria está em $pct% e carregando." else "A bateria está em $pct%."
            }

            n.contains("lanterna") -> {
                val on = !(n.contains("deslig") || n.contains("apag"))
                if (!status.hasTorch()) return "Este celular não tem lanterna."
                return if (status.setTorch(on)) {
                    if (on) "Lanterna ligada." else "Lanterna desligada."
                } else {
                    "A câmera está em uso, não consegui mexer na lanterna."
                }
            }

            n.contains("notifica") -> return readNotifications()
        }

        greetingCommand(n)?.let { return it }
        createEventCommand(raw, n)?.let { return it }
        notesCommand(raw, n)?.let { return it }
        mathCommand(raw, n)?.let { return it }
        navigationCommand(raw)?.let { return it }
        rideCommand(raw, n)?.let { return it }
        playSearchCommand(raw, n)?.let { return it }
        agendaQuery(n)?.let { return it }
        connectionCommand(n)?.let { return it }
        callCommand(n)?.let { return it }
        messageCommand(raw, n)?.let { return it }
        volumeCommand(n)?.let { return it }
        brightnessCommand(n)?.let { return it }
        ringerCommand(n)?.let { return it }
        timerCommand(n)?.let { return it }
        alarmCommand(n)?.let { return it }
        mediaCommand(n)?.let { return it }
        openAppCommand(n)?.let { return it }
        return null
    }

    // ----- Agenda -----

    private fun createEventCommand(raw: String, n: String): String? {
        val verb = Regex("^(ei )?(me )?(marca|marcar|agenda|agendar|cria|criar|adiciona|adicionar|anota|anotar|salva|salvar|guarda|guardar|coloca na agenda|colocar na agenda|lembra|lembrar|lembrete)\\b").find(n)
            ?: return null
        if (Regex("\\b(timer|temporizador|alarme|despertador|nota)\\b").containsMatchIn(n)) return null
        val parsed = WhenParser.parse(raw)
        // "anota comprar pão" (sem data) é nota, não compromisso.
        if (parsed == null && Regex("(anota|salva|guarda|lembra)").containsMatchIn(verb.value)) return null
        // "agenda de amanhã" é pergunta, não pedido para marcar.
        if (n.startsWith("agenda") && (parsed == null || parsed.title == "Compromisso")) return null
        if (parsed == null) return "Para quando? Diga por exemplo: marca dentista sexta às 10."
        val end = parsed.begin + 60 * 60_000L
        val whenText = describeWhen(parsed.begin, parsed.allDay)
        val id = calendar.insert(parsed.title, parsed.begin, end, parsed.allDay, reminderMinutes = if (parsed.relative) 0 else 10)
        if (id != null) return "Marquei ${parsed.title}, $whenText."
        // Sem permissão de escrita: abre o app de agenda já preenchido.
        return if (island.open(calendar.insertIntent(parsed.title, parsed.begin, end, parsed.allDay))) {
            "Abri a agenda com ${parsed.title}, $whenText. É só salvar."
        } else {
            "Não achei um app de agenda neste celular."
        }
    }

    private fun agendaQuery(n: String): String? {
        val asks = Regex("\\b(agenda|compromisso|compromissos|evento|eventos|reuniao|reunioes)\\b").containsMatchIn(n) ||
            Regex("\\b(o que|que) (eu )?tenho\\b").containsMatchIn(n)
        if (!asks) return null
        if (!calendar.canRead()) return "Preciso de acesso à agenda. Permita no app Ilha Assistente."
        return when {
            n.contains("proximo") || n.contains("proxima") -> {
                val e = calendar.upcoming(days = 30, limit = 1).firstOrNull() ?: return "Você não tem nada marcado nos próximos 30 dias."
                "Seu próximo compromisso é ${e.title}, ${describeWhen(e.begin, e.allDay)}."
            }
            n.contains("depois de amanha") -> listDay(2, "depois de amanhã")
            n.contains("amanha") -> listDay(1, "amanhã")
            n.contains("semana") -> {
                val list = calendar.upcoming(days = 7, limit = 6)
                if (list.isEmpty()) "Nada marcado nos próximos 7 dias." else
                    "Nos próximos dias: " + list.joinToString("; ") { "${it.title}, ${describeWhen(it.begin, it.allDay)}" } + "."
            }
            else -> {
                val list = calendar.today()
                if (list.isEmpty()) "Hoje você não tem mais nada marcado." else
                    "Hoje: " + list.joinToString("; ") { e -> e.title + if (e.allDay) " (dia todo)" else " às " + hm(e.begin) } + "."
            }
        }
    }

    private fun listDay(offset: Int, label: String): String {
        val list = calendar.onDay(offset)
        if (list.isEmpty()) return "Você não tem nada marcado $label."
        return "${label.replaceFirstChar { it.uppercase() }}: " +
            list.joinToString("; ") { e -> e.title + if (e.allDay) " (dia todo)" else " às " + hm(e.begin) } + "."
    }

    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", ptBR).format(Date(ms))

    private fun describeWhen(begin: Long, allDay: Boolean): String {
        val day = when (begin) {
            in CalendarRepo.startOfDay(0) until CalendarRepo.startOfDay(1) -> "hoje"
            in CalendarRepo.startOfDay(1) until CalendarRepo.startOfDay(2) -> "amanhã"
            in CalendarRepo.startOfDay(2) until CalendarRepo.startOfDay(7) -> SimpleDateFormat("EEEE", ptBR).format(Date(begin))
            else -> SimpleDateFormat("d 'de' MMMM", ptBR).format(Date(begin))
        }
        return if (allDay) day else "$day às ${hm(begin)}"
    }

    // ----- Ligações e mensagens -----

    private fun callCommand(n: String): String? {
        val m = Regex("^(liga|ligar|liga ai|faz uma ligacao|telefona|telefonar|chama|chamar) (pra|para|pro|pro a|a|o)? ?(.+)$").find(n) ?: return null
        val who = m.groupValues[3].removePrefix("o ").removePrefix("a ").trim()
        if (who.isEmpty() || who in setOf("wifi", "wi fi", "bluetooth", "internet", "dados")) return null
        if (!contacts.canRead()) return "Preciso de acesso aos contatos. Permita no app Ilha Assistente."
        val c = contacts.find(who) ?: return "Não achei $who nos seus contatos."
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number)))
        return if (island.open(intent)) "Ligando para ${c.name}. É só tocar no botão verde." else "Não consegui abrir o telefone."
    }

    private fun messageCommand(raw: String, n: String): String? {
        val m = Regex("^(manda|mandar|envia|enviar|escreve|escrever) (uma )?(mensagem|msg|zap|whats|whatsapp|sms)( no (whatsapp|zap|whats))? (pra|para|pro) (.+?)( (dizendo|falando|que|escrito|com o texto) (.+))?$").find(n)
            ?: return null
        val sms = m.groupValues[3] == "sms"
        val who = m.groupValues[7].removePrefix("o ").removePrefix("a ").trim()
        if (!contacts.canRead()) return "Preciso de acesso aos contatos. Permita no app Ilha Assistente."
        val c = contacts.find(who) ?: return "Não achei $who nos seus contatos."
        // Texto com acentos: pega do original depois da palavra-chave.
        val text = if (m.groupValues[10].isNotEmpty()) {
            Regex("(?iU)\\b(dizendo|falando|que|escrito|com o texto)\\s+(.+)$").find(raw)?.groupValues?.get(2)?.trim() ?: m.groupValues[10]
        } else {
            ""
        }
        val intent = if (sms) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", text)
        } else {
            val url = "https://api.whatsapp.com/send?phone=${Contacts.internationalDigits(c.number)}" +
                if (text.isNotEmpty()) "&text=" + Uri.encode(text) else ""
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        val app = if (sms) "SMS" else "WhatsApp"
        return if (island.open(intent)) {
            if (text.isNotEmpty()) "Abri o $app com a mensagem para ${c.name}. Confira e toque em enviar." else "Abri o $app na conversa com ${c.name}."
        } else {
            "Não consegui abrir o $app."
        }
    }

    // ----- Som, brilho, conexões -----

    private fun volumeCommand(n: String): String? {
        if (!n.contains("volume") && !Regex("^(mut[ae]|silencia|tira o som)\\b").containsMatchIn(n)) return null
        val current = status.volumePercent()
        val target = when {
            Regex("\\b(\\d{1,3})\\b").find(n) != null -> Regex("\\b(\\d{1,3})\\b").find(n)!!.groupValues[1].toInt()
            Regex("\\bmax(imo)?\\b").containsMatchIn(n) || n.contains("todo") -> 100
            n.contains("mut") || n.contains("zero") || n.contains("silencia") || n.contains("tira o som") -> 0
            n.contains("metade") || n.contains("meio") -> 50
            Regex("\\b(aument|sobe|subir|mais alto|mais)").containsMatchIn(n) -> current + 15
            Regex("\\b(abaix|diminu|baix|menos)").containsMatchIn(n) -> current - 15
            else -> return "O volume está em $current%."
        }.coerceIn(0, 100)
        status.setVolumePercent(target)
        return if (target == 0) "Som da mídia no mudo." else "Volume em $target%."
    }

    private fun brightnessCommand(n: String): String? {
        if (!n.contains("brilho") && !n.contains("tela mais clara") && !n.contains("tela mais escura")) return null
        val current = status.brightnessPercent()
        val target = when {
            Regex("\\b(\\d{1,3})\\b").find(n) != null -> Regex("\\b(\\d{1,3})\\b").find(n)!!.groupValues[1].toInt()
            Regex("\\bmax(imo)?\\b").containsMatchIn(n) || n.contains("todo") -> 100
            Regex("\\bmin(imo)?\\b").containsMatchIn(n) -> 5
            Regex("\\b(aument|sobe|subir|mais|clara)").containsMatchIn(n) -> current + 20
            Regex("\\b(abaix|diminu|baix|menos|escura)").containsMatchIn(n) -> current - 20
            else -> return "O brilho está em $current%."
        }.coerceIn(5, 100)
        if (!status.setBrightnessPercent(target)) {
            island.open(status.brightnessPermissionIntent())
            return "Preciso da sua permissão para mudar o brilho. Ative \"Ilha Assistente\" nessa tela e me peça de novo."
        }
        return "Brilho em $target%."
    }

    private fun ringerCommand(n: String): String? {
        val vibrate = Regex("\\b(modo vibra\\w*|vibracall|so vibrar|coloca no vibrar|no vibrar)\\b").containsMatchIn(n)
        val normal = Regex("\\b(modo normal|tira do vibrar|som normal|volta o som)\\b").containsMatchIn(n)
        if (!vibrate && !normal) return null
        return if (status.setVibrateMode(vibrate)) {
            if (vibrate) "Celular no modo vibrar." else "Som do celular normal."
        } else {
            "O Android não me deixou mudar isso agora."
        }
    }

    private fun connectionCommand(n: String): String? {
        val action = Regex("^(liga|ligar|desliga|desligar|ativa|ativar|desativa|desativar|abre|abrir|conecta|conectar)\\b").containsMatchIn(n)
        if (!action) return null
        return when {
            n.contains("wifi") || n.contains("wi fi") || n.contains("wireless") ->
                if (island.open(status.wifiPanelIntent())) "Abri o Wi-Fi para você." else null
            n.contains("bluetooth") ->
                if (island.open(status.bluetoothIntent())) "Abri o Bluetooth para você." else null
            n.contains("dados moveis") || n.contains("internet") || n.contains("dados") ->
                if (island.open(status.internetPanelIntent())) "Abri as opções de internet." else null
            else -> null
        }
    }

    // ----- Outros -----

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
        if (Regex("^(para|parar|pare|cancela|cancelar)\\b").containsMatchIn(n)) {
            val t = Timers.primary() ?: return "Não tem nenhum timer rodando."
            if (Timers.ringing != null) Timers.stopRinging() else Timers.stop(t)
            return "Timer parado."
        }
        var seconds = 0L
        Regex("(\\d+|um|uma|dois|duas|tres|meia)\\s*(segundo|minuto|hora)s?").findAll(n).forEach { m ->
            val amount = when (val word = m.groupValues[1]) {
                "um", "uma" -> 1L
                "dois", "duas" -> 2L
                "tres" -> 3L
                "meia" -> 30L
                else -> word.toLong()
            }
            seconds += when (m.groupValues[2]) {
                "segundo" -> amount
                "minuto" -> amount * 60
                else -> if (m.groupValues[1] == "meia") 30 * 60 else amount * 3600
            }
        }
        if (n.contains("e meia") && seconds in 60..3599) seconds += 30 // "2 minutos e meia" ~ "2 e meio"
        if (seconds <= 0) return "Quanto tempo? Diga por exemplo: timer de 5 minutos."
        val label = Regex("\\b(?:para|pra|pro) (?:o |a )?(.+)$").find(n)?.groupValues?.get(1)
            ?.replaceFirstChar { it.uppercase() } ?: "Timer de ${Timers.format(seconds * 1000)}"
        Timers.start(seconds, label)
        IslandHub.main.post { island.showTimers() }
        return "Timer de ${describeSeconds(seconds)} começando agora."
    }

    private fun describeSeconds(s: Long): String = when {
        s % 3600 == 0L -> "${s / 3600} hora" + if (s / 3600 > 1) "s" else ""
        s % 60 == 0L -> "${s / 60} minuto" + if (s / 60 > 1) "s" else ""
        s < 60 -> "$s segundos"
        else -> "${s / 60} minutos e ${s % 60} segundos"
    }

    // ----- Saudação -----

    private fun greetingCommand(n: String): String? {
        if (!Regex("^(oi|ola|ei|hey|e ai|bom dia|boa tarde|boa noite)( assistente| ${normalize(prefs.assistantName)})?$").matches(n)) return null
        return "Oi! Pode falar: marcar compromisso, anotar, timer, mensagem, rota... o que precisar."
    }

    // ----- Notas -----

    private fun notesCommand(raw: String, n: String): String? {
        val notes = island.notes
        if (Regex("^(abre|abrir|mostra|mostrar|ver) (o |as |meu |minhas )?(bloco de notas|notas)").containsMatchIn(n)) {
            IslandHub.main.post { island.showNotes() }
            return "Aqui estão suas notas."
        }
        if (Regex("^(minhas notas|quais (sao )?(as )?minhas notas|le (as )?minhas notas|ler (as )?(minhas )?notas)").containsMatchIn(n)) {
            val all = notes.all()
            if (all.isEmpty()) return "Você não tem notas ainda."
            return "Suas últimas notas: " + all.take(3).joinToString("; ") { it.text } + "."
        }
        val m = Regex("(?iU)^(?:ei\\s+)?(?:me\\s+)?(?:anota(?:r)?|anote|cria(?:r)?\\s+uma\\s+nota|salva(?:r)?\\s+(?:uma\\s+)?nota|guarda(?:r)?|nova\\s+nota|escreve(?:r)?\\s+(?:uma\\s+)?nota|bloco\\s+de\\s+notas)\\s*(?:a[ií]\\s+|que\\s+|:\\s*|dizendo\\s+)?(.+)$")
            .find(raw.trim()) ?: return null
        val text = m.groupValues[1].trim().trimEnd('.', '!').replaceFirstChar { it.uppercase() }
        if (text.isBlank()) return "O que eu anoto?"
        notes.add(text)
        IslandHub.main.post { island.showNotes() }
        return "Anotei: $text."
    }

    // ----- Conta rápida -----

    private fun mathCommand(raw: String, n: String): String? {
        if (!Regex("\\b(quanto|calcula|calcular|conta)\\b").containsMatchIn(n) && !Regex("^[\\d\\s.,+*/x%-]+$").matches(raw.trim())) return null
        val s = raw.lowercase(Locale.ROOT)
        fun num(t: String) = t.replace(".", "").replace(',', '.').toDouble()
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:%|por cento)\\s*de\\s*(\\d+(?:[.,]\\d+)?)").find(s)?.let {
            val r = num(it.groupValues[1]) / 100.0 * num(it.groupValues[2])
            return "${it.groupValues[1]}% de ${it.groupValues[2]} é ${fmt(r)}."
        }
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(mais|\\+|menos|-|vezes|x|\\*|multiplicado por|dividido por|/|÷)\\s*(\\d+(?:[.,]\\d+)?)").find(s)
            ?: return null
        val a = num(m.groupValues[1])
        val b = num(m.groupValues[3])
        val r = when (m.groupValues[2]) {
            "mais", "+" -> a + b
            "menos", "-" -> a - b
            "vezes", "x", "*", "multiplicado por" -> a * b
            else -> if (b == 0.0) return "Não dá para dividir por zero." else a / b
        }
        return "Dá ${fmt(r)}."
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) String.format(ptBR, "%,.0f", v) else String.format(ptBR, "%,.2f", v)

    // ----- Mapa, corrida e música -----

    private fun navigationCommand(raw: String): String? {
        val m = Regex("(?iU)^(?:me\\s+)?(?:leva|levar|leve|navega(?:r)?|navegue|vai|ir|rota|tra[çc]a(?:r)?\\s+(?:a\\s+|uma\\s+)?rota|como\\s+(?:eu\\s+)?chego)\\s+(?:para|pra|pro|at[ée]|ao|à|a|no|na|em)\\s+(.+)$")
            .find(raw.trim()) ?: return null
        val dest = m.groupValues[1].trim().trimEnd('.', '?', '!')
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
        if (island.open(nav)) return "Abrindo o caminho até $dest."
        return if (island.open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))) "Abri o mapa com $dest." else "Não achei um app de mapas."
    }

    private fun rideCommand(raw: String, n: String): String? {
        val m = Regex("^(chama|chamar|pede|pedir|solicita) (um |uma )?(uber|99|noventa e nove|taxi|carro)").find(n) ?: return null
        val dest = Regex("(?iU)\\b(?:para|pra|at[ée])\\s+(.+)$").find(raw.trim())?.groupValues?.get(1)?.trimEnd('.', '!')
        if (m.groupValues[3] == "99" || m.groupValues[3] == "noventa e nove") {
            val i = ctx.packageManager.getLaunchIntentForPackage("com.taxis99")
            return if (i != null && island.open(i)) "Abrindo o 99." else "Não achei o app 99 instalado."
        }
        val q = if (dest != null) "&dropoff[formatted_address]=" + Uri.encode(dest) else ""
        val uber = Intent(Intent.ACTION_VIEW, Uri.parse("uber://?action=setPickup&pickup=my_location$q"))
        if (island.open(uber)) return if (dest != null) "Abrindo o Uber para $dest." else "Abrindo o Uber."
        return if (island.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com/ul/?action=setPickup&pickup=my_location$q")))) "Abrindo o Uber." else "Não consegui abrir o Uber."
    }

    private fun playSearchCommand(raw: String, n: String): String? {
        val m = Regex("(?iU)^(?:toca|tocar|toque|coloca|bota|p[õo]e|ouvir|quero ouvir)\\s+(?:a\\s+)?(?:m[úu]sica\\s+|as\\s+m[úu]sicas\\s+(?:de|do|da)\\s+|uma\\s+m[úu]sica\\s+(?:de|do|da)\\s+)?(.+)$")
            .find(raw.trim()) ?: return null
        var q = m.groupValues[1].trim().trimEnd('.', '!')
        val qn = normalize(q)
        if (qn.isEmpty() || qn in setOf("musica", "a musica", "de novo", "ai", "o som")) return null
        var pkg: String? = null
        var web: String? = null
        when {
            qn.endsWith("no spotify") -> pkg = "com.spotify.music"
            qn.endsWith("no youtube music") -> pkg = "com.google.android.apps.youtube.music"
            qn.endsWith("no youtube") -> web = "https://www.youtube.com/results?search_query="
            qn.endsWith("no deezer") -> pkg = "deezer.android.app"
        }
        q = q.replace(Regex("(?iU)\\s+no\\s+(spotify|youtube music|youtube|deezer)$"), "").trim()
        if (web != null) {
            return if (island.open(Intent(Intent.ACTION_VIEW, Uri.parse(web + Uri.encode(q))))) "Procurando $q no YouTube." else "Não consegui abrir o YouTube."
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (pkg != null) intent.setPackage(pkg)
        return if (island.open(intent)) "Tocando $q." else "Não achei um app de música para tocar $q."
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
        if (query == "agenda" || query == "calendario") {
            return if (island.open(calendar.openCalendarIntent())) "Abrindo a agenda." else "Não achei um app de agenda."
        }
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        val best = apps
            .map { it to normalize(it.loadLabel(pm).toString()) }
            .filter { (_, label) -> label.isNotEmpty() && (label == query || label.contains(query) || query.contains(label)) }
            .minByOrNull { (_, label) -> if (label == query) 0 else label.length }
            ?: return "Não achei nenhum app chamado $query."
        val name = best.first.loadLabel(pm)
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
            ?: return "Não consegui abrir $name."
        return if (island.open(intent)) "Abrindo $name." else "Não consegui abrir $name."
    }

    private fun start(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val NO_MUSIC = "Não tem nenhum player de música aberto (ou falta o acesso às notificações)."
        private const val HELP =
            "Posso salvar compromissos (\"salva dia 29 eu vou viajar\"), anotar coisas, criar timers na ilha, " +
                "dizer sua agenda, ligar e mandar WhatsApp, traçar rotas, chamar Uber, tocar músicas, fazer contas, " +
                "mexer no volume, brilho, lanterna e modo vibrar, criar alarmes, abrir apps e ler notificações. " +
                "Com a chave do Claude, respondo qualquer pergunta e busco clima e notícias."

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9: ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
