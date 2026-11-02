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
    // "timer" / "config" / "blocks" / "heartbeat" / "alert#…" / "post#…" / "put#…" → cuerpo
    private val pending = LinkedHashMap<String, JSONObject>()
    private var started = false
    @Volatile private var rev = -1   // -1: la primera consulta responde al instante
    @Volatile var lastError = ""
        private set

    fun configured() = Store.token.isNotEmpty() && Store.url.isNotEmpty()

    fun start() {
        if (started) return
        started = true
        // Lo que quedó sin mandar (diario, respuestas…) de la ejecución anterior.
        synchronized(lock) {
            val saved = Store.outbox
            saved.keys().forEach { pending[it] = saved.getJSONObject(it) }
        }
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
                when (kind.substringBefore('#')) {
                    "timer" -> req("PUT", "/api/timer", JSONObject().put("source", "phone").put("timer", body))
                    "config" -> req("PUT", "/api/config", JSONObject().put("source", "phone").put("config", body))
                    "blocks" -> req("PUT", "/api/blocks", JSONObject().put("source", "phone").put("blocks", body))
                    "alert" -> req("POST", "/api/alert", body.put("source", "phone"))
                    "post", "put" -> req(kind.substringBefore('#').uppercase(), body.getString("path"), body.getJSONObject("body"))
                    else -> req("POST", "/api/heartbeat", body.put("source", "phone"))
                }
                done(kind, body)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                // Un 4xx no se arregla reintentando (p. ej. la tarea ya no existe): se descarta.
                if (lastError.startsWith("HTTP 4") && kind.contains('#')) done(kind, body) else Thread.sleep(15000)
            }
        }
    }

    private fun queue(kind: String, body: JSONObject) = synchronized(lock) {
        pending[kind] = body
        if (durable(kind)) saveOutbox()
        lock.notifyAll()
    }

    private fun done(kind: String, body: JSONObject) = synchronized(lock) {
        if (pending[kind] === body) pending.remove(kind)
        if (durable(kind)) saveOutbox()
    }

    /** Lo que registras tú sobrevive a que maten la app sin red. */
    private fun durable(kind: String) = kind.startsWith("post#") || kind.startsWith("put#") || kind.startsWith("alert#")

    private fun saveOutbox() {
        val o = JSONObject()
        pending.filterKeys(::durable).forEach { (k, v) -> o.put(k, v) }
        Store.outbox = o
    }

    fun pushTimer(t: JSONObject) = queue("timer", t)

    /** Una petición cualquiera al hub, encolada con reintentos (cada una aparte). Ver [Hub]. */
    fun post(path: String, body: JSONObject) = queue("post#${System.nanoTime()}", JSONObject().put("path", path).put("body", body))

    fun put(path: String, body: JSONObject) = queue("put#${System.nanoTime()}", JSONObject().put("path", path).put("body", body))

    /** Lectura directa (en un hilo de fondo). */
    fun get(path: String): JSONObject = req("GET", path)

    /** Descarga un archivo del hub (el APK nuevo, ver [Updater]). */
    fun download(path: String, to: java.io.File) {
        val c = URL(Store.url + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 120000
            c.setRequestProperty("Authorization", "Bearer " + Store.token)
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { inp -> to.outputStream().use { inp.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    fun hasPendingTimer() = synchronized(lock) { "timer" in pending }

    fun pushConfig(c: JSONObject) = queue("config", c)

    fun hasPendingConfig() = synchronized(lock) { "config" in pending }

    fun pushBlocks(b: JSONObject) = queue("blocks", b)

    /**
     * Se detectó contenido en pantalla ([ScreenWatch]) o te saltaste el pomodoro: queda registrado y el
     * bot te escribe. Cada alerta va aparte (no se pisan como el temporizador).
     */
    fun alert(level: String, pkg: String, name: String, action: String, reason: String = "") = queue("alert#${System.nanoTime()}", JSONObject()
        .put("level", level).put("app", pkg).put("app_name", name).put("action", action).put("reason", reason))

    /** Manda una captura reducida a analizar (en un hilo de fondo). Regresa "explicit" | "suggestive" | "none". */
    fun checkScreen(jpeg: ByteArray): String {
        val c = URL(Store.url + "/api/screen").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer " + Store.token)
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.doOutput = true
            c.outputStream.use { it.write(jpeg) }
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("level", "none")
        } finally {
            c.disconnect()
        }
    }

    fun hasPendingBlocks() = synchronized(lock) { "blocks" in pending }

    /** Llegó un estado más nuevo de la compu: lo nuestro pendiente ya no aplica. */
    fun dropTimer() = synchronized(lock) { pending.remove("timer") }

    fun heartbeat(info: JSONObject) = queue("heartbeat", JSONObject().put("info", info)
        .put("stats", Store.todayMap("stats")).put("phone_stats", Store.todayMap("phone_stats")))
}
