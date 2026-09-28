package com.caughtlackin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SquadMember(val name: String, val phone: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("caughtlackin", Context.MODE_PRIVATE)

    var squad: List<SquadMember>
        get() {
            val arr = JSONArray(sp.getString(KEY_SQUAD, "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SquadMember(o.getString("name"), o.getString("phone"))
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            sp.edit().putString(KEY_SQUAD, arr.toString()).apply()
        }

    /** One message per entry. "{reason}" is replaced with the strike reason. */
    var messages: List<String>
        get() = sp.getString(KEY_MESSAGES, null)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_MESSAGES
        set(value) = sp.edit().putString(KEY_MESSAGES, value.joinToString("\n")).apply()

    /** true: locking the screen ends the session. false: it counts as a strike. */
    var lockEndsSession: Boolean
        get() = sp.getBoolean(KEY_LOCK_ENDS, true)
        set(value) = sp.edit().putBoolean(KEY_LOCK_ENDS, value).apply()

    var calibrationMode: Boolean
        get() = sp.getBoolean(KEY_CALIBRATION, false)
        set(value) = sp.edit().putBoolean(KEY_CALIBRATION, value).apply()

    var lastTextAt: Long
        get() = sp.getLong(KEY_LAST_TEXT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_TEXT, value).apply()

    companion object {
        const val MIN_SQUAD = 3
        const val MAX_SQUAD = 5

        private const val KEY_SQUAD = "squad"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_LOCK_ENDS = "lock_ends_session"
        private const val KEY_CALIBRATION = "calibration_mode"
        private const val KEY_LAST_TEXT = "last_text_at"

        val DEFAULT_MESSAGES = listOf(
            "Automated shame alert: I was supposed to be studying but I {reason}. Please bully me back to my desk.",
            "I have been caught lackin: I {reason}. I owe you a snack for witnessing this.",
            "My study app says I {reason}. I am a disappointment to my textbooks.",
            "Confession: instead of studying I {reason}. Reply with one (1) motivational insult.",
            "This is my phone snitching on me. I {reason} during study time. Hold me accountable.",
        )
    }
}
