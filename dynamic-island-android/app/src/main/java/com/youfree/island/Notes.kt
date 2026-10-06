package com.youfree.island

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Note(val text: String, val time: Long)

/** Bloco de notas da assistente ("anota comprar pão"), guardado só no celular. */
class Notes(context: Context) {
    private val sp = context.getSharedPreferences("island_notes", Context.MODE_PRIVATE)

    fun all(): List<Note> {
        val arr = try {
            JSONArray(sp.getString("notes", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Note(o.optString("text"), o.optLong("time"))
        }
    }

    fun add(text: String) {
        val list = listOf(Note(text, System.currentTimeMillis())) + all()
        save(list.take(100))
    }

    fun remove(note: Note) = save(all().filterNot { it == note })

    fun clear() = save(emptyList())

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("text", it.text).put("time", it.time)) }
        sp.edit().putString("notes", arr.toString()).apply()
    }
}
