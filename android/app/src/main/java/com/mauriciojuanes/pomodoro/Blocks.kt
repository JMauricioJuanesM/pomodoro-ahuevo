package com.mauriciojuanes.pomodoro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Los bloqueos del Panel ADB, cumplidos desde el teléfono aunque la compu esté apagada.
 *
 * El panel publica en el servidor lo que debe estar bloqueado (DNS privado, bloqueo de apps de Xiaomi,
 * apps bloqueadas) y los desbloqueos temporales con su hora de vencimiento (`at`, o null si es "hasta que
 * lo reactive"). Aquí:
 *  - al vencer un desbloqueo se vuelve a bloquear y se le avisa al panel;
 *  - el DNS y el bloqueo de Xiaomi se mantienen encendidos mientras no haya un desbloqueo vigente
 *    (requiere WRITE_SECURE_SETTINGS, que la compu otorga por ADB);
 *  - las apps bloqueadas se cierran al abrirlas (con la accesibilidad de [GuardService]).
 *
 * Bloquear es fácil (un toque); desbloquear desde aquí pide motivo, una espera y dura poco.
 */
object Blocks {
    private lateinit var ctx: Context
    private var lastEnforce = 0L

    fun init(c: Context) {
        ctx = c.applicationContext
    }

    val state: JSONObject get() = Store.blocks
    fun configured() = state.has("relocks") || state.has("blocked_apps")
    fun now() = System.currentTimeMillis() / 1000.0

    fun canWrite() = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---------- lectura ----------
    data class Relock(val kind: String, val pkg: String?, val label: String, val at: Double?)

    fun relocks(): List<Relock> {
        val arr = state.optJSONArray("relocks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Relock(it.optString("kind"), if (it.isNull("pkg")) null else it.optString("pkg"),
                it.optString("label"), if (it.isNull("at")) null else it.optDouble("at"))
        }
    }

    fun blockedApps(): List<String> {
        val arr = state.optJSONArray("blocked_apps") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    fun label(pkg: String): String = state.optJSONObject("labels")?.optString(pkg).takeUnless { it.isNullOrEmpty() }
        ?: pkg.substringAfterLast('.')

    /** ¿Hay un desbloqueo vigente para esto? (sin hora = hasta que lo reactives) */
    fun pending(kind: String, pkg: String? = null) =
        relocks().any { it.kind == kind && it.pkg == pkg && (it.at == null || it.at > now()) }

    fun isBlocked(pkg: String) = pkg in blockedApps() && !pending("app", pkg)

    fun dnsHost(): String = state.optString("dns_host")
    fun keepApplock() = state.optBoolean("keep_applock_on", true)

    fun dnsOn(): Boolean {
        val cr = ctx.contentResolver
        return Settings.Global.getString(cr, "private_dns_mode") == "hostname" &&
            Settings.Global.getString(cr, "private_dns_specifier") == dnsHost()
    }

    fun applockOn() = Settings.Secure.getString(ctx.contentResolver, "access_control_lock_enabled") == "1"

    // ---------- reloj (cada segundo desde PomodoroService) ----------
    fun tick() {
        if (!configured()) return
        val due = relocks().filter { it.at != null && it.at <= now() }
        if (due.isNotEmpty()) {
            relock(due)
            Notifier.alert(ctx, "🔒 Se volvió a bloquear", due.joinToString(", ") { it.label })
        }
        // Lo demás basta revisarlo cada pocos segundos.
        val t = System.currentTimeMillis()
        if (t - lastEnforce > 5000) {
            lastEnforce = t
            enforce()
        }
    }

    /** DNS y bloqueo de Xiaomi encendidos mientras no haya un desbloqueo vigente. */
    fun enforce() {
        if (!configured() || !canWrite()) return
        val cr = ctx.contentResolver
        if (dnsHost().isNotEmpty() && !pending("dns") && !dnsOn()) {
            Settings.Global.putString(cr, "private_dns_specifier", dnsHost())
            Settings.Global.putString(cr, "private_dns_mode", "hostname")
        }
        if (keepApplock() && !pending("applock") && !applockOn()) {
            Settings.Secure.putString(cr, "access_control_lock_enabled", "1")
        }
    }

    // ---------- cambios ----------
    private fun edit(f: (JSONObject) -> Unit) {
        val b = JSONObject(state.toString())
        f(b)
        Store.blocks = b
        Sync.pushBlocks(b)
        enforce()
        Engine.notifyChanged()
    }

    /** Vuelve a bloquear lo indicado (porque venció o porque tú quisiste): igual que `_relock_done` del panel. */
    fun relock(items: List<Relock>) = edit { b ->
        val keys = items.map { it.kind to it.pkg }.toSet()
        val arr = b.optJSONArray("relocks") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val pkg = if (r.isNull("pkg")) null else r.optString("pkg")
            if ((r.optString("kind") to pkg) !in keys) kept.put(r)
        }
        b.put("relocks", kept)
        val apps = (b.optJSONArray("blocked_apps") ?: JSONArray())
        val have = (0 until apps.length()).map { apps.optString(it) }.toMutableSet()
        items.filter { it.kind == "app" && it.pkg != null }.forEach { have += it.pkg!! }
        b.put("blocked_apps", JSONArray(have.sorted()))
    }

    fun relockAll() = relock(relocks())

    /** Desbloqueo de emergencia desde el teléfono (p. ej. un WiFi con portal que el DNS no deja abrir). */
    fun unlock(kind: String, minutes: Int, reason: String) = edit { b ->
        val label = (if (kind == "dns") "DNS privado" else "Bloqueo de apps de Xiaomi") + " (teléfono: $reason)"
        val arr = b.optJSONArray("relocks") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("kind") != kind) kept.put(it) }
        kept.put(JSONObject().put("kind", kind).put("pkg", JSONObject.NULL).put("label", label)
            .put("at", now() + minutes * 60))
        b.put("relocks", kept)
        val log = b.optJSONArray("phone_log") ?: JSONArray()
        log.put(JSONObject().put("ts", now()).put("kind", kind).put("minutes", minutes).put("reason", reason))
        while (log.length() > 100) log.remove(0)
        b.put("phone_log", log)
        val cr = ctx.contentResolver
        if (canWrite()) {
            if (kind == "dns") Settings.Global.putString(cr, "private_dns_mode", "off")
            else Settings.Secure.putString(cr, "access_control_lock_enabled", "0")
        }
    }

    /** Segundos de espera antes de poder desbloquear: crece con cada desbloqueo del día (como en el panel). */
    fun unlockWait(): Int {
        val log = state.optJSONArray("phone_log") ?: JSONArray()
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        val today = (0 until log.length()).count { (log.optJSONObject(it)?.optDouble("ts") ?: 0.0) >= dayStart }
        return minOf(300, state.optInt("unlock_wait_sec", 120) + 30 * today)
    }

    // ---------- lo que llega del servidor ----------
    fun onRemote(st: JSONObject) {
        val b = st.optJSONObject("blocks") ?: return
        val rev = st.optInt("blocks_rev")
        // Nuestro propio eco no se aplica (aquí ya está lo más nuevo); en una instalación nueva sí.
        if (rev > Store.blocksRevSeen && !Sync.hasPendingBlocks() &&
            (st.optString("blocks_source") != "phone" || !configured())) {
            Store.blocks = b
            enforce()
        }
        Store.blocksRevSeen = maxOf(Store.blocksRevSeen, rev)
    }
}
