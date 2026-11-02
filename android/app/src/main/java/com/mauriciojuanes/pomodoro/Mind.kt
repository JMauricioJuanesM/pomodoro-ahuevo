package com.mauriciojuanes.pomodoro

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * Frases para reforzar creencias que te sirven y desarmar las que no: se muestran en pantalla completa al
 * desbloquear el teléfono (cada `mind_unlock_every_min` minutos como mucho), en los descansos y después de
 * una urgencia o recaída.
 *
 * El mazo lo reescribe cada noche la IA a partir de tu diario, tus reacciones (👍/👎) y lo que ha pasado
 * (ver `server/nightly/`); mientras no haya, se usan las de [SEED].
 *
 * Cada frase: {"id", "text", "sub"?, "when": ["unlock","break","urge","relapse","morning","night","work"],
 * "weight"?, "score"?, "anim"?: "fade" | "breath" | "rise" | "pulse"}.
 */
object Mind {
    private val SEED = listOf(
        Triple("Lo que haces hoy decide quién eres mañana.", "Un pomodoro a la vez.", "unlock work"),
        Triple("No necesitas ganas. Necesitas empezar.", "Las ganas llegan a los 5 minutos.", "unlock work morning"),
        Triple("La urgencia es una ola: sube, llega al pico y baja.", "Respira. En 10 minutos ya pasó.", "urge breath"),
        Triple("Una caída no borra el camino.", "Registra qué pasó, aprende y sigue. Hoy cuenta.", "relapse"),
        Triple("Tu cerebro se está recableando.", "Cada urgencia que dejas pasar la hace más débil.", "urge unlock"),
        Triple("Eres el que decide, no el impulso.", "", "unlock urge"),
        Triple("Descansar también es parte del trabajo.", "Levántate, estírate, toma agua. Lejos de la pantalla.", "break"),
        Triple("Mira lejos. Respira hondo. Mueve el cuerpo.", "", "break breath"),
        Triple("Lo difícil se vuelve fácil con repeticiones.", "", "unlock work"),
        Triple("Dormir bien es el primer pomodoro de mañana.", "Deja el teléfono fuera del cuarto.", "night"),
        Triple("No estás atrasado. Estás construyendo.", "", "unlock morning"),
        Triple("Hoy no tienes que ser perfecto, solo constante.", "", "morning unlock"),
        Triple("El scroll no descansa la mente: la cansa.", "", "unlock break"),
        Triple("Merece la pena la persona en la que te estás convirtiendo.", "", "unlock night"),
        Triple("Termina lo que empezaste.", "Tu yo de mañana te lo va a agradecer.", "work"),
    )

    fun deck(): List<JSONObject> {
        val msgs = Store.mind.optJSONArray("messages")
        if (msgs != null && msgs.length() > 0) return (0 until msgs.length()).mapNotNull { msgs.optJSONObject(it) }
        return SEED.mapIndexed { i, (t, s, w) ->
            JSONObject().put("id", "seed$i").put("text", t).put("sub", s)
                .put("when", JSONArray(w.split(" ").filter { it != "breath" }))
                .put("anim", if ("breath" in w) "breath" else "fade")
        }
    }

    /** El momento en que estamos, para escoger frase. */
    fun moment(): String {
        val recent = Store.journal.let { j ->
            (0 until j.length()).mapNotNull { j.optJSONObject(it) }
                .filter { System.currentTimeMillis() / 1000.0 - it.optDouble("ts") < 3 * 3600 }
                .map { it.optString("type") }
        }
        val h = LocalTime.now().hour
        return when {
            "recaida" in recent -> "relapse"
            "urgencia" in recent -> "urge"
            Engine.timer.phase in setOf("short", "long", "sched") -> "break"
            h < 10 -> "morning"
            h >= 22 || h < 4 -> "night"
            Engine.onDuty() -> "work"
            else -> "unlock"
        }
    }

    /** Una frase para `moment`, al azar según peso y tus reacciones, sin repetir las últimas. */
    fun pick(moment: String = moment()): JSONObject? {
        val all = deck()
        val recent = Store.mindRecent
        fun fits(m: JSONObject) = m.optJSONArray("when")?.let { w -> (0 until w.length()).any { w.optString(it) == moment } } ?: true
        val pool = all.filter(::fits).ifEmpty { all.filter { fits(it) || moment == "unlock" } }.ifEmpty { all }
        val fresh = pool.filter { it.optString("id") !in recent }.ifEmpty { pool }
        if (fresh.isEmpty()) return null
        val weights = fresh.map { maxOf(0.2, it.optDouble("weight", 1.0) + 0.3 * it.optInt("score")) }
        var r = Math.random() * weights.sum()
        val chosen = fresh.zip(weights).firstOrNull { (_, w) -> r -= w; r <= 0 }?.first ?: fresh.last()
        Store.mindRecent = (recent + chosen.optString("id")).takeLast(6)
        return chosen
    }

    /** ¿Toca una frase al desbloquear? */
    fun dueOnUnlock(): Boolean {
        if (!Engine.cfg.optBoolean("mind_on_unlock", true)) return false
        val every = Store.mind.optDouble("unlock_every_min", Engine.cfg.optDouble("mind_unlock_every_min", 20.0))
        return System.currentTimeMillis() - Store.long("mind_last") > every * 60_000
    }

    fun shown() = Store.setLong("mind_last", System.currentTimeMillis())
}
