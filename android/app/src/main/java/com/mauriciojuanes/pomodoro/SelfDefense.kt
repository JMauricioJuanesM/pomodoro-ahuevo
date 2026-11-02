package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Que esta app no se pueda quitar desde el teléfono (la salida es la compu: `adb uninstall`).
 *
 *  - Es administrador del dispositivo ([Admin], lo activa la compu por ADB): Android no deja desinstalar
 *    un administrador activo.
 *  - La accesibilidad te saca de cualquier pantalla de Ajustes, Seguridad o del instalador que hable de
 *    Pomodoro y de desinstalar, forzar detención, borrar datos o desactivar (el administrador o la
 *    accesibilidad), y el bot se entera.
 *  - Si alguien apaga la accesibilidad (fuera del modo banco), se vuelve a prender sola en segundos.
 *
 * Se apaga con `phone_self_protect: false` en la configuración de la compu. Para desinstalarla de verdad,
 * desde la compu (Android no deja quitar por ADB un administrador que no es de prueba):
 *   adb shell am start-foreground-service -a release com.mauriciojuanes.pomodoro/.PomodoroService
 *   adb uninstall com.mauriciojuanes.pomodoro
 */
object SelfDefense {
    private val GUARDED = setOf("com.android.settings", "com.miui.securitycenter", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.miui.packageinstaller", "com.miui.home",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.miui.securitycore")
    private val DANGER = listOf("desinstalar", "uninstall", "forzar", "force stop", "detener", "borrar datos",
        "clear data", "clear storage", "borrar almacenamiento", "eliminar", "desactivar", "deactivate",
        "turn off", "apagar", "administrador", "device admin")
    private const val MAX_NODES = 300
    private var lastScan = 0L
    private var lastKick = 0L
    private var lastRestore = 0L

    fun on() = Engine.cfg.optBoolean("phone_self_protect", true)

    /** Una ventana de Ajustes/instalador cambió: si amenaza a Pomodoro, fuera. */
    fun onEvent(s: AccessibilityService, pkg: String, stateChanged: Boolean) {
        if (!on() || pkg !in GUARDED || Strikes.busy()) return
        val now = System.currentTimeMillis()
        if (!stateChanged && now - lastScan < 700) return
        lastScan = now
        val text = runCatching { screenText(s.rootInActiveWindow) }.getOrNull() ?: return
        if ("pomodoro" !in text || DANGER.none { it in text }) return
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (now - lastKick > 10_000) {
            lastKick = now
            Notifier.alert(s, "🛡 Pomodoro no se quita desde aquí", "Si de verdad hace falta, desde la compu (adb).")
            Sync.alert("self_defense", pkg, "Ajustes", "home")
        }
    }

    private fun screenText(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return sb.toString().lowercase()
    }

    /** Cada segundo (desde [PomodoroService]): si apagaron la accesibilidad fuera del modo banco, prenderla. */
    fun tick(ctx: Context) {
        if (!on() || Store.bankSince > 0L || !Blocks.canWrite()) return
        val now = System.currentTimeMillis()
        if (now - lastRestore < 5000 || GuardService.instance != null) return
        lastRestore = now
        BankMode.exit(ctx)
    }

    /** Deja de ser administrador (lo pide la compu por ADB, ver arriba). */
    fun release(ctx: Context) {
        val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        runCatching { dpm.removeActiveAdmin(android.content.ComponentName(ctx, Admin::class.java)) }
    }

    /** Administrador del dispositivo sin políticas: solo para que Android no deje desinstalar la app. */
    class Admin : DeviceAdminReceiver() {
        override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
            "Pomodoro te protege de recaídas. Si de verdad hace falta quitarlo, hazlo desde la compu."
    }
}
