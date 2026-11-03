package com.mauriciojuanes.pomodoro

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONObject

/**
 * Trabajo lejos de la compu (la lógica vive en la compu: `pomodoro_app/controller/offpc.py`).
 *
 * Cuando el sensor de la compu ve que te fuiste en horario de trabajo, el temporizador trae
 * `offpc.state = "asking"`: aquí se pregunta «¿qué estás haciendo?» con el telón ([CurtainActivity], modo
 * `offpc`) o, con la pantalla apagada, con una notificación que lo abre. La respuesta (con los pasos que
 * contó el teléfono, ver [Steps]) va al hub y la IA de la compu decide. Si cuenta, llega `state = "active"`
 * con `where`:
 *  - `phone`: solo se valen las apps que hacen falta (`allow`, ver [Policy.at]);
 *  - `none`: lejos de las pantallas; si usas el teléfono en algo que no sea esencial ~1 min, se acaba el pase.
 * El veredicto (`offpc_last`) se avisa una vez.
 */
object Offpc {
    const val AWAY = "🚶 Me fui / descanso"
    const val DONE = "✅ Ya acabé"
    const val USED_MS = 60_000L   // uso del teléfono que acaba un pase «lejos de las pantallas»

    private var reportedFor = ""

    fun current(): JSONObject? = Engine.timer.offpc?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun asking(): JSONObject? = current()?.takeIf { it.optString("state") == "asking" && !it.optBoolean("pending") }

    /** El pase vigente, si lo hay (y la compu sigue viva: sin ella no hay sensor que lo respalde). */
    fun active(): JSONObject? = current()?.takeIf {
        it.optString("state") == "active" && it.optDouble("until", 0.0) > Engine.now() && Engine.desktopAlive
    }

    /** Llegó un temporizador nuevo de la compu (hilo principal). */
    fun onTimer(ctx: Context) {
        val o = current()
        val ask = asking()
        if (ask != null && ask.optString("id") != Store.string("offpc_asked")) {
            Store.setString("offpc_asked", ask.optString("id"))
            val on = ctx.getSystemService(PowerManager::class.java).isInteractive
            Notifier.alert(ctx, "🚶 ${ask.optString("question")}", "Si es trabajo lejos de la compu, cuenta. Toca para contestar.",
                open = PendingIntent.getActivity(ctx, 7, Intent(ctx, CurtainActivity::class.java).putExtra("mode", "offpc")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            if (on && Curtain.visible == null && !Curtain.inCall(ctx)) Curtain.show(ctx, "offpc")
        }
        Engine.timer.offpcLast?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { v ->
            if (v.optString("id") != Store.string("offpc_verdict")) {
                val first = Store.string("offpc_verdict").isEmpty()
                Store.setString("offpc_verdict", v.optString("id"))
                if (!first) Notifier.alert(ctx, if (v.optBoolean("ok")) "✅ Cuenta como trabajo" else "🚶 Fuera de la compu",
                    v.optString("msg"), vibrate = !v.optBoolean("ok"))
            }
        }
        if (o?.optString("state") != "active") reportedFor = ""
    }

    fun answer(text: String) {
        val o = current()
        val since = o?.optDouble("since", 0.0)?.takeIf { it > 0 } ?: Engine.now()
        val body = JSONObject().put("kind", "answer").put("text", text).put("source", "phone").put("ask", o?.optString("id"))
        Steps.since(since)?.let { body.put("steps", it) }
        Sync.post("/api/offpc", body)
    }

    fun finish() = Sync.post("/api/offpc", JSONObject().put("kind", "end").put("source", "phone"))

    /**
     * Desde la vigilancia: llevas `usedMs` en apps que no tocan durante un pase. En `none`, al minuto se acaba
     * (una sola vez por pase). Regresa true si lo acabó.
     */
    fun onPhoneUse(ctx: Context, usedMs: Long, appName: String): Boolean {
        val o = active() ?: return false
        if (o.optString("where") != "none" || usedMs < USED_MS || reportedFor == o.optString("id")) return false
        reportedFor = o.optString("id")
        Sync.post("/api/offpc", JSONObject().put("kind", "phone_used").put("text", appName).put("source", "phone"))
        Notifier.alert(ctx, "📵 Era lejos de las pantallas", "Usaste $appName: se acaba «${o.optString("what")}».")
        return true
    }

    fun allow(o: JSONObject): Set<String> {
        val a = o.optJSONArray("allow") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }
}
