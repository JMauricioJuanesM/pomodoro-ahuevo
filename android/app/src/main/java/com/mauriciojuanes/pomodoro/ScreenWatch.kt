package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vigilancia de lo que se ve en pantalla, para evitar recaídas. Cubre todas las apps (también las que
 * instales después), no solo una lista.
 *
 * Sin gastar de más:
 *  - captura cuando cambia la pantalla (otra app, otra ventana, scroll o contenido nuevo), con un mínimo
 *    de [MIN_GAP_MS] entre capturas, y además cada [RISK_INTERVAL_MS] en las apps de riesgo (videos) o
 *    [INTERVAL_MS] en las demás;
 *  - si la captura se ve igual a la última revisada (huella de 64 bits), no se manda al servidor;
 *  - el servidor la revisa en memoria con NudeNet y no la guarda (`server/screen.py`).
 *
 * Además, sin capturas: el nombre y paquete de cada app y lo que dice la barra de direcciones de los
 * navegadores (URL o búsqueda) se comparan con [AdultDb].
 *
 * Según lo que encuentre:
 *  - explícito (imagen, URL o app de la lista) → te saca, bloquea la pantalla, cancela los desbloqueos,
 *    avisa al bot y suma una advertencia a la app; a la segunda, la desinstala ([Strikes]);
 *  - sugerente → aviso; si se repite [SUGGESTIVE_LIMIT] veces en [SUGGESTIVE_WINDOW_MS], como explícito;
 *  - pantalla negra en un navegador (pestaña de incógnito) → te saca y avisa.
 *
 * No se revisa: videollamadas (cualquier app con el audio en modo llamada, y las pantallas de llamada de
 * WhatsApp) ni los mensajes de «ver una vez» de WhatsApp. Las fotos normales de WhatsApp sí, pero
 * WhatsApp nunca se desinstala.
 */
