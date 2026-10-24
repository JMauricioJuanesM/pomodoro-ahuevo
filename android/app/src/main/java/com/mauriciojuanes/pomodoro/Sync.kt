package com.mauriciojuanes.pomodoro

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Habla con el servidor del VPS en dos hilos: uno espera cambios (long-poll de `/api/wait`)
 * y otro envía lo pendiente (temporizador y señal de vida), reintentando si no hay red.
 */
object Sync {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val pending = LinkedHashMap<String, JSONObject>()   // "timer" / "heartbeat" → cuerpo
    private var started = false
    @Volatile private var rev = -1   // -1: la primera consulta responde al instante
    @Volatile var lastError = ""
        private set

    fun configured() = Store.token.isNotEmpty() && Store.url.isNotEmpty()

    fun start() {
        if (started) return
        started = true
        Thread(::pollLoop, "pomodoro-poll").apply { isDaemon = true }.start()
        Thread(::sendLoop, "pomodoro-send").apply { isDaemon = true }.start()
    }

    private fun req(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15000): JSONObject {
        val c = URL(Store.url + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer " + Store.token)
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code != 200) throw java.io.IOException(if (code == 401) "token inválido" else "HTTP $code")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun pollLoop() {
        while (true) {
            if (!configured()) {
                Thread.sleep(5000)
                continue
            }
            try {
                val st = req("GET", "/api/wait?rev=$rev&source=phone&timeout=40", timeoutMs = 60000)
                rev = st.optInt("rev")
                lastError = ""
                main.post { Engine.onRemote(st) }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                main.post { Engine.offline() }
                Thread.sleep(15000)
            }
        }
    }

    private fun sendLoop() {
        while (true) {
            val (kind, body) = synchronized(lock) {
                while (pending.isEmpty() || !configured()) lock.wait(5000)
                pending.entries.first().toPair()
            }
            try {
                when (kind) {
                    "timer" -> req("PUT", "/api/timer", JSONObject().put("source", "phone").put("timer", body))
                    "config" -> req("PUT", "/api/config", JSONObject().put("source", "phone").put("config", body))
                    else -> req("POST", "/api/heartbeat", body.put("source", "phone"))
                }
                synchronized(lock) { if (pending[kind] === body) pending.remove(kind) }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Thread.sleep(15000)
            }
        }
    }

    private fun queue(kind: String, body: JSONObject) = synchronized(lock) {
        pending[kind] = body
        lock.notifyAll()
    }

    fun pushTimer(t: JSONObject) = queue("timer", t)

    fun hasPendingTimer() = synchronized(lock) { "timer" in pending }

    fun pushConfig(c: JSONObject) = queue("config", c)

    fun hasPendingConfig() = synchronized(lock) { "config" in pending }

    /** Llegó un estado más nuevo de la compu: lo nuestro pendiente ya no aplica. */
    fun dropTimer() = synchronized(lock) { pending.remove("timer") }

    fun heartbeat(info: JSONObject) = queue("heartbeat", JSONObject().put("info", info)
        .put("stats", Store.todayMap("stats")).put("phone_stats", Store.todayMap("phone_stats")))
}
