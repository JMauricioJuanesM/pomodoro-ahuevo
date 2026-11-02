package com.mauriciojuanes.pomodoro

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import org.json.JSONObject

/**
 * Pruebas automáticas en el propio teléfono: al arrancar (también tras una actualización) y cada 6 horas.
 * El resultado va al hub (`/api/selftest`); si falla algo crítico el bot se entera y el ciclo nocturno
 * lo toma en cuenta (p. ej. regresar a la versión anterior).
 */
object SelfTest {
    private val CRITICAL = setOf("guard", "secure", "sync", "engine", "policy")

    fun run(ctx: Context): JSONObject {
        val checks = linkedMapOf<String, Boolean>()
        checks["guard"] = GuardService.instance != null || Store.bankSince > 0   // en modo banco se apaga a propósito
        checks["secure"] = Blocks.canWrite()
        checks["sync"] = Sync.configured() && Engine.online
        checks["usage"] = ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        checks["notifications"] = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        checks["dnd"] = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        checks["admin"] = ctx.getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(ctx, SelfDefense.Admin::class.java))
        checks["install"] = ctx.packageManager.canRequestPackageInstalls()
        checks["service"] = PomodoroService.running
        checks["engine"] = runCatching { Engine.remaining(); Engine.sched.cell(java.time.LocalDateTime.now()); true }.getOrDefault(false)
        checks["policy"] = runCatching {
            Policy.at()
            Apps.category(ctx, ctx.packageName) == Apps.ESSENTIAL && Mind.pick() != null
        }.getOrDefault(false)
        val ok = CRITICAL.all { checks[it] == true }
        return JSONObject().put("source", "phone").put("version", Updater.installedName(ctx))
            .put("code", Updater.installedCode(ctx)).put("ok", ok).put("checks", JSONObject(checks as Map<*, *>))
    }

    /** Corre y reporta si toca (cada 6 h, o ya si la versión cambió). */
    fun maybeReport(ctx: Context) {
        val code = Updater.installedCode(ctx)
        val last = Store.long("selftest_at")
        if (Store.long("selftest_code") == code && System.currentTimeMillis() - last < 6 * 3600_000L) return
        // Recién arrancado (p. ej. tras actualizarse) la accesibilidad tarda en conectarse: se espera un poco.
        if (!Engine.online || android.os.SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 90_000) return
        Store.setLong("selftest_at", System.currentTimeMillis())
        Store.setLong("selftest_code", code)
        Sync.post("/api/selftest", run(ctx))
    }
}
