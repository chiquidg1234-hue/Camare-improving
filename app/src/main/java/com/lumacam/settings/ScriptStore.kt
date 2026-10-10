package com.lumacam.settings

import android.content.Context
import com.lumacam.core.prompter.Teleprompter
import org.json.JSONArray
import org.json.JSONObject

/** Un guion del teleprompter. */
data class Script(val id: Long, val title: String, val text: String)

/** Guiones del modo PRESENTAR, guardados en el teléfono. */
class ScriptStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumacam_scripts", Context.MODE_PRIVATE)

    fun load(): List<Script> {
        val raw = prefs.getString(KEY, null) ?: return listOf(sample())
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Script(o.getLong("id"), o.optString("title"), o.optString("text"))
            }.ifEmpty { listOf(sample()) }
        } catch (_: Exception) {
            listOf(sample())
        }
    }

    fun save(scripts: List<Script>) {
        val arr = JSONArray()
        for (s in scripts) arr.put(JSONObject().put("id", s.id).put("title", s.title).put("text", s.text))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    var selectedId: Long
        get() = prefs.getLong(KEY_SELECTED, SAMPLE_ID)
        set(value) = prefs.edit().putLong(KEY_SELECTED, value).apply()

    private fun sample() = Script(SAMPLE_ID, "Cómo usar el teleprompter", Teleprompter.SAMPLE)

    companion object {
        private const val KEY = "scripts"
        private const val KEY_SELECTED = "selected"
        private const val SAMPLE_ID = 1L
    }
}
