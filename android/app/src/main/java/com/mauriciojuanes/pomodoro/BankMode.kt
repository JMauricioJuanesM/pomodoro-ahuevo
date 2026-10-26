package com.mauriciojuanes.pomodoro

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * Excepción para las apps del banco.
 *
 * Nu (y otros bancos) desconfían de cualquier app con accesibilidad activa que no venga de Play Store
 * —así trabajan los troyanos bancarios— y piden desinstalarla. Mientras usas una app del banco, la
 * vigilancia ([GuardService]) se apaga sola, y se vuelve a encender en cuanto sales de ella o apagas la
 * pantalla. El DNS y el bloqueo de Xiaomi siguen cumpliéndose igual ([Blocks] no depende de la
 * accesibilidad).
 *
 * Requiere WRITE_SECURE_SETTINGS (prender/apagar la accesibilidad) y el acceso a uso de apps (saber que
 * ya saliste del banco sin la accesibilidad); la compu otorga ambos por ADB. Sin el acceso a uso, la
 * vigilancia regresa a los [FALLBACK_SEC].
 */
object BankMode {
    val DEFAULT_APPS = setOf("com.nu.production", "ai.powerup.stori", "com.mercadopago.wallet")
    private const val FALLBACK_SEC = 180

    fun apps(): Set<String> {
        val arr = Engine.cfg.optJSONArray("phone_bank_apps") ?: return DEFAULT_APPS
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }

    fun isBank(pkg: String) = pkg in apps()

    private fun me(ctx: Context) = ComponentName(ctx, GuardService::class.java).flattenToString()

    private fun enabledServices(ctx: Context) =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotEmpty() && it != "null" }

    /** Abriste una app del banco: la vigilancia se apaga sola (lo llama [GuardService]). */
    fun enter(ctx: Context) {
        if (!Blocks.canWrite() || Store.bankSince > 0) return
        Store.bankSince = System.currentTimeMillis()
        val others = enabledServices(ctx).filterNot { it.equals(me(ctx), true) }
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
    }

    /** Cada segundo (desde [PomodoroService]): si ya saliste del banco, regresa la vigilancia. */
    fun tick(ctx: Context) {
        val since = Store.bankSince
        if (since == 0L) return
        val screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive
        val fg = foreground(ctx, since)
        val left = !screenOn || (fg != null && !isBank(fg)) ||
            (fg == null && !hasUsageAccess(ctx) && System.currentTimeMillis() - since > FALLBACK_SEC * 1000L)
        if (left) exit(ctx)
    }

    fun exit(ctx: Context) {
        Store.bankSince = 0L
        if (!Blocks.canWrite()) return
        val services = enabledServices(ctx)
        if (services.none { it.equals(me(ctx), true) }) {
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (services + me(ctx)).joinToString(":"))
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }
    }

    private fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** La última app que pasó al frente desde que entraste al banco (null si no se sabe). */
    private fun foreground(ctx: Context, sinceMs: Long): String? {
        if (!hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(sinceMs - 5000, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}
