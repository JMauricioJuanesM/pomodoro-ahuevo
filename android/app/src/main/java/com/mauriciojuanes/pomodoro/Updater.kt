package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * La app se actualiza sola: el VPS compila, prueba y publica (`release` en el hub, el APK en `/api/apk`), y en
 * cuanto el teléfono se entera lo descarga, revisa su huella y lo instala. No hace falta la compu.
 *
 * Android pide confirmar las instalaciones de apps que no vienen de la tienda; la accesibilidad pulsa
 * «Actualizar» como en [Strikes]. Para no cortarte nada, espera a que no estés en un pomodoro y tengas la
 * pantalla prendida. Tras instalarse, [SelfTest] corre las pruebas y las reporta: si fallan, el ciclo
 * nocturno se entera y publica la versión anterior con un número mayor.
 *
 * Necesita «instalar apps desconocidas» para esta app; la compu lo da por ADB
 * (`appops set com.mauriciojuanes.pomodoro REQUEST_INSTALL_PACKAGES allow`).
 */
object Updater {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.miui.global.packageinstaller", "com.miui.securitycenter")
    private val CONFIRM = setOf("actualizar", "instalar", "update", "install", "continuar", "continue", "siguiente",
        "next", "aceptar", "ok", "listo", "done")
    private const val CONFIRM_MS = 90_000L
    private const val MAX_FAILS = 3
    private const val BACKOFF_MS = 10 * 60_000L
    @Volatile private var busy = false
    @Volatile private var confirmUntil = 0L
    private var pending: JSONObject? = null
    var status = ""
        private set

    fun installedCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Llegó la versión publicada (desde [Hub.onRemote]). */
    fun onRelease(ctx: Context, rel: JSONObject) {
        val code = rel.optLong("version_code")
        // Simulacro de rollback: esa versión reporta sus pruebas como fallidas (ver [SelfTest]).
        Store.setLong("selftest_fail_code", if (rel.optBoolean("test_fail")) code else 0)
        if (code <= installedCode(ctx) || !Engine.cfg.optBoolean("auto_update", true)) {
            pending = null
            return
        }
        pending = rel
        status = "Hay versión nueva: ${rel.optString("version_name")}"
        maybeInstall(ctx)
    }

    /** ¿Hay una versión nueva esperando? (para el botón «Instalar ahora» de Ajustes) */
    fun hasPending() = pending != null

