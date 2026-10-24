package com.mauriciojuanes.pomodoro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Estado del temporizador tal como se comparte con la compu (mismo formato que `timer_snapshot`). */
data class Timer(
    val phase: String = "work",
    val title: String = "Trabajo",
    val color: String = "#c0392b",
    val running: Boolean = false,
    val endTime: Double? = null,      // epoch en segundos, si corre
    val remaining: Double? = 1500.0,  // segundos, si está en pausa
    val total: Double = 1500.0,
    val doneInCycle: Int = 0,
    val overtime: Boolean = false,
    val skippable: Boolean = false,
) {
    fun remainingAt(now: Double): Double = if (running) (endTime ?: now) - now else remaining ?: total

    fun toJson(): JSONObject = JSONObject()
        .put("phase", phase).put("title", title).put("color", color).put("running", running)
        .put("end_time", endTime ?: JSONObject.NULL).put("remaining", remaining ?: JSONObject.NULL)
        .put("total", total).put("done_in_cycle", doneInCycle).put("overtime", overtime)
        .put("skippable", skippable).put("shutdown", false)

    companion object {
        fun fromJson(o: JSONObject) = Timer(
            phase = o.optString("phase", "work"),
            title = o.optString("title", "Trabajo"),
            color = o.optString("color", "#c0392b"),
            running = o.optBoolean("running"),
            endTime = if (o.isNull("end_time")) null else o.optDouble("end_time"),
            remaining = if (o.isNull("remaining")) null else o.optDouble("remaining"),
            total = o.optDouble("total", 1500.0),
            doneInCycle = o.optInt("done_in_cycle"),
            overtime = o.optBoolean("overtime"),
            skippable = o.optBoolean("skippable"),
        )
    }
}

/** Todo lo que la app recuerda (SharedPreferences). La configuración es la última que mandó la compu. */
object Store {
    private lateinit var p: SharedPreferences
    private var configCache: JSONObject? = null

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String = "") = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()

    var url: String
        get() = str("url", "https://pomodoro.mauriciojuanes.com")
        set(v) = put("url", v.trim().trimEnd('/'))
    var token: String
        get() = str("token")
        set(v) = put("token", v.trim())

    var config: JSONObject
        get() = configCache ?: JSONObject(str("config", "{}")).also { configCache = it }
        set(v) {
            configCache = v
            put("config", v.toString())
        }

    var timer: Timer
        get() = runCatching { Timer.fromJson(JSONObject(str("timer", "{}"))) }.getOrDefault(Timer())
        set(v) = put("timer", v.toJson().toString())

    var timerRevSeen: Int
        get() = p.getInt("timer_rev_seen", 0)
        set(v) = p.edit().putInt("timer_rev_seen", v).apply()

    var autoDate: String
        get() = str("auto_date")
        set(v) = put("auto_date", v)
    var endDayDate: String
        get() = str("end_day_date")
        set(v) = put("end_day_date", v)
    var schedKey: String
        get() = str("sched_key")
        set(v) = put("sched_key", v)
    var dndByUs: Boolean
        get() = p.getBoolean("dnd_by_us", false)
        set(v) = p.edit().putBoolean("dnd_by_us", v).apply()

    // ---------- estadísticas por día (pomodoros y segundos de teléfono trabajando) ----------
    private fun dayMap(key: String) = JSONObject(str(key, "{}"))

    fun count(key: String, day: String = today()): Double = dayMap(key).optDouble(day, 0.0)

    fun setCount(key: String, v: Double, day: String = today()) {
        val m = dayMap(key)
        m.put(day, v)
        // Solo guardamos las últimas semanas.
        val old = LocalDate.now().minusDays(60).toString()
        m.keys().asSequence().filter { it < old }.toList().forEach { m.remove(it) }
        put(key, m.toString())
    }

    fun mergeMax(key: String, remote: JSONObject?) {
        val v = remote?.optDouble(today(), 0.0) ?: 0.0
        if (v > count(key)) setCount(key, v)
    }

    fun todayMap(key: String) = JSONObject().put(today(), count(key))

    // ---------- recordatorios ya disparados hoy ----------
    fun fired(): MutableSet<String> {
        val arr = JSONArray(str("fired", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }.filter { today() in it }.toMutableSet()
    }

    fun setFired(s: Set<String>) = put("fired", JSONArray(s.toList()).toString())

    fun today(): String = LocalDate.now().toString()
}
