package com.youfree.island

import java.util.Calendar

/**
 * Entende frases como "marca dentista sexta às 10h", "reunião amanhã às 3 da tarde",
 * "me lembra de pagar a conta dia 15", "tirar o bolo daqui a 40 minutos".
 */
object WhenParser {

    data class Result(
        val title: String,
        val begin: Long,
        val allDay: Boolean,
        /** "daqui a X minutos": avisar na hora, sem antecedência. */
        val relative: Boolean,
    )

    private val COMMAND = Regex(
        "(?U)^(?:ei\\s+)?(?:me\\s+)?(?:marca(?:r)?|agenda(?:r)?|cria(?:r)?|adiciona(?:r)?|anota(?:r)?|coloca(?:r)?|salva(?:r)?|guarda(?:r)?|" +
            "lembra(?:r)?(?:-me|\\s+me)?|lembrete)\\b\\s*(?:na\\s+(?:minha\\s+)?agenda\\s*)?" +
            "(?:(?:um|uma)\\s+)?(?:(?:evento|compromisso|lembrete)\\s+)?(?:(?:de|para|pra|que)\\s+)?",
    )

    private val RELATIVE = Regex("(?U)\\b(?:daqui\\s+a|em|dentro\\s+de)\\s+(\\d{1,3}|um|uma|meia)\\s+(minutos?|horas?)\\b")
    private val DAY_WORD = Regex("(?U)\\b(depois\\s+de\\s+amanh[ãa]|amanh[ãa]|hoje)\\b")
    private val WEEKDAY = Regex(
        "(?U)\\b(?:n[ao]\\s+|nest[ae]\\s+|ess[ae]\\s+)?(pr[óo]xim[ao]\\s+)?" +
            "(segunda|ter[çc]a|quarta|quinta|sexta|s[áa]bado|domingo)(?:[-\\s]feira)?\\b",
    )
    private val DAY_NUMBER = Regex("(?U)\\b(?:n?o\\s+)?dia\\s+(\\d{1,2})(?:\\s+de\\s+([a-zç]+))?\\b")
    private val DAY_SLASH = Regex("(?U)\\b(\\d{1,2})/(\\d{1,2})\\b")
    private val NOON = Regex("(?U)\\b(?:[àa]o?\\s+)?(meio[-\\s]dia|meia[-\\s]noite)\\b")
    private val TIME_H = Regex("(?U)\\b(?:[àa]s?\\s+|pelas?\\s+)?(\\d{1,2})\\s*(?:h|:|\\s+horas?)\\s*(?:e\\s+)?(\\d{2})?\\b")
    private val TIME_AS = Regex("(?U)\\b(?:[àa]s|pelas)\\s+(\\d{1,2})(?:\\s+e\\s+(\\d{1,2}))?\\b")
    private val PERIOD = Regex("(?U)\\b(?:da|de)\\s+(manh[ãa]|tarde|noite|madrugada)\\b")

