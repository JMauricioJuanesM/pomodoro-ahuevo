package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Vigilancia (lo mismo que hacía la compu por ADB, pero desde el teléfono):
 *  - a cualquier hora, si abres una app que el Panel ADB tiene bloqueada, te saca de ella ([Blocks]);
 *  - a cualquier hora, revisa lo que se ve en pantalla y las apps que instalas ([ScreenWatch], [Strikes]);
 *  - en los descansos tapa el teléfono con el telón ([Curtain]), salvo llamadas, banco y mensajes;
 * y durante el trabajo (también si la compu está esperando a que empieces, ver [Engine.onDuty]), si la
 * actividad del horario vigila el teléfono ([Policy]):
 *  - si abres una app que distrae en esta actividad (redes, video, juegos, la lista…), avisa y a los pocos
 *    segundos te manda al inicio; si vuelves a entrar poco después, te saca al instante, y a la tercera
 *    bloquea el teléfono;
 *  - WhatsApp: chats con tope por pomodoro; estados, canales y grupos no permitidos distraen ([WhatsApp]);
 *  - si llevas mucho rato en apps que no tocan, avisa y luego lo bloquea (banco, mapas, lo esencial y lo que
 *    la actividad permite no cuentan); si lo vuelves a usar poco después de un bloqueo, los tiempos se acortan;
 *  - al terminar el descanso, si sigues en el teléfono, avisa y lo bloquea;
 *  - según cuánto llevas sin arrancar en la compu ([Engine.nudge]): con 2, modo estricto (sin tiempo de
 *    gracia en lo que distrae y tiempos cortos en lo demás); con 3, se bloquea en cuanto usas algo que no
 *    sea esencial (llamadas, mensajes, inicio, ajustes, banco y `phone_essential`).
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var fg = ""
    private var usedMs = 0L                      // tiempo en apps que no tocan, desde que prendiste la pantalla
    private var lastAccrual = 0L
    private var waWarned = false
    private var warned = false
    private var distractPkg: String? = null
    private var lastBlockedPkg = ""
    private var screen: ScreenWatch? = null
    private var lastBlockedAt = 0L
    private var lastLock = 0L                    // último bloqueo por la vigilancia del trabajo
    private val kicks = mutableMapOf<String, MutableList<Long>>()   // app → cuándo te saqué de ella
    private var lastNudge = 0
    private var lastStrictWarn = 0L
    private val essentialBase by lazy {
        val pm = packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        val dialer = getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        val sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this)
        (home + listOfNotNull(dialer, sms, packageName) + ESSENTIAL).toSet()
    }

    val foreground get() = fg
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
        Steps.start(this)
        screen = ScreenWatch(this).also { it.start() }
        registerReceiver(installs, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
    }

    /** Apps nuevas (o reinstaladas): se revisan contra la lista de contenido sexual. */
    private val installs = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (!i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) Strikes.onInstalled(this@GuardService, pkg)
        }
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(loop)
        screen?.stop()
        runCatching { unregisterReceiver(installs) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        val state = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        SelfDefense.onEvent(this, pkg, state)
        if (pkg in WhatsApp.PKGS) WhatsApp.onEvent(this, e.className?.toString(), state)
        if (!state) {   // scroll o contenido nuevo
            if (pkg !in ignored) screen?.onContent(pkg)
            return
        }
        Strikes.onWindow(this, pkg)
        Updater.onWindow(this, pkg)
        if (pkg in ignored) return
        fg = pkg
        screen?.onForeground(pkg, e.className?.toString() ?: "")
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
        if (Engine.onDuty() || Curtain.breakLocked()) check()
    }

    private val cfg get() = Engine.cfg
    private val readSec get() = cfg.optInt("phone_read_sec", 15)
    private fun screenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun check() {
        nudgeAlerts()
        val on = screenOn()
        // Descanso: el telón tapa lo que no sea llamadas, banco, mapas o mensajes (ver [Curtain]).
        if (on && Curtain.breakLocked() && Curtain.visible == null && fg.isNotEmpty() && !Curtain.breakAllowed(this, fg) &&
            !Curtain.inCall(this) && !Updater.confirming() && !Strikes.busy()) {
            Curtain.show(this, "break")
        }
        if (!Engine.onDuty() || !on) {
            usedMs = 0
            lastAccrual = 0
            warned = false
            distractPkg = null
            WhatsApp.away()
            return
        }
        val p = Policy.at()
        if (!p.phone) return   // esta actividad no vigila el teléfono (lock = "pc" o "none")
        val now = System.currentTimeMillis()
        val level = Engine.nudge()
        val essential = isEssential(fg)
        val recentLock = now - lastLock < REENTRY_MS
        if (level >= 3 && !essential) {
            if (now - lastStrictWarn > 8000) {   // un solo aviso por cada vez que lo prendes
                lastStrictWarn = now
                warnThen("🛑 Ve a la compu", "Llevas rato sin arrancar: en 5 s se bloquea el teléfono.", 5) { lock() }
            }
            return
        }
        if (fg in WhatsApp.PKGS && cfg.optBoolean("wa_guard", true)) {
            lastAccrual = now
            if (!WhatsApp.tick(p)) {
                if (!waWarned && !p.chatFree && WhatsApp.leftSec() in 1..60) {
                    waWarned = true
                    Notifier.alert(this, "💬 Te queda 1 min de WhatsApp", "En este pomodoro. Cierra la conversación.", vibrate = false)
                }
                return
            }
            if (distractPkg != fg) {
                distractPkg = fg
                val pkg = fg
                warnThen("📵 Cierra WhatsApp", WhatsApp.why() + "\nEn ${minOf(readSec, 8)} s te saco.", minOf(readSec, 8)) {
                    if (fg == pkg && WhatsApp.tick(Policy.at())) performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            return
        }
        WhatsApp.away()
        waWarned = waWarned && WhatsApp.leftSec() < 60
        if (cfg.optBoolean("phone_distract", true) && Policy.distracting(this, fg, p)) {
            val pkg = fg
            val name = appName(pkg)
            val recent = kicks.getOrPut(pkg) { mutableListOf() }.apply { removeAll { now - it > REENTRY_MS } }
            if (level >= 2 || recent.isNotEmpty()) {   // ya te había sacado (o vas tarde): sin tiempo de gracia
                performGlobalAction(GLOBAL_ACTION_HOME)
                recent += now
                if (recent.size >= 3) {
                    recent.clear()
                    Notifier.alert(this, "🔒 Tercera vez en $name", "Bloqueé el teléfono. Es hora de trabajar.")
                    lock()
                } else {
                    Notifier.alert(this, "📵 Otra vez $name", "Te saqué. Si vuelves a entrar, bloqueo el teléfono.")
                }
                distractPkg = pkg
                return
            }
            if (distractPkg != pkg) {
                distractPkg = pkg
                warnThen("📵 $name en horario de trabajo", "En $readSec s te saco de $name.") {
                    if (fg == pkg) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        kicks.getOrPut(pkg) { mutableListOf() } += System.currentTimeMillis()
                    }
                }
            }
            return
        }
        distractPkg = null
        if (!cfg.optBoolean("phone_work_guard", true)) return
        // El banco, los mapas, lo esencial y lo que permite la actividad no cuentan como «estar en el teléfono».
        val dt = if (lastAccrual > 0) minOf(now - lastAccrual, 10_000L) else 0L
        lastAccrual = now
        if (Policy.allowed(this, fg, p) || fg.isEmpty()) return
        usedMs += dt
        if (Offpc.onPhoneUse(this, usedMs, appName(fg))) return   // era lejos de las pantallas: se acaba el pase
        val mins = usedMs / 60000.0
        var warnMin = cfg.optDouble("phone_work_warn_min", 3.0)
        var lockMin = cfg.optDouble("phone_work_lock_min", 5.0)
        if ((level >= 2 || recentLock) && !essential) {   // modo estricto o recién te bloqueé
            warnMin = minOf(warnMin, 0.5)
            lockMin = minOf(lockMin, 1.5)
        }
        if (mins >= lockMin) {
            usedMs = 0
            warned = false
            warnThen("🔒 Deja el teléfono", "Llevas ${mins.toInt()} min en él en horario de trabajo.\nEn $readSec s se bloquea.") {
                lock()
            }
        } else if (mins >= warnMin && !warned) {
            warned = true
            Notifier.alert(this, "👀 Estás en el teléfono",
                "Es hora de trabajo. Si sigues, en ${Engine.fmt((lockMin - warnMin) * 60)} lo bloqueo.")
        }
    }

    /** Avisa cuando la compu sube la presión por tardar en arrancar. */
    private fun nudgeAlerts() {
        val level = Engine.nudge()
        if (level > lastNudge) {
            when (level) {
                1 -> Notifier.alert(this, "⏰ Ya toca trabajar", "Llevas un rato sin arrancar en la compu.")
                2 -> Notifier.alert(this, "📵 Modo estricto", "Te saco de todo lo que no sea esencial hasta que arranques.")
                else -> Notifier.alert(this, "🛑 Ve a la compu", "El teléfono se bloquea en cuanto lo uses.")
            }
        }
        lastNudge = level
    }

    private fun isEssential(pkg: String): Boolean {
        val extra = cfg.optJSONArray("phone_essential")
        return pkg in essentialBase || Apps.category(this, pkg) in Apps.ALWAYS ||
            (extra != null && (0 until extra.length()).any { extra.optString(it) == pkg })
    }

    /** Primero avisa (con tiempo para leer) y después ejecuta `action` si sigue aplicando. */
    private fun warnThen(title: String, text: String, secs: Int = readSec, action: () -> Unit) {
        Notifier.alert(this, title, text)
        handler.postDelayed({ if (Engine.onDuty() && screenOn()) action() }, secs * 1000L)
    }

    private fun lock() {
        lastLock = System.currentTimeMillis()
        usedMs = 0
        warned = false
        performGlobalAction(GLOBAL_ACTION_HOME)
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /** Se acabó el descanso: si a los N segundos sigues en el teléfono, avisa y lo bloquea. */
    fun breakOver() {
        if (cfg.optString("phone_break_end", "lock") != "lock") return
        val delay = cfg.optInt("phone_lock_delay_sec", 45)
        handler.postDelayed({
            if (Engine.onDuty() && screenOn()) {
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
        const val REENTRY_MS = 10 * 60 * 1000L   // volver a entrar antes de esto cuenta como reincidir
        val ESSENTIAL = setOf("com.android.settings", "com.android.phone", "com.android.incallui",
            "com.google.android.dialer", "com.android.contacts", "com.google.android.apps.messaging",
            "com.android.mms", "com.miui.securitycenter", "com.android.permissioncontroller")
    }
}
