package com.youfree.island

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

data class CalEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String,
)

/** Lê e cria compromissos na agenda do celular (Google Agenda, Samsung Calendar...). */
class CalendarRepo(private val ctx: Context) {

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Compromissos que acontecem entre [from] e [to] (ms), em ordem. */
    fun between(from: Long, to: Long, limit: Int = 20): List<CalEvent> {
        if (!canRead()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = ArrayList<CalEvent>()
        try {
            ctx.contentResolver.query(
                uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val allDay = c.getInt(4) == 1
                    var begin = c.getLong(2)
                    var end = c.getLong(3)
                    if (allDay) {
                        // Eventos de dia inteiro vêm em UTC: converte para o fuso local.
                        begin = utcDayToLocal(begin)
                        end = utcDayToLocal(end)
                    }
                    out.add(
                        CalEvent(
                            id = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(sem título)",
                            begin = begin,
                            end = end,
                            allDay = allDay,
                            color = c.getInt(5),
                            location = c.getString(6).orEmpty(),
                        ),
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out.sortedBy { it.begin }
    }

    fun today(): List<CalEvent> {
        val start = startOfDay(0)
        return between(start, startOfDay(1)).filter { it.end > System.currentTimeMillis() || it.allDay }
    }

    fun onDay(offsetDays: Int): List<CalEvent> = between(startOfDay(offsetDays), startOfDay(offsetDays + 1))

    fun upcoming(days: Int = 7, limit: Int = 8): List<CalEvent> {
        val now = System.currentTimeMillis()
        return between(now, now + days * DAY, limit).filter { it.end > now }
    }

    /**
     * Cria o compromisso direto na agenda principal. Retorna o id, ou null se não tiver
     * permissão / agenda gravável (aí use [insertIntent]).
     */
    fun insert(title: String, begin: Long, end: Long, allDay: Boolean, reminderMinutes: Int = 10): Long? {
        if (!canWrite()) return null
        val calendarId = writableCalendarId() ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.DTSTART, localDayToUtc(begin))
                put(CalendarContract.Events.DTEND, localDayToUtc(begin) + DAY)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
            val eventId = ContentUris.parseId(uri)
            if (!allDay) {
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
            }
            eventId
        } catch (_: Exception) {
            null
        }
    }

    /** Abre o app de agenda já preenchido para a pessoa confirmar. */
    fun insertIntent(title: String, begin: Long, end: Long, allDay: Boolean): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun newEventIntent(): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun viewIntent(event: CalEvent): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openCalendarIntent(): Intent {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also {
            ContentUris.appendId(it, System.currentTimeMillis())
        }.build()
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun writableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000

        fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, offsetDays)
        }.timeInMillis

        private fun utcDayToLocal(utcMs: Long): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
            return Calendar.getInstance().apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }

        private fun localDayToUtc(localMs: Long): Long {
            val local = Calendar.getInstance().apply { timeInMillis = localMs }
            return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
    }
}