    private val MONTHS = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    /** Retorna null se a frase não tiver dia nem horário. */
    fun parse(raw: String, now: Calendar = Calendar.getInstance()): Result? {
        // Procura em minúsculas, mas recorta também o texto original para o título manter as maiúsculas.
        val base = raw.replace(Regex("(?U)[,.!?]"), " ").replace(Regex("(?U)\\s+"), " ").trim()
        var s = base.lowercase()
        var orig = if (s.length == base.length) base else s
        fun cut(first: Int, endExclusive: Int) {
            s = s.removeRange(first, endExclusive)
            orig = orig.removeRange(first, endExclusive)
        }
        fun cut(range: IntRange) = cut(range.first, range.last + 1)
        COMMAND.find(s)?.let { cut(0, it.range.last + 1) }

        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // "daqui a 30 minutos"
        RELATIVE.find(s)?.let { m ->
            val amount = when (val w = m.groupValues[1]) {
                "um", "uma" -> 1
                "meia" -> 30
                else -> w.toInt()
            }
            val minutes = if (m.groupValues[2].startsWith("hora") && m.groupValues[1] != "meia") amount * 60 else amount
            cal.add(Calendar.MINUTE, minutes)
            cut(m.range)
            return Result(cleanTitle(orig), cal.timeInMillis, allDay = false, relative = true)
        }

        var hasDay = false
        var weekdayTarget = -1

        DAY_WORD.find(s)?.let { m ->
            hasDay = true
            val w = m.groupValues[1]
            when {
                w.startsWith("depois") -> cal.add(Calendar.DAY_OF_MONTH, 2)
                w.startsWith("amanh") -> cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            cut(m.range)
        }
        if (!hasDay) WEEKDAY.find(s)?.let { m ->
            hasDay = true
            weekdayTarget = when (Assistant.normalize(m.groupValues[2])) {
                "domingo" -> Calendar.SUNDAY
                "segunda" -> Calendar.MONDAY
                "terca" -> Calendar.TUESDAY
                "quarta" -> Calendar.WEDNESDAY
                "quinta" -> Calendar.THURSDAY
                "sexta" -> Calendar.FRIDAY
                else -> Calendar.SATURDAY
            }
            cut(m.range)
        }
        if (!hasDay) DAY_NUMBER.find(s)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = MONTHS.indexOf(Assistant.normalize(m.groupValues[2]))
            if (day in 1..31) {
                hasDay = true
                setDayMonth(cal, now, day, month)
                // "dia 15 de março": o nome do mês só sai do título se foi reconhecido.
                val end = if (month >= 0 || m.groupValues[2].isEmpty()) m.range.last + 1 else m.groups[1]!!.range.last + 1
                cut(m.range.first, end)
            }
        }
        if (!hasDay) DAY_SLASH.find(s)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt() - 1
            if (day in 1..31 && month in 0..11) {
                hasDay = true
                setDayMonth(cal, now, day, month)
                cut(m.range)
            }
        }

        // ----- horário -----
        var hour = -1
        var minute = 0
        NOON.find(s)?.let { m ->
            hour = if (m.groupValues[1].startsWith("meio")) 12 else 0
            cut(m.range)
        }
        if (hour < 0) (TIME_H.find(s) ?: TIME_AS.find(s))?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) {
                hour = h
                minute = min
                cut(m.range)
            }
        }
        if (hour >= 0) PERIOD.find(s)?.let { m ->
            val p = m.groupValues[1]
            if ((p == "tarde" || p == "noite") && hour in 1..11) hour += 12
            cut(m.range)
        }

        if (!hasDay && hour < 0) return null

        if (weekdayTarget >= 0) {
            var diff = (weekdayTarget - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0) {
                val later = hour >= 0 && (hour > now.get(Calendar.HOUR_OF_DAY) ||
                    (hour == now.get(Calendar.HOUR_OF_DAY) && minute > now.get(Calendar.MINUTE)))
                if (!later) diff = 7
            }
            cal.add(Calendar.DAY_OF_MONTH, diff)
        }

        val allDay = hour < 0
        if (allDay) {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
        } else {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            // Só horário e já passou hoje: é amanhã.
            if (!hasDay && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return Result(cleanTitle(orig), cal.timeInMillis, allDay, relative = false)
    }

    private fun setDayMonth(cal: Calendar, now: Calendar, day: Int, month: Int) {
        if (month >= 0) {
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (cal.before(startOfDay(now))) cal.add(Calendar.YEAR, 1)
        } else {
            if (day < now.get(Calendar.DAY_OF_MONTH)) cal.add(Calendar.MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
    }

    private fun startOfDay(now: Calendar): Calendar = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun cleanTitle(rest: String): String {
        var t = rest.replace(Regex("(?U)\\s+"), " ").trim()
        val edge = Regex("(?U)^(?:de|da|do|para|pra|que|às|as|a|o|e|em|no|na)\\s+|\\s+(?:de|da|do|para|pra|às|as|a|o|e|em|no|na)$")
        repeat(4) { t = t.replace(edge, "").trim() }
        // "eu vou viajar" -> "Viajar"; "que eu tenho dentista" -> "Dentista"
        repeat(2) {
            t = t.replace(Regex("(?iU)^(?:que\\s+)?(?:eu\\s+)?(?:vou|vamos|tenho\\s+que|tenho|preciso|devo)\\s+"), "").trim()
        }
        if (t.isEmpty()) return "Compromisso"
        return t.replaceFirstChar { it.uppercase() }
    }
}