    /**
     * ¿Android la deja instalar sin preguntar? Solo si esta app es su propia instaladora (después de la
     * primera actualización hecha por ella misma; las instaladas por ADB no cuentan).
     */
    private fun silent(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && Store.long("update_needs_dialog") == 0L && runCatching {
        ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName
    }.getOrDefault(false)

    /**
     * Cada tanto (desde [PomodoroService]): si hay pendiente y es buen momento, instala. Sin diálogo, ya (son
     * unos segundos); con diálogo, con la pantalla prendida y fuera de un pomodoro (en el descanso sí, aunque
     * esté el telón). Tras un fallo espera (ver [fail]). `now` (el botón de Ajustes) no espera.
     */
    fun maybeInstall(ctx: Context, now: Boolean = false) {
        val rel = pending ?: return
        if (busy) return
        if (!now && fails(ctx) > 0 && System.currentTimeMillis() < Store.long("update_retry_at")) return
        if (!now && !silent(ctx)) {
            if (Engine.working() || Curtain.visible !in setOf(null, "break", "mind")) return
            if (!ctx.getSystemService(PowerManager::class.java).isInteractive) return
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "Falta el permiso para instalar (la compu lo da por ADB)"
            return
        }
        busy = true
        io.execute {
            try {
                val apk = File(ctx.cacheDir, "Faro.apk")
                Sync.download("/api/apk", apk)
                val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
                val want = rel.optString("sha256")
                if (want.isNotEmpty() && !sha.equals(want, true)) throw IllegalStateException("la huella no coincide")
                handler.post { install(ctx, apk, rel.optLong("version_code")) }
            } catch (e: Exception) {
                fail(ctx, rel.optLong("version_code"), e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun install(ctx: Context, apk: File, code: Long) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31 && Store.long("update_needs_dialog") == 0L) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("Faro.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) } ; }
                val intent = Intent(ctx, InstallReceiver::class.java).putExtra("code", code)
                val sender = PendingIntent.getBroadcast(ctx, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
                status = "Instalando…"
                Store.setLong("update_started", System.currentTimeMillis())
                s.commit(sender)
            }
        } catch (e: Exception) {
            fail(ctx, code, e.message ?: e.javaClass.simpleName)
        }
    }

    fun fail(ctx: Context, code: Long, why: String) {
        busy = false
        status = "No se pudo actualizar: $why"
        // MIUI niega la instalación sin diálogo («INSTALL_FAILED_ABORTED: Permission denied»): desde ahora con
        // diálogo (pantalla prendida, la accesibilidad lo acepta). Va antes de lo de «abortada»: con ese mensaje
        // el aviso dice ABORTED, y si se ignoraba, se reintentaba sin diálogo para siempre (23 veces, 2–3 nov).
        val denied = "permission denied" in why.lowercase()
        if (denied && Store.long("update_needs_dialog") == 0L) {
            Store.setLong("update_needs_dialog", 1)
            Sync.alert("update_failed", ctx.packageName, "Faro $code", "update", "sin diálogo no se puede: ahora con diálogo")
            return   // se reintenta ya, con diálogo, sin contarlo como fallo
        }
        // Abortada (p. ej. se apagó la pantalla con el diálogo abierto): se reintenta sin contarla.
        if ("ABORTED" in why && !denied) return
        // Fallos seguidos desde la versión instalada, aunque sean de versiones publicadas distintas (el 2-nov fueron 22
        // de la 15 a la 21): se espera 10 min, luego 20, y a la tercera un día, con aviso para instalarla a mano.
        val n = fails(ctx) + 1
        Store.setLong("update_fails_from", installedCode(ctx))
        Store.setLong("update_fails", n)
        val wait = if (n >= MAX_FAILS) 86_400_000L else BACKOFF_MS shl (n - 1).toInt()
        Store.setLong("update_retry_at", System.currentTimeMillis() + wait)
        Sync.alert("update_failed", ctx.packageName, "Faro $code", "update", why.take(200))
        if (n >= MAX_FAILS) Notifier.alert(ctx, "Faro no pudo actualizarse sola",
            "Falló $n veces seguidas (${why.take(80)}). Lo vuelve a intentar mañana; o ve a Ajustes → Instalar ahora.", vibrate = false)
    }

    /** Fallos seguidos desde la versión instalada (se olvidan en cuanto se instala una nueva). */
    private fun fails(ctx: Context): Long =
        if (Store.long("update_fails_from") == installedCode(ctx)) Store.long("update_fails") else 0

    /** Android pidió confirmar: abre su diálogo y que la accesibilidad lo acepte. */
    fun needsUser(ctx: Context, confirm: Intent) {
        // MIUI pide confirmar aunque la app sea su propia instaladora: desde ahora, solo con la pantalla prendida.
        Store.setLong("update_needs_dialog", 1)
        confirmUntil = System.currentTimeMillis() + CONFIRM_MS
        runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Se abrió una ventana (desde [GuardService]): si es el instalador que pedimos, pulsa «Actualizar». */
    fun onWindow(s: AccessibilityService, pkg: String) {
        if (System.currentTimeMillis() > confirmUntil || pkg !in INSTALLERS) return
        for (i in 0..5) handler.postDelayed({ confirm(s) }, 400L + i * 500L)
    }

    private fun confirm(s: AccessibilityService) {
        if (System.currentTimeMillis() > confirmUntil) return
        val root = s.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByViewId("android:id/button1") +
            CONFIRM.flatMap { root.findAccessibilityNodeInfosByText(it) }
        val btn = nodes.firstOrNull { n ->
            val t = n.text?.toString()?.trim()?.lowercase() ?: ""
            n.isEnabled && (n.viewIdResourceName == "android:id/button1" || t in CONFIRM)
        } ?: return
        var target: AccessibilityNodeInfo? = btn
        while (target != null && !target.isClickable) target = target.parent
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun confirming() = System.currentTimeMillis() < confirmUntil

    fun done() {
        busy = false
        confirmUntil = 0
    }
}

/** Resultado de la instalación (lo manda el instalador de Android). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getLongExtra("code", 0)
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) Updater.needsUser(ctx, confirm) else Updater.fail(ctx, code, "sin diálogo")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.done()   // normalmente ya nos reiniciaron
            else -> Updater.fail(ctx, code, "estado $st: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
        }
    }
}
