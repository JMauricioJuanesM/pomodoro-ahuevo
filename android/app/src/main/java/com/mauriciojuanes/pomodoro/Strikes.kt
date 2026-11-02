package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray

/**
 * Advertencias por contenido sexual, por app. La primera vez es aviso; si en [WINDOW_MS] vuelve a pasar
 * (`phone_uninstall_strikes` veces en total), la app se desinstala sola: se abre el diálogo de Android
 * y la accesibilidad pulsa «Aceptar».
 *
 * Nunca se desinstala: WhatsApp (ahí hay información importante), las apps del sistema (Chrome, Galería…
 * no se pueden), el navegador predeterminado, el banco, esta app y `phone_never_uninstall`. En esas
 * solo se te saca y se bloquea la pantalla.
 */
object Strikes {
    private const val WINDOW_MS = 7 * 24 * 3600 * 1000L
    private const val CONFIRM_MS = 30_000L
    val NEVER = setOf("com.whatsapp", "com.whatsapp.w4b", "com.android.vending")
    private val INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.miui.global.packageinstaller", "com.miui.securitycenter")
    private val CONFIRM = setOf("aceptar", "ok", "desinstalar", "uninstall", "eliminar", "confirmar", "sí", "si")
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pendingPkg: String? = null
    private var pendingAt = 0L

    /** ¿Estamos desinstalando algo (el diálogo del instalador es nuestro)? */
    fun busy() = pendingPkg != null && System.currentTimeMillis() - pendingAt < CONFIRM_MS

    private fun limit() = Engine.cfg.optInt("phone_uninstall_strikes", 2).coerceAtLeast(1)

    fun count(pkg: String): Int {
        val now = System.currentTimeMillis()
        val arr = Store.strikes.optJSONArray(pkg) ?: return 0
        return (0 until arr.length()).count { now - arr.optLong(it) < WINDOW_MS }
    }

    /** Suma una advertencia; regresa cuántas lleva en la ventana. */
    fun add(pkg: String): Int {
        val now = System.currentTimeMillis()
        val all = Store.strikes
        val old = all.optJSONArray(pkg) ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until old.length()) if (now - old.optLong(i) < WINDOW_MS) kept.put(old.optLong(i))
        kept.put(now)
        all.put(pkg, kept)
        Store.strikes = all
        return kept.length()
    }

    fun canUninstall(s: AccessibilityService, pkg: String): Boolean {
        if (!Engine.cfg.optBoolean("phone_uninstall", true)) return false
        if (pkg in NEVER || pkg == s.packageName || BankMode.isBank(pkg)) return false
        val never = Engine.cfg.optJSONArray("phone_never_uninstall")
        if (never != null && (0 until never.length()).any { never.optString(it) == pkg }) return false
        val browser = s.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
        if (browser?.activityInfo?.packageName == pkg) return false
        return runCatching {
            s.packageManager.getApplicationInfo(pkg, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        }.getOrDefault(false)
    }

    /** Se detectó algo en `pkg`: advertencia o, si ya iban suficientes, desinstalar. Regresa true si la desinstala. */
    fun strike(s: AccessibilityService, pkg: String, name: String, level: String): Boolean {
        val n = add(pkg)
        if (!canUninstall(s, pkg)) return false
        if (n >= limit()) {
            uninstall(s, pkg, name, level)
            return true
        }
        Notifier.alert(s, "⚠️ Advertencia ${n}/${limit()}: $name",
            "Si vuelve a pasar esta semana, desinstalo $name sin preguntar.")
        Sync.alert("warn", pkg, name, "warn")
        return false
    }

    fun uninstall(s: AccessibilityService, pkg: String, name: String, level: String) {
        pendingPkg = pkg
        pendingAt = System.currentTimeMillis()
        Notifier.alert(s, "🗑 Desinstalando $name", "Ya te había advertido. No la vuelvas a instalar.")
        Sync.alert(level, pkg, name, "uninstall")
        runCatching {
            s.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Se abrió una ventana: si es el diálogo de desinstalar que pedimos, pulsa «Aceptar». */
    fun onWindow(s: AccessibilityService, pkg: String) {
        if (pendingPkg == null || pkg !in INSTALLERS) return
        if (System.currentTimeMillis() - pendingAt > CONFIRM_MS) {
            pendingPkg = null
            return
        }
        // El diálogo tarda un poco en armarse: unos cuantos intentos.
        for (i in 0..4) handler.postDelayed({ if (pendingPkg != null) confirm(s) }, 300L + i * 400L)
    }

    private fun confirm(s: AccessibilityService) {
        val root = s.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByViewId("android:id/button1") +
            CONFIRM.flatMap { root.findAccessibilityNodeInfosByText(it) }
        val btn = nodes.firstOrNull { n ->
            val t = n.text?.toString()?.trim()?.lowercase() ?: ""
            n.isEnabled && (n.viewIdResourceName == "android:id/button1" || t in CONFIRM)
        } ?: return
        var target: AccessibilityNodeInfo? = btn
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) pendingPkg = null
    }

    /** Llegó (o regresó) una app: si es de contenido sexual, aviso; si ya tenía advertencias, fuera. */
    fun onInstalled(s: AccessibilityService, pkg: String) {
        val name = runCatching {
            s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        if (!AdultDb.isAdultApp(pkg, name)) return
        if (count(pkg) > 0 && canUninstall(s, pkg)) {
            add(pkg)
            uninstall(s, pkg, name, "adult_app")
        } else {
            add(pkg)
            Notifier.alert(s, "🚫 Instalaste $name", "Está en la lista de contenido sexual. Si la abres, la desinstalo.")
            Sync.alert("adult_app", pkg, name, "warn")
        }
    }
}
