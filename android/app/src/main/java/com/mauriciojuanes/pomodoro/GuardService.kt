package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Vigilancia (lo mismo que hacía la compu por ADB, pero desde el teléfono):
 *  - a cualquier hora, si abres una app que el Panel ADB tiene bloqueada, te saca de ella ([Blocks]);
 *  - a cualquier hora, revisa lo que se ve en las apps de riesgo ([ScreenWatch]);
 * y durante el trabajo:
 *  - si abres una app que distrae, avisa y a los pocos segundos te manda al inicio;
 *  - si llevas mucho rato en el teléfono, avisa y luego lo bloquea;
 *  - al terminar el descanso, si sigues en el teléfono, avisa y lo bloquea.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var fg = ""
    private var onSince = 0L
    private var warned = false
    private var distractPkg: String? = null
    private var lastBlockedPkg = ""
    private var screen: ScreenWatch? = null
    private var lastBlockedAt = 0L
    private val ignored = mutableSetOf("com.android.systemui", "android")

    private val loop = object : Runnable {
        override fun run() {
            try {
                check()
            } finally {
                handler.postDelayed(this, 5000)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { ignored += it.packageName }
        if (!PomodoroService.running) PomodoroService.start(this)
        handler.post(loop)
        screen = ScreenWatch(this).also { it.start() }
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(loop)
        screen?.stop()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        fg = pkg
        screen?.onForeground(pkg)
        if (BankMode.isBank(pkg)) {   // el banco desconfía de la accesibilidad: nos apagamos mientras lo usas
            BankMode.enter(this)
            return
        }
        if (Blocks.isBlocked(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val t = System.currentTimeMillis()
            if (pkg != lastBlockedPkg || t - lastBlockedAt > 60_000) {
                lastBlockedPkg = pkg
                lastBlockedAt = t
                Notifier.alert(this, "🚫 ${Blocks.label(pkg)} está bloqueada", "La bloqueaste en el Panel ADB.", vibrate = false)
            }
            return
        }
        if (Engine.working()) check()
    }

    private val cfg get() = Engine.cfg
    private val readSec get() = cfg.optInt("phone_read_sec", 15)
    private fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun check() {
        if (!Engine.working() || !screenOn()) {
            onSince = 0
            warned = false
            distractPkg = null
            return
        }
        val distracting = cfg.optJSONArray("phone_distracting")
        val isDistracting = distracting != null && (0 until distracting.length()).any { distracting.optString(it) == fg }
        if (cfg.optBoolean("phone_distract", true) && isDistracting) {
            if (distractPkg != fg) {
                val pkg = fg
                distractPkg = pkg
                val name = appName(pkg)
                warnThen("📵 $name en horario de trabajo", "En $readSec s te saco de $name.") {
                    if (fg == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            return
        }
        distractPkg = null
        if (!cfg.optBoolean("phone_work_guard", true)) return
        val now = System.currentTimeMillis()
        if (onSince == 0L) onSince = now
        val mins = (now - onSince) / 60000.0
        val warnMin = cfg.optDouble("phone_work_warn_min", 3.0)
        val lockMin = cfg.optDouble("phone_work_lock_min", 5.0)
        if (mins >= lockMin) {
            onSince = 0
            warned = false
            warnThen("🔒 Deja el teléfono", "Llevas ${mins.toInt()} min en él en horario de trabajo.\nEn $readSec s se bloquea.") {
                lock()
            }
        } else if (mins >= warnMin && !warned) {
            warned = true
            Notifier.alert(this, "👀 Estás en el teléfono",
                "Es hora de trabajo. Si sigues, en ${(lockMin - warnMin).toInt()} min lo bloqueo.")
        }
    }

    /** Primero avisa (con tiempo para leer) y después ejecuta `action` si sigue aplicando. */
    private fun warnThen(title: String, text: String, action: () -> Unit) {
        Notifier.alert(this, title, text)
        handler.postDelayed({ if (Engine.working() && screenOn()) action() }, readSec * 1000L)
    }

    private fun lock() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /** Se acabó el descanso: si a los N segundos sigues en el teléfono, avisa y lo bloquea. */
    fun breakOver() {
        if (cfg.optString("phone_break_end", "lock") != "lock") return
        val delay = cfg.optInt("phone_lock_delay_sec", 45)
        handler.postDelayed({
            if (Engine.working() && screenOn()) {
                warnThen("🔒 Ya es hora de trabajar", "Sigues en el teléfono y el descanso ya terminó.\nEn $readSec s se bloquea.") {
                    lock()
                }
            }
        }, delay * 1000L)
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.')
    }

    companion object {
        @Volatile var instance: GuardService? = null
    }
}
