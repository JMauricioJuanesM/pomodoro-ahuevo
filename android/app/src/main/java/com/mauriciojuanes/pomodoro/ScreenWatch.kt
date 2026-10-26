package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vigilancia de lo que se ve en pantalla, para evitar recaídas.
 *
 * Mientras estás en una app de riesgo (navegadores, redes, galería…), cada [INTERVAL_MS] toma una captura
 * con la accesibilidad, la reduce a [SIDE] px y la manda a tu servidor, que la analiza en memoria con
 * NudeNet y no la guarda (`server/screen.py`). Según lo que vea:
 *  - explícito → te saca, bloquea la pantalla, cancela los desbloqueos vigentes y avisa al bot;
 *  - sugerente → aviso; si se repite [SUGGESTIVE_LIMIT] veces en [SUGGESTIVE_WINDOW_MS], lo mismo que explícito;
 *  - pantalla negra en un navegador (pestaña de incógnito, que no se deja capturar) → te saca y avisa.
 */
class ScreenWatch(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var busy = false
    private var cooldownUntil = 0L
    private var lastShot = 0L
    private val suggestive = ArrayDeque<Long>()
    private val browsers: Set<String> by lazy {
        val pm = service.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
            .map { it.activityInfo.packageName }.toSet() + KNOWN_BROWSERS
    }

    var fg = ""

    private val loop = object : Runnable {
        override fun run() {
            try {
                check()
            } finally {
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
    }

    fun start() = handler.postDelayed(loop, INTERVAL_MS)

    fun stop() {
        handler.removeCallbacks(loop)
        net.shutdownNow()
    }

    /** Cambió la app al frente: si es de riesgo, revisarla pronto. */
    fun onForeground(pkg: String) {
        fg = pkg
        if (watched(pkg)) handler.postDelayed({ check() }, 1500)
    }

    private fun watched(pkg: String) = Engine.cfg.optBoolean("phone_screen_watch", true) &&
        (pkg in browsers || pkg in RISK_APPS || pkg in extraApps())

    private fun extraApps(): Set<String> {
        val arr = Engine.cfg.optJSONArray("phone_watch_apps") ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }

    private fun check() {
        val pkg = fg
        if (android.os.Build.VERSION.SDK_INT < 30) return   // capturas por accesibilidad: Android 11+
        if (busy || !watched(pkg) || !Sync.configured() || System.currentTimeMillis() < cooldownUntil) return
        if (!service.getSystemService(PowerManager::class.java).isInteractive) return
        if (System.currentTimeMillis() - lastShot < MIN_GAP_MS) return
        lastShot = System.currentTimeMillis()
        busy = true
        service.takeScreenshot(Display.DEFAULT_DISPLAY, net, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val jpeg = try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    r.hardwareBuffer.close()
                    bmp?.let { shrink(it) }
                } catch (e: Exception) {
                    null
                }
                if (jpeg == null) {
                    busy = false
                    return
                }
                val result = if (jpeg.second) "private" else runCatching { Sync.checkScreen(jpeg.first) }.getOrNull()
                handler.post {
                    busy = false
                    if (result != null && fg == pkg) handle(result, pkg)
                }
            }

            override fun onFailure(code: Int) {
                handler.post { busy = false }
            }
        })
    }

    /** (JPEG reducido, ¿está casi toda negra?) */
    private fun shrink(src: Bitmap): Pair<ByteArray, Boolean> {
        val scale = SIDE.toFloat() / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        src.recycle()
        var dark = 0
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        for (c in px) if ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff) < 30) dark++
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        small.recycle()
        return out.toByteArray() to (dark > px.size * 0.97)
    }

    private fun handle(level: String, pkg: String) {
        val name = appName(pkg)
        when (level) {
            "explicit" -> act(pkg, name, "explicit", "🚫 Contenido explícito en $name")
            "suggestive" -> {
                val now = System.currentTimeMillis()
                suggestive.addLast(now)
                while (suggestive.isNotEmpty() && now - suggestive.first() > SUGGESTIVE_WINDOW_MS) suggestive.removeFirst()
                if (suggestive.size >= SUGGESTIVE_LIMIT) {
                    suggestive.clear()
                    act(pkg, name, "suggestive", "🚫 Mucho contenido sugerente en $name")
                } else {
                    Notifier.alert(service, "👀 Contenido sugerente en $name", "Sal de ahí antes de que escale.", vibrate = true)
                }
            }
            "private" -> if (pkg in browsers && Engine.cfg.optBoolean("phone_block_private", true)) {
                cooldownUntil = System.currentTimeMillis() + 20_000
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Notifier.alert(service, "🕶 Incógnito en $name", "Las pestañas privadas no se pueden revisar: ciérralas.")
                Sync.alert("private", pkg, name, "home")
            }
        }
    }

    /** Te saca, bloquea la pantalla, cancela los desbloqueos y avisa al bot. */
    private fun act(pkg: String, name: String, level: String, title: String) {
        cooldownUntil = System.currentTimeMillis() + 30_000
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        if (Blocks.relocks().isNotEmpty()) Blocks.relockAll()
        Notifier.alert(service, title, "Te saqué y bloqueé el teléfono. Respira, levántate y sal del cuarto.")
        Sync.alert(level, pkg, name, "lock")
    }

    private fun appName(pkg: String): String = runCatching {
        service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    companion object {
        const val INTERVAL_MS = 6000L
        const val MIN_GAP_MS = 3000L
        const val SIDE = 320
        const val SUGGESTIVE_LIMIT = 3
        const val SUGGESTIVE_WINDOW_MS = 2 * 60 * 1000L
        // Por si Android no los reporta como navegadores (p. ej. desinstalados para tu usuario y reinstalados).
        val KNOWN_BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.brave.browser", "org.mozilla.firefox", "org.mozilla.focus",
            "com.opera.browser", "com.opera.mini.native", "com.microsoft.emmx", "com.duckduckgo.mobile.android",
            "com.mi.globalbrowser", "com.android.browser", "com.sec.android.app.sbrowser", "com.kiwibrowser.browser",
            "mark.via.gp", "com.UCMobile.intl", "com.vivaldi.browser", "org.torproject.torbrowser",
        )
        val RISK_APPS = setOf(
            "com.instagram.android", "com.instagram.lite", "com.facebook.katana", "com.facebook.lite",
            "com.google.android.youtube", "app.revanced.android.youtube", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.twitter.android", "com.reddit.frontpage", "com.pinterest",
            "com.snapchat.android", "com.tumblr", "com.miui.gallery", "com.google.android.apps.photos",
            "com.miui.videoplayer", "org.telegram.messenger", "com.discord",
        )
    }
}