class ScreenWatch(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var busy = false
    private var cooldownUntil = 0L
    private var lastShot = 0L
    private var lastSentHash = 0L
    private var lastSentAt = 0L
    private var lastSentPkg = ""
    private var lastUrlCheck = 0L
    private var lastUrl = ""
    private var scheduled = false
    private val warnedApps = mutableMapOf<String, Long>()
    private val suggestive = ArrayDeque<Long>()
    private val browsers: Set<String> by lazy {
        val pm = service.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
            .map { it.activityInfo.packageName }.toSet() + KNOWN_BROWSERS
    }

    var fg = ""
    private var fgClass = ""

    private val loop = object : Runnable {
        override fun run() {
            try {
                val gap = if (risky(fg)) RISK_INTERVAL_MS else INTERVAL_MS
                if (System.currentTimeMillis() - lastShot >= gap) check()
            } finally {
                handler.postDelayed(this, RISK_INTERVAL_MS)
            }
        }
    }

    private val shot = Runnable {
        scheduled = false
        check()
    }

    fun start() = handler.postDelayed(loop, RISK_INTERVAL_MS)

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        net.shutdownNow()
    }

    /** Cambió la ventana al frente (otra app u otra pantalla de la misma). */
    fun onForeground(pkg: String, cls: String) {
        fg = pkg
        fgClass = cls
        if (!enabled()) return
        checkApp(pkg)
        checkUrl(force = true)
        schedule(WINDOW_DELAY_MS)
    }

    /** Cambió algo dentro de la app al frente (scroll, imagen nueva…). */
    fun onContent(pkg: String) {
        if (pkg != fg || !enabled()) return
        checkUrl(force = false)
        schedule(CONTENT_DELAY_MS)
    }

    private fun schedule(delay: Long) {
        if (scheduled) return
        scheduled = true
        val wait = maxOf(delay, lastShot + MIN_GAP_MS - System.currentTimeMillis())
        handler.postDelayed(shot, wait)
    }

    private fun enabled() = Engine.cfg.optBoolean("phone_screen_watch", true)

    private fun risky(pkg: String) = pkg in browsers || pkg in RISK_APPS || pkg in cfgList("phone_watch_apps")

    private fun cfgList(key: String): Set<String> {
        val arr = Engine.cfg.optJSONArray(key) ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }

    /** Lo que no se revisa nunca (videollamadas, «ver una vez», el banco, esta app…). */
    private fun excluded(pkg: String, cls: String): Boolean {
        if (pkg.isEmpty() || pkg == service.packageName || pkg in SKIP_APPS || BankMode.isBank(pkg)) return true
        if (pkg in cfgList("phone_watch_exclude")) return true
        val mode = service.getSystemService(AudioManager::class.java)?.mode
        if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) return true
        if (pkg.startsWith("com.whatsapp")) {
            val c = cls.lowercase()
            if ("voip" in c || "call" in c || "viewonce" in c || "view_once" in c) return true
        }
        return false
    }

    private fun watched(pkg: String) = enabled() && (Engine.cfg.optBoolean("phone_watch_all", true) || risky(pkg)) &&
        !excluded(pkg, fgClass)

    // ---------- sin capturas: nombre de la app y barra de direcciones ----------
    private fun checkApp(pkg: String) {
        if (excluded(pkg, fgClass)) return
        val name = appName(pkg)
        if (!AdultDb.isAdultApp(pkg, name)) return
        val now = System.currentTimeMillis()
        if (now - (warnedApps[pkg] ?: 0) < 5000) return   // varias ventanas de la misma app al abrirla
        warnedApps[pkg] = now
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (Strikes.strike(service, pkg, name, "adult_app")) return
        Notifier.alert(service, "🚫 $name es de contenido sexual", "Te saqué. Si la vuelves a abrir, la desinstalo.")
        Sync.alert("adult_app", pkg, name, "home")
    }

    private fun checkUrl(force: Boolean) {
        val pkg = fg
        if (pkg !in browsers) return
        val now = System.currentTimeMillis()
        if (!force && now - lastUrlCheck < URL_GAP_MS) return
        lastUrlCheck = now
        val url = runCatching { urlBar(service.rootInActiveWindow) }.getOrNull() ?: return
        if (url == lastUrl) return
        lastUrl = url
        when (AdultDb.urlLevel(url)) {
            "explicit" -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                act(pkg, appName(pkg), "url", "🚫 Página de contenido sexual en ${appName(pkg)}")
            }
            "suggestive" -> suggestiveHit(pkg, appName(pkg), "🔎 Esa búsqueda no", "Es la puerta de entrada. Sal de ahí.")
        }
    }

    /** El texto de la barra de direcciones del navegador al frente, o null. */
    private fun urlBar(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        for (id in URL_IDS) {
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id").firstOrNull()?.text?.let { return it.toString() }
        }
        // Navegador desconocido: el primer campo de texto de arriba que parezca una dirección.
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 150) {
            val n = queue.removeFirst()
            val t = n.text?.toString()
            if (n.isEditable && t != null && ('.' in t || ' ' in t) && t.length > 3) return t
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    // ---------- capturas ----------
    private fun check() {
        val pkg = fg
        val cls = fgClass
        if (android.os.Build.VERSION.SDK_INT < 30) return   // capturas por accesibilidad: Android 11+
        if (busy || !watched(pkg) || !Sync.configured() || System.currentTimeMillis() < cooldownUntil) return
        if (!service.getSystemService(PowerManager::class.java).isInteractive) return
        if (System.currentTimeMillis() - lastShot < MIN_GAP_MS) return
        lastShot = System.currentTimeMillis()
        busy = true
        service.takeScreenshot(Display.DEFAULT_DISPLAY, net, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val small = try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    r.hardwareBuffer.close()
                    bmp?.let { shrink(it) }
                } catch (e: Exception) {
                    null
                }
                if (small == null) {
                    handler.post { busy = false }
                    return
                }
                val now = System.currentTimeMillis()
                val same = pkg == lastSentPkg && now - lastSentAt < RESEND_MS &&
                    java.lang.Long.bitCount(small.hash xor lastSentHash) <= SAME_BITS
                val result = when {
                    small.dark -> "private"
                    same -> null   // ya se revisó esta misma pantalla
                    else -> runCatching { Sync.checkScreen(small.jpeg) }.getOrNull()?.also {
                        lastSentHash = small.hash
                        lastSentAt = now
                        lastSentPkg = pkg
                    }
                }
                handler.post {
                    busy = false
                    if (result != null && fg == pkg && !excluded(pkg, cls)) handle(result, pkg)
                }
            }

            override fun onFailure(code: Int) {
                handler.post { busy = false }
            }
        })
    }

    private class Small(val jpeg: ByteArray, val dark: Boolean, val hash: Long)

    /** Captura reducida a [SIDE] px, si está casi toda negra y su huella (dHash de 64 bits). */
    private fun shrink(src: Bitmap): Small {
        val scale = SIDE.toFloat() / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        src.recycle()
        var dark = 0
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        for (c in px) if ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff) < 30) dark++
        val tiny = Bitmap.createScaledBitmap(small, 9, 8, true)
        val g = IntArray(72)
        tiny.getPixels(g, 0, 9, 0, 0, 9, 8)
        tiny.recycle()
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = g[y * 9 + x]
            val b = g[y * 9 + x + 1]
            if ((a shr 16 and 0xff) + (a shr 8 and 0xff) + (a and 0xff) > (b shr 16 and 0xff) + (b shr 8 and 0xff) + (b and 0xff)) {
                hash = hash or (1L shl (y * 8 + x))
            }
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        small.recycle()
        return Small(out.toByteArray(), dark > px.size * 0.97, hash)
    }

    private fun handle(level: String, pkg: String) {
        val name = appName(pkg)
        when (level) {
            "explicit" -> act(pkg, name, "explicit", "🚫 Contenido explícito en $name")
            "suggestive" -> suggestiveHit(pkg, name, "👀 Contenido sugerente en $name", "Sal de ahí antes de que escale.")
            "private" -> if (pkg in browsers && Engine.cfg.optBoolean("phone_block_private", true)) {
                cooldownUntil = System.currentTimeMillis() + 20_000
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Notifier.alert(service, "🕶 Incógnito en $name", "Las pestañas privadas no se pueden revisar: ciérralas.")
                Sync.alert("private", pkg, name, "home")
            }
        }
    }

    private fun suggestiveHit(pkg: String, name: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        suggestive.addLast(now)
        while (suggestive.isNotEmpty() && now - suggestive.first() > SUGGESTIVE_WINDOW_MS) suggestive.removeFirst()
        if (suggestive.size >= SUGGESTIVE_LIMIT) {
            suggestive.clear()
            act(pkg, name, "suggestive", "🚫 Mucho contenido sugerente en $name")
        } else {
            Notifier.alert(service, title, text, vibrate = true)
        }
    }

    /** Te saca, bloquea la pantalla, cancela los desbloqueos, avisa al bot y suma una advertencia a la app. */
    private fun act(pkg: String, name: String, level: String, title: String) {
        cooldownUntil = System.currentTimeMillis() + 30_000
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (Blocks.relocks().isNotEmpty()) Blocks.relockAll()
        if (Strikes.strike(service, pkg, name, level)) return   // la desinstala: no bloquear encima del diálogo
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        Notifier.alert(service, title, "Te saqué y bloqueé el teléfono. Respira, levántate y sal del cuarto.")
        Sync.alert(level, pkg, name, "lock")
    }

    private fun appName(pkg: String): String = runCatching {
        service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    companion object {
        const val RISK_INTERVAL_MS = 6000L
        const val INTERVAL_MS = 15_000L
        const val MIN_GAP_MS = 2500L
        const val WINDOW_DELAY_MS = 1200L
        const val CONTENT_DELAY_MS = 1500L
        const val URL_GAP_MS = 1500L
        const val RESEND_MS = 60_000L      // una pantalla igual se vuelve a revisar pasado esto
        const val SAME_BITS = 4            // bits distintos en la huella para considerarla otra pantalla
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
        // Se revisan más seguido (videos y scroll infinito).
        val RISK_APPS = setOf(
            "com.instagram.android", "com.instagram.lite", "com.facebook.katana", "com.facebook.lite",
            "com.google.android.youtube", "app.revanced.android.youtube", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.twitter.android", "com.reddit.frontpage", "com.pinterest",
            "com.snapchat.android", "com.tumblr", "com.miui.gallery", "com.google.android.apps.photos",
            "com.miui.videoplayer", "org.telegram.messenger", "com.discord",
        )
        // Llamadas y videollamadas, y la cámara (lo tuyo).
        val SKIP_APPS = setOf(
            "com.android.incallui", "com.google.android.dialer", "com.android.phone", "com.google.android.apps.tachyon",
            "com.android.camera", "com.google.android.GoogleCamera",
        )
        // Barra de direcciones de los navegadores conocidos.
        val URL_IDS = listOf("url_bar", "mozac_browser_toolbar_url_view", "url_field", "location_bar_edit_text",
            "omnibarTextInput", "url", "search_box", "address_bar_edit_text", "url_edit_text")
    }
}
