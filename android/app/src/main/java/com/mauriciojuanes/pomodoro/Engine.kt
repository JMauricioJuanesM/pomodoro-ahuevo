package com.mauriciojuanes.pomodoro

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * El temporizador del teléfono (todo en el hilo principal).
 *
 * Mientras la compu está prendida, ella manda: el teléfono refleja su estado y solo envía lo que tú
 * hagas aquí (iniciar, pausar, descanso…). Con la compu apagada, el teléfono lleva la jornada él solo
 * con las mismas reglas: arranque automático, descansos, ciclos, horas extra, horario y recordatorios.
 */
object Engine {
    private lateinit var ctx: Context
    var timer = Timer()
        private set
    var online = false
    private var desktopSeenAt = 0.0     // reloj local, en segundos
    private var lastPhase = "work"
    private var warned = false
    private var lastNag = now()
    private var synced = false          // ya recibimos el primer estado del servidor en esta ejecución
    private val listeners = mutableSetOf<() -> Unit>()

    private val NAMES = mapOf("work" to "Trabajo", "short" to "Descanso corto", "long" to "Descanso largo")
    private val COLORS = mapOf("work" to "#c0392b", "short" to "#1e8449", "long" to "#1f618d")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun init(c: Context) {
        ctx = c.applicationContext
        timer = Store.timer
        lastPhase = timer.phase
    }

    fun now() = System.currentTimeMillis() / 1000.0
    fun listen(f: () -> Unit) = listeners.add(f)
    fun unlisten(f: () -> Unit) = listeners.remove(f)
    private fun changed() = listeners.toList().forEach { it() }

    val cfg: JSONObject get() = Store.config
    val sched get() = Sched(cfg)
    private fun num(key: String, def: Double) = cfg.optDouble(key, def)
    private fun flag(key: String, def: Boolean) = cfg.optBoolean(key, def)

    val desktopAlive get() = now() - desktopSeenAt < 120
    /** ¿Le toca al teléfono mover el temporizador? (la compu no está) */
    val authority get() = !desktopAlive
    fun working() = timer.phase == "work" && timer.running
    fun remaining() = timer.remainingAt(now())

