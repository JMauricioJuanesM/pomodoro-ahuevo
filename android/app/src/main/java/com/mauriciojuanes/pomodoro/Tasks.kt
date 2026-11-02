package com.mauriciojuanes.pomodoro

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tareas de TickTick (espejo del hub) y sus recordatorios «tajantes»: cuando vence una tarea con hora, sale una
 * pantalla completa que no se quita sin decidir: ✅ hecha, 🎯 hacerla ahora (queda como tarea del pomodoro) o
 * ⏰ posponer (a la tercera hay que decir por qué). Las de todo el día que vencen hoy salen a las
 * `tasks_allday_hour` (11 por defecto). Lo mismo aparece en la compu; lo que decidas en uno vale en el otro.
 */
object Tasks {
    private val TT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val forgotten = mutableSetOf<String>()
    private val localSnooze = mutableMapOf<String, Long>()

    fun items(): List<JSONObject> {
        val arr = Store.tasks.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("id") !in forgotten }
    }

    fun due(t: JSONObject): OffsetDateTime? = t.optString("due").takeIf { it.isNotEmpty() && it != "null" }
        ?.let { runCatching { OffsetDateTime.parse(it, TT) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    /** Fecha local en que vence (las de todo el día vienen a medianoche de su zona). */
    fun dueDate(t: JSONObject): LocalDate? = due(t)?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDate()

    fun snoozeCount(id: String) = Store.tasks.optJSONObject("snoozes")?.optJSONObject(id)?.optInt("count") ?: 0

    private fun snoozedUntil(id: String): Long {
        val hub = ((Store.tasks.optJSONObject("snoozes")?.optJSONObject(id)?.optDouble("until") ?: 0.0) * 1000).toLong()
        return maxOf(hub, localSnooze[id] ?: 0L)
    }

    fun forget(id: String) {
        forgotten += id
        if (Store.currentTask?.optString("id") == id) Store.currentTask = null
    }

    fun snoozeLocal(id: String, minutes: Int) {
        localSnooze[id] = System.currentTimeMillis() + minutes * 60_000L
    }

    /** La tarea que toca recordar ya (o null). */
    fun dueNow(): JSONObject? {
        if (!Engine.cfg.optBoolean("tasks_firm", true)) return null
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val alldayAt = LocalTime.of(Engine.cfg.optInt("tasks_allday_hour", 11), 0)
        return items().filter { t ->
            val d = due(t) ?: return@filter false
            if (snoozedUntil(t.optString("id")) > now) return@filter false
            if (t.optBoolean("all_day")) dueDate(t) == today && LocalTime.now() >= alldayAt
            else d.toInstant().toEpochMilli() in (now - 3 * 86400_000L)..now
        }.minByOrNull { due(it)?.toEpochSecond() ?: 0 }
    }

    /** Lo que vence hoy o ya venció, para la pestaña de tareas. */
    fun today(): List<JSONObject> = items().filter { t -> dueDate(t)?.let { it <= LocalDate.now() } == true }
        .sortedBy { due(it)?.toEpochSecond() ?: 0 }

    fun whenText(t: JSONObject): String {
        val d = due(t)?.atZoneSameInstant(ZoneId.systemDefault()) ?: return "sin fecha"
        val day = when (d.toLocalDate()) {
            LocalDate.now() -> "hoy"
            LocalDate.now().plusDays(1) -> "mañana"
            LocalDate.now().minusDays(1) -> "ayer"
            else -> d.format(DateTimeFormatter.ofPattern("d MMM"))
        }
        return if (t.optBoolean("all_day")) day else "$day ${d.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
