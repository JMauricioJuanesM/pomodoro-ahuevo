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
    // Sensor de presencia de la compu: el pomodoro espera a que empieces a trabajar (no corre aún) y
    // cuánta presión lleva por la tardanza (0–3).
    val arming: Boolean = false,
    val nudge: Int = 0,
    val armingSince: Double? = null,
    // Trabajo lejos de la compu ([Offpc]): la pregunta o el pase vigente y el último veredicto (JSON, de la compu).
    val offpc: String? = null,
    val offpcLast: String? = null,
) {
    fun remainingAt(now: Double): Double = if (running) (endTime ?: now) - now else remaining ?: total

    fun toJson(): JSONObject = JSONObject()
        .put("phase", phase).put("title", title).put("color", color).put("running", running)
        .put("end_time", endTime ?: JSONObject.NULL).put("remaining", remaining ?: JSONObject.NULL)
        .put("total", total).put("done_in_cycle", doneInCycle).put("overtime", overtime)
        .put("skippable", skippable).put("shutdown", false)
        .put("arming", arming).put("nudge", nudge).put("arming_since", armingSince ?: JSONObject.NULL)

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
            arming = o.optBoolean("arming"),
            nudge = o.optInt("nudge"),
            armingSince = if (o.isNull("arming_since")) null else o.optDouble("arming_since"),
            offpc = o.optJSONObject("offpc")?.toString(),
            offpcLast = o.optJSONObject("offpc_last")?.toString(),
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

    /** Bloqueos del Panel ADB (ver [Blocks]). */
    var blocks: JSONObject
        get() = runCatching { JSONObject(str("blocks", "{}")) }.getOrDefault(JSONObject())
        set(v) = put("blocks", v.toString())

    /** Cuándo vio el teléfono cada desbloqueo (para el tope de 15 min del DNS y del bloqueo de Xiaomi). */
    var relockSeen: JSONObject
        get() = runCatching { JSONObject(str("relock_seen", "{}")) }.getOrDefault(JSONObject())
        set(v) = put("relock_seen", v.toString())

    /** Desde cuándo estás en una app del banco con la vigilancia apagada (0 = no). Ver [BankMode]. */
    var bankSince: Long
        get() = p.getLong("bank_since", 0L)
        set(v) = p.edit().putLong("bank_since", v).apply()

    /** Advertencias por contenido sexual, por app: paquete → [cuándo]. Ver [Strikes]. */
    var strikes: JSONObject
        get() = runCatching { JSONObject(str("strikes", "{}")) }.getOrDefault(JSONObject())
        set(v) = put("strikes", v.toString())

    /** Veces que hoy pausaste, reiniciaste o te fuiste a descanso en horario de trabajo (ver [Engine.frictionNeeded]). */
    var skipsToday: Int
        get() = if (str("skips_day") == today()) p.getInt("skips", 0) else 0
        set(v) = p.edit().putString("skips_day", today()).putInt("skips", v).apply()

    var blocksRevSeen: Int
        get() = p.getInt("blocks_rev_seen", 0)
        set(v) = p.edit().putInt("blocks_rev_seen", v).apply()

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

    // ---------- Faro: lo que llega del hub (ver [Hub]) ----------
    private fun obj(key: String) = runCatching { JSONObject(str(key, "{}")) }.getOrDefault(JSONObject())
    private fun arr(key: String) = runCatching { JSONArray(str(key, "[]")) }.getOrDefault(JSONArray())

    /** Actividad → {"allow": [...], "why": "..."} que dedujo la IA (ver [Policy]). */
    var policies: JSONObject
        get() = obj("policies")
        set(v) = put("policies", v.toString())
    private var aiCatCache: JSONObject? = null
    var aiCategories: JSONObject
        get() = aiCatCache ?: obj("ai_categories").also { aiCatCache = it }
        set(v) {
            aiCatCache = v
            put("ai_categories", v.toString())
        }
    var aiCategoriesRev: Int
        get() = p.getInt("ai_categories_rev", 0)
        set(v) = p.edit().putInt("ai_categories_rev", v).apply()
    var asks: JSONArray
        get() = arr("asks")
        set(v) = put("asks", v.toString())
    var answeredAsks: Set<String>
        get() = p.getStringSet("answered_asks", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("answered_asks", v.toList().takeLast(50).toSet()).apply()
    var currentTask: JSONObject?
        get() = str("current_task").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = put("current_task", v?.toString() ?: "")
    var inboxUnread: Int
        get() = p.getInt("inbox_unread", 0)
        set(v) = p.edit().putInt("inbox_unread", v).apply()
    var mind: JSONObject
        get() = obj("mind")
        set(v) = put("mind", v.toString())
    var tasks: JSONObject
        get() = obj("tasks")
        set(v) = put("tasks", v.toString())
    var journal: JSONArray
        get() = arr("journal")
        set(v) = put("journal", v.toString())
    var inbox: JSONArray
        get() = arr("inbox")
        set(v) = put("inbox", v.toString())
    /** Peticiones al hub que no se han podido mandar (ver [Sync]). */
    var outbox: JSONObject
        get() = obj("outbox")
        set(v) = put("outbox", v.toString())
    var appsReported: String
        get() = str("apps_reported")
        set(v) = put("apps_reported", v)

    /** Últimas frases mostradas (para no repetirlas, ver [Mind]). */
    var mindRecent: List<String>
        get() = str("mind_recent").split('|').filter { it.isNotEmpty() }
        set(v) = put("mind_recent", v.joinToString("|"))

    fun revSeen(key: String) = p.getInt("rev_$key", -1)
    fun setRevSeen(key: String, v: Int) = p.edit().putInt("rev_$key", v).apply()

    fun string(key: String) = str(key)
    fun setString(key: String, v: String) = put(key, v)

    fun long(key: String) = p.getLong(key, 0L)
    fun setLong(key: String, v: Long) = p.edit().putLong(key, v).apply()

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