    // ---------- reglas (iguales a las de la compu) ----------
    fun inOvertime(t: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!flag("overtime_mode", true)) return false
        val b = sched.bounds(t.toLocalDate())
        return t.toLocalTime() < LocalTime.of(5, 0) || b == null || t >= b.second
    }

    fun duration(phase: String, overtime: Boolean): Double {
        var mins = when (phase) {
            "work" -> num("work_min", 25.0)
            "short" -> num("short_min", 5.0)
            else -> num("long_min", 15.0)
        }
        if (overtime) mins = if (phase == "work") maxOf(1.0, mins / 2) else mins * 2
        return mins * 60
    }

    private fun focusNow(): Boolean =
        flag("focus_allow_skip", true) && sched.kind(sched.cell(LocalDateTime.now())) == "enfocado"

    fun pomodorosToday() = Store.count("stats").toInt()

    // ---------- cambios de estado ----------
    private fun set(t: Timer, push: Boolean = true) {
        timer = t
        Store.timer = t
        warned = false
        if (push) Sync.pushTimer(t.toJson().put("stats", JSONObject().put("day", Store.today())
            .put("count", pomodorosToday())))
        watchPhase()
        changed()
    }

    private fun startPhase(phase: String, run: Boolean = true, skippable: Boolean = false, done: Int = timer.doneInCycle) {
        val ot = inOvertime()
        val total = duration(phase, ot)
        set(Timer(phase, NAMES.getValue(phase), COLORS.getValue(phase), run,
            if (run) now() + total else null, if (run) null else total, total, done, ot, skippable))
    }

    private fun startBreak(count: Boolean) {
        var done = timer.doneInCycle
        if (count) {
            done += 1
            Store.setCount("stats", Store.count("stats") + 1)
        }
        val long = done >= num("cycles", 4.0)
        if (long) done = 0
        startPhase(if (long) "long" else "short", skippable = flag("allow_skip", false) || focusNow(), done = done)
    }

    private fun endBreak() {
        val aid = sched.cell(LocalDateTime.now())
        val auto = flag("auto_start_work", true) && Sched.isWork(sched.kind(aid))
        startPhase("work", run = auto)
    }

    private fun startSched(b: Block, key: String) {
        Store.schedKey = key
        val end = b.end.atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
        val total = java.time.Duration.between(b.start, b.end).seconds.toDouble()
        set(Timer("sched", sched.label(b.aid), sched.color(b.aid), true, end, null, total, 0, false,
            flag("allow_skip", false)))
    }

    /** Avisos que dependen de en qué fase estamos (sirven igual si el cambio lo hizo la compu). */
    private fun watchPhase() {
        val phase = timer.phase
        if (phase == lastPhase) return
        val was = lastPhase
        lastPhase = phase
        val until = java.time.Instant.ofEpochSecond((timer.endTime ?: now()).toLong())
            .atZone(java.time.ZoneId.systemDefault()).format(HHMM)
        when {
            phase == "short" || phase == "long" ->
                Notifier.alert(ctx, "☕ ${timer.title}", "Hasta las $until. Levántate, estírate, toma agua.", vibrate = false)
            phase == "sched" -> Notifier.alert(ctx, "🔒 ${timer.title}", "Es hora de ${timer.title} hasta las $until.")
            phase == "work" && was != "work" -> {
                val txt = if (timer.running) "Arrancó tu siguiente pomodoro." else "Pulsa Iniciar cuando quieras."
                Notifier.alert(ctx, "⏰ Se acabó el descanso", txt)
                GuardService.instance?.breakOver()
            }
        }
    }

    // ---------- reloj: una vez por segundo ----------
    fun tick() {
        val now = LocalDateTime.now()
        if (authority) checkDay(now)
        checkReminders(now)
        if (timer.running) {
            val rem = remaining()
            if (timer.phase == "work") {
                val warnSec = num("warn_sec", 60.0)
                if (authority && !warned && warnSec > 0 && rem <= warnSec) {
                    warned = true
                    Notifier.alert(ctx, "El descanso empieza pronto", "En ${fmt(rem)} toca descansar.", vibrate = false)
                }
                if (authority && rem <= 0) startBreak(true)
            } else if (authority && rem <= 0) {
                endBreak()
            }
            if (working() && inOvertime(now) && now() - lastNag > 20 * 60) {
                lastNag = now()
                Notifier.alert(ctx, "Tu jornada ya terminó", "Estás trabajando fuera de horario. Ve cerrando.")
            }
        }
        changed()
    }

    private fun checkDay(now: LocalDateTime) {
        val today = LocalDate.now().toString()
        val s = sched
        val b = s.blockAt(now)
        if (b.aid != null && s.kind(b.aid) == "descanso") {
            val key = b.start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH"))
            if (Store.schedKey != key) {
                startSched(b, key)
                return
            }
        }
        // Un bloque «enfocado» (p. ej. una reunión) que empieza a media pausa la termina: llegas a tiempo.
        val breakEnd = timer.endTime
        if ((timer.phase == "short" || timer.phase == "long") && timer.running && breakEnd != null &&
            b.aid != null && s.kind(b.aid) == "enfocado" &&
            b.start.atZone(java.time.ZoneId.systemDefault()).toEpochSecond() > breakEnd - timer.total + 1) {
            endBreak()
            return
        }
        val bounds = s.bounds(now.toLocalDate())
        val idle = timer.phase == "work" && !timer.running
        if (flag("autostart", true) && Store.autoDate != today && idle && bounds != null &&
            now >= bounds.first && now < bounds.second && Sched.isWork(s.kind(b.aid))) {
            Store.autoDate = today
            Store.endDayDate = ""
            startPhase("work")
            Notifier.alert(ctx, "Arrancó tu jornada", "Ahora toca ${s.label(b.aid)}. ¡A darle!")
            return
        }
        if (working() && inOvertime(now) && Store.endDayDate != today && !timer.overtime) {
            Store.endDayDate = today
            set(timer.copy(running = false, endTime = null, remaining = remaining()))
            Notifier.alert(ctx, "🌙 Tu jornada terminó", "Pausé el pomodoro. Si le sigues, serán horas extra.")
        }
    }

    private fun checkReminders(now: LocalDateTime) {
        val reminders = cfg.optJSONArray("reminders") ?: return
        val today = now.toLocalDate().toString()
        val fired = Store.fired()
        var dirty = false
        for (i in 0 until reminders.length()) {
            val r = reminders.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true)) continue
            val type = r.optString("type")
            if (type == "once" && r.optString("date") != today) continue
            if (type != "once") {
                val days = r.optJSONArray("days") ?: continue
                if ((0 until days.length()).none { days.optInt(it) == now.dayOfWeek.value - 1 }) continue
            }
            val time = runCatching { LocalTime.parse(r.optString("time"), DateTimeFormatter.ofPattern("H:mm")) }
                .getOrNull() ?: continue
            val delta = java.time.Duration.between(now.toLocalDate().atTime(time), now).seconds
            val key = "${r.opt("id")}|$today ${r.optString("time")}"
            if (delta in 0..119 && key !in fired) {
                fired += key
                dirty = true
                val dur = r.optInt("duration")
                Notifier.alert(ctx, "⏰ " + r.optString("text"),
                    "Recordatorio de las ${r.optString("time")}" + if (dur > 0) " · $dur min" else "")
            }
        }
        if (dirty) Store.setFired(fired)
    }

    // ---------- lo que haces tú en el teléfono ----------
    fun toggle() {
        if (timer.phase != "work") return
        if (timer.running) {
            set(timer.copy(running = false, endTime = null, remaining = remaining()))
        } else {
            val rem = timer.remaining ?: timer.total
            Store.autoDate = Store.today()
            if (inOvertime()) Store.endDayDate = Store.today()   // seguir = aceptar las horas extra
            set(timer.copy(running = true, endTime = now() + rem, remaining = null))
        }
    }

    fun reset() {
        if (timer.phase != "work") return
        val total = duration("work", inOvertime())
        set(timer.copy(running = false, endTime = null, remaining = total, total = total))
    }

    fun breakNow() {
        if (timer.phase == "work") startBreak(false)
    }

    fun skip() {
        if (timer.phase != "work" && timer.skippable) endBreak()
    }

    /** Cambiar la configuración desde el teléfono (horario, recordatorios, ajustes) y mandarla a la compu. */
    fun editConfig(f: (JSONObject) -> Unit) {
        val c = JSONObject(Store.config.toString())
        f(c)
        Store.config = c
        Sync.pushConfig(c)
        if (timer.phase == "work" && !timer.running) {   // p. ej. cambió la duración del pomodoro
            val total = duration("work", inOvertime())
            if (timer.remaining == timer.total) set(timer.copy(remaining = total, total = total))
        }
        changed()
    }

    // ---------- lo que llega del servidor ----------
    fun onRemote(st: JSONObject) {
        online = true
        // Nuestro propio eco no se aplica: aquí ya está lo más nuevo (y una respuesta en camino podría traer
        // una versión anterior). Al arrancar sí se toma lo del servidor.
        val fromPhone = st.optString("config_source") == "phone"
        if (!Sync.hasPendingConfig() && (!synced || !fromPhone)) st.optJSONObject("config")?.let { Store.config = it }
        val serverNow = st.optDouble("server_time", now())
        st.optJSONObject("devices")?.optJSONObject("desktop")?.let {
            desktopSeenAt = now() - (serverNow - it.optDouble("seen", 0.0))
        }
        Store.mergeMax("stats", st.optJSONObject("stats"))
        Store.mergeMax("phone_stats", st.optJSONObject("phone_stats"))
        val rev = st.optInt("timer_rev")
        val t = st.optJSONObject("timer")
        // Al arrancar se adopta lo del servidor aunque diga "phone" (pudo quedar de otra instalación).
        val fresh = !synced && !Sync.hasPendingTimer()
        if (t != null && (fresh || st.optString("timer_source") != "phone" && rev > Store.timerRevSeen)) {
            Sync.dropTimer()
            set(Timer.fromJson(t), push = false)
        }
        synced = true
        Store.timerRevSeen = maxOf(Store.timerRevSeen, rev)
        changed()
    }

    fun offline() {
        online = false
        changed()
    }

    fun fmt(secs: Double): String {
        val s = maxOf(0, Math.round(secs).toInt())
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}
