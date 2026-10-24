package com.mauriciojuanes.pomodoro

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class Block(val aid: String?, val start: LocalDateTime, val end: LocalDateTime)

/** Consultas sobre el horario semanal (misma lógica que `core/schedule.py` de la compu). */
class Sched(private val cfg: JSONObject) {
    // En el horario 0 = lunes … 6 = domingo, como `weekday()` de Python.
    private fun day(d: LocalDate): JSONObject? =
        cfg.optJSONObject("schedule")?.optJSONObject((d.dayOfWeek.value - 1).toString())

    fun cell(t: LocalDateTime): String? =
        day(t.toLocalDate())?.optString(t.hour.toString(), "")?.takeIf { it.isNotEmpty() && it != "null" }

    fun activity(aid: String?): JSONObject? = aid?.let { cfg.optJSONObject("activities")?.optJSONObject(it) }

    fun kind(aid: String?): String = activity(aid)?.optString("kind", "libre") ?: "libre"

    fun label(aid: String?): String = activity(aid)?.optString("label", "—") ?: "—"

    fun color(aid: String?): String = activity(aid)?.optString("color", "#444444") ?: "#444444"

    /** (inicio, fin) del trabajo de ese día según el horario, o null si no hay trabajo. */
    fun bounds(date: LocalDate): Pair<LocalDateTime, LocalDateTime>? {
        val d = day(date) ?: return null
        val hours = d.keys().asSequence().filter { isWork(kind(d.optString(it))) }.map { it.toInt() }.toList()
        if (hours.isEmpty()) return null
        val base = date.atStartOfDay()
        return base.plusHours(hours.min().toLong()) to base.plusHours(hours.max().toLong() + 1)
    }

    /** Bloque continuo de la misma actividad que contiene `t`. */
    fun blockAt(t: LocalDateTime): Block {
        val aid = cell(t)
        val hour0 = t.truncatedTo(ChronoUnit.HOURS)
        var start = hour0
        var end = hour0.plusHours(1)
        while (start.hour > 0 && cell(start.minusHours(1)) == aid) start = start.minusHours(1)
        while (end.toLocalDate() == hour0.toLocalDate() && cell(end) == aid && end.hour != 0) end = end.plusHours(1)
        return Block(aid, start, end)
    }

    /** (actividad, inicio) de lo siguiente que toca. */
    fun nextChange(t: LocalDateTime): Pair<String, LocalDateTime>? {
        val b = blockAt(t)
        var end = b.end
        repeat(24 * 7) {
            val nxt = cell(end)
            if (nxt != null && nxt != b.aid) return nxt to end
            end = end.plusHours(1)
        }
        return null
    }

    companion object {
        fun isWork(kind: String) = kind == "trabajo" || kind == "enfocado"
    }
}
