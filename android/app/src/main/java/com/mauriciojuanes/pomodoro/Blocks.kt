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
 * Desde el teléfono solo se puede bloquear: desbloquear se hace en la compu (Panel ADB). Y el DNS y el
 * bloqueo de Xiaomi nunca quedan abiertos más de [MAX_SHORT_SEC] desde que el teléfono se entera, pida
 * lo que pida el panel (para revisar algo rápido basta; más tiempo es riesgo de recaída).
 */
object Blocks {
    private lateinit var ctx: Context
    private var lastEnforce = 0L
    private val SHORT = setOf("dns", "applock")
    const val MAX_SHORT_SEC = 15 * 60

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

    /** Cuándo se vuelve a bloquear de verdad: el DNS y el bloqueo de Xiaomi, a lo más 15 min tras verlo aquí. */
    fun effectiveAt(r: Relock): Double? {
        if (r.kind !in SHORT) return r.at
        val cap = firstSeen(r) + MAX_SHORT_SEC
        return if (r.at == null) cap else minOf(r.at, cap)
    }

    private fun key(r: Relock) = "${r.kind}|${r.pkg}|${r.at}"

    private fun firstSeen(r: Relock): Double {
        val seen = Store.relockSeen
        if (!seen.has(key(r))) {
            seen.put(key(r), now())
            Store.relockSeen = seen
        }
        return seen.optDouble(key(r), now())
    }

    /** ¿Hay un desbloqueo vigente para esto? */
    fun pending(kind: String, pkg: String? = null) = relocks().any {
        it.kind == kind && it.pkg == pkg && (effectiveAt(it)?.let { at -> at > now() } ?: true)
    }

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
        val due = relocks().filter { r -> effectiveAt(r)?.let { it <= now() } ?: false }
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
        forgetSeen(b)
    }

    private fun forgetSeen(b: JSONObject) {
        val arr = b.optJSONArray("relocks") ?: JSONArray()
        val live = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            "${it.optString("kind")}|${if (it.isNull("pkg")) null else it.optString("pkg")}|${if (it.isNull("at")) null else it.optDouble("at")}"
        }.toSet()
        val seen = Store.relockSeen
        seen.keys().asSequence().toList().filter { it !in live }.forEach { seen.remove(it) }
        Store.relockSeen = seen
    }

    fun relockAll() = relock(relocks())

    // ---------- lo que llega del servidor ----------
    fun onRemote(st: JSONObject) {
        val b = st.optJSONObject("blocks") ?: return
        val rev = st.optInt("blocks_rev")
        // Nuestro propio eco no se aplica (aquí ya está lo más nuevo); en una instalación nueva sí.
        if (rev > Store.blocksRevSeen && !Sync.hasPendingBlocks() &&
            (st.optString("blocks_source") != "phone" || !configured())) {
            Store.blocks = b
            forgetSeen(b)
            enforce()
        }
        Store.blocksRevSeen = maxOf(Store.blocksRevSeen, rev)
    }
}
