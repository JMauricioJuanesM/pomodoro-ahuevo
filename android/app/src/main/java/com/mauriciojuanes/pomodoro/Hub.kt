package com.mauriciojuanes.pomodoro

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Lo de Faro que vive en el hub además del pomodoro: diario, buzón, frases ([Mind]), tareas de TickTick
 * ([Tasks]), preguntas, políticas por actividad y categorías de apps.
 *
 * El long-poll solo trae los `*_rev`; cuando uno cambia, aquí se pide lo nuevo en un hilo aparte y se guarda
 * en [Store] para que todo funcione igual sin red. Lo que tú registras se encola en [Sync] (con reintentos).
 */
object Hub {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val seen = mutableMapOf<String, Int>()

    /** Llegó un estado del servidor (hilo principal). */
    fun onRemote(ctx: Context, st: JSONObject) {
        st.optJSONObject("policies")?.let { Store.policies = it }
        Store.asks = st.optJSONArray("asks") ?: JSONArray()
        Store.currentTask = st.optJSONObject("current_task")
        Store.inboxUnread = st.optInt("inbox_unread")
        st.optJSONObject("release")?.let { Updater.onRelease(ctx, it) }
        refreshIfChanged(st, "mind", "/api/mind") { Store.mind = it.optJSONObject("mind") ?: JSONObject() }
        refreshIfChanged(st, "tasks", "/api/tasks") { Store.tasks = it }
        refreshIfChanged(st, "journal", "/api/journal?limit=400") { Store.journal = it.optJSONArray("items") ?: JSONArray() }
        refreshIfChanged(st, "inbox", "/api/inbox?limit=60") { Store.inbox = it.optJSONArray("items") ?: JSONArray() }
        refreshIfChanged(st, "app_categories", "/api/apps/categories") {
            Store.aiCategories = it.optJSONObject("categories") ?: JSONObject()
            Store.aiCategoriesRev = it.optInt("rev")
        }
    }

    private fun refreshIfChanged(st: JSONObject, key: String, path: String, save: (JSONObject) -> Unit) {
        val rev = st.optInt(key + "_rev")
        if (rev == 0 || seen[key] == rev && Store.revSeen(key) == rev) return
        seen[key] = rev
        io.execute {
            runCatching { Sync.get(path) }.onSuccess {
                main.post {
                    save(it)
                    Store.setRevSeen(key, rev)
                    Engine.notifyChanged()
                }
            }.onFailure { seen.remove(key) }
        }
    }

    /** Pide algo al hub en segundo plano y entrega la respuesta en el hilo principal. */
    fun fetch(path: String, done: (JSONObject?) -> Unit) = io.execute {
        val r = runCatching { Sync.get(path) }.getOrNull()
        main.post { done(r) }
    }

    // ---------- lo que registras tú (se encola con reintentos) ----------

    fun journal(type: String, text: String, trigger: String? = null, auto: Boolean = false, level: Int? = null) {
        val body = JSONObject().put("type", type).put("text", text).put("source", "phone").put("auto", auto)
            .put("ts", System.currentTimeMillis() / 1000.0).put("ext_id", "phone-${System.nanoTime()}")
        if (trigger != null) body.put("trigger", trigger)
        if (level != null) body.put("level", level)
        // Se ve al instante en la app aunque no haya red.
        Store.journal = JSONArray(Store.journal.toString()).put(body)
        Sync.post("/api/journal", body)
        Engine.notifyChanged()
    }

    fun answer(askId: String, text: String) {
        Store.answeredAsks = Store.answeredAsks + askId
        Sync.post("/api/asks/$askId/answer", JSONObject().put("text", text).put("source", "phone"))
    }

    fun react(mindId: String, value: Int) =
        Sync.post("/api/mind/react", JSONObject().put("id", mindId).put("value", value).put("source", "phone"))

    fun readInbox(id: String) {
        val arr = Store.inbox
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("id") == id) it.put("read", true) }
        Store.inbox = arr
        Store.inboxUnread = maxOf(0, Store.inboxUnread - 1)
        Sync.post("/api/inbox/$id/read", JSONObject())
    }

    fun completeTask(t: JSONObject) {
        Tasks.forget(t.optString("id"))
        Sync.post("/api/tasks/${t.optString("id")}/complete",
            JSONObject().put("project_id", t.optString("project_id")).put("source", "phone"))
    }

    fun snoozeTask(t: JSONObject, minutes: Int, reason: String, counts: Boolean = true) {
        Tasks.snoozeLocal(t.optString("id"), minutes)
        Sync.post("/api/tasks/${t.optString("id")}/snooze", JSONObject().put("minutes", minutes).put("reason", reason)
            .put("count", counts).put("source", "phone"))
    }

    fun focusTask(t: JSONObject?) {
        Store.currentTask = t?.let { JSONObject().put("id", it.optString("id")).put("title", it.optString("title")) }
        Sync.put("/api/tasks/current", JSONObject().put("task", Store.currentTask ?: JSONObject.NULL))
        Engine.notifyChanged()
    }

    fun createTask(title: String) = Sync.post("/api/tasks", JSONObject().put("title", title))

    /** Una vez al día: las apps instaladas, para que la IA de la noche clasifique las nuevas. */
    fun reportApps(ctx: Context) {
        if (Store.appsReported == Store.today()) return
        Store.appsReported = Store.today()
        io.execute {
            val apps = JSONObject(Apps.launchable(ctx) as Map<*, *>)
            Sync.post("/api/apps", JSONObject().put("apps", apps))
        }
    }
}
