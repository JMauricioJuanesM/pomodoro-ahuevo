package com.mauriciojuanes.pomodoro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Qué se vale en la actividad del horario que toca ahora.
 *
 * Cada actividad dice dónde aplica (`lock`: "both" | "pc" | "phone" | "none") y qué tipos de app se permiten
 * en el teléfono durante el trabajo (`allow`, ver [Apps]). Si no lo dices tú, se usa lo que dedujo la IA del
 * ciclo nocturno (`policies` del hub) y, si tampoco hay, se deduce del nombre y la intención de la actividad
 * («👤 trámites» → banco, compras, mapas; «🎓» → estudio y lectura…).
 *
 * Banco, mapas y lo esencial nunca se bloquean por trabajo ([Apps.ALWAYS]).
 */
object Policy {
    data class P(
        val aid: String?,
        val label: String,
        val kind: String,
        val pc: Boolean,
        val phone: Boolean,
        val allow: Set<String>,
        val source: String,   // "tú" | "IA" | "nombre"
    ) {
        /** ¿WhatsApp sin tope en esta actividad? */
        val chatFree get() = Apps.MESSAGING in allow
    }

    private val RULES = listOf(
        Regex("banc|pago|mandad|trámite|tramite|finanz|personal|👤|compra|super|🛒") to
            setOf(Apps.FINANCE, Apps.SHOPPING, Apps.MAPS, Apps.WORK, Apps.MESSAGING),
        Regex("estudi|🎓|curso|clase|tarea|examen|📚") to setOf(Apps.EDUCATION, Apps.READING, Apps.WORK),
        Regex("leer|lectura|📖|libro") to setOf(Apps.READING),
        Regex("chat|💬|reuni|daily|junta|llamada|meet|📞") to setOf(Apps.MESSAGING, Apps.WORK),
        Regex("run|walk|gym|ejercicio|correr|caminar|🏃|🚶|🧘") to setOf(Apps.AUDIO, Apps.MAPS),
        Regex("proyecto|⚙️|work|trabajo|💻") to setOf(Apps.WORK),
    )

    fun lockOf(a: JSONObject?): String = a?.optString("lock", "both")?.takeIf { it.isNotEmpty() } ?: "both"

    fun at(t: LocalDateTime = LocalDateTime.now()): P {
        val s = Engine.sched
        val aid = s.cell(t)
        val a = s.activity(aid)
        val lock = lockOf(a)
        val (allow, source) = allowFor(aid, a)
        return P(aid, s.label(aid), s.kind(aid), lock == "both" || lock == "pc", lock == "both" || lock == "phone",
            allow, source)
    }

    fun allowFor(aid: String?, a: JSONObject?): Pair<Set<String>, String> {
        a?.optJSONArray("allow")?.let { return it.toSet() to "tú" }
        Store.policies.optJSONObject(aid ?: "")?.optJSONArray("allow")?.let {
            return it.toSet() to "IA"
        }
        return infer(a) to "nombre"
    }

    fun infer(a: JSONObject?): Set<String> {
        val text = ((a?.optString("label") ?: "") + " " + (a?.optString("intent") ?: "")).lowercase()
        return RULES.filter { it.first.containsMatchIn(text) }.flatMap { it.second }.toSet()
    }

    /** ¿Se puede usar `pkg` ahora sin que cuente como estar en el teléfono? */
    fun allowed(ctx: Context, pkg: String, p: P = at()): Boolean {
        val cat = Apps.category(ctx, pkg)
        if (cat in Apps.ALWAYS || cat in p.allow) return cat != Apps.MESSAGING || p.chatFree
        val extra = Engine.cfg.optJSONArray("phone_essential")
        return extra != null && (0 until extra.length()).any { extra.optString(it) == pkg }
    }

    /** ¿Distrae en esta actividad? (redes, video, juegos y la lista de apps que distraen, salvo que se permitan) */
    fun distracting(ctx: Context, pkg: String, p: P = at()): Boolean {
        val cat = Apps.category(ctx, pkg)
        if (cat in p.allow || cat in Apps.ALWAYS) return false
        val list = Engine.cfg.optJSONArray("phone_distracting")
        if (list != null && (0 until list.length()).any { list.optString(it) == pkg }) return true
        return cat in Apps.DISTRACTING
    }

    private fun JSONArray.toSet(): Set<String> = (0 until length()).map { optString(it) }.toSet()
}
