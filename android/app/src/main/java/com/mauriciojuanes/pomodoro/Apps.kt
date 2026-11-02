package com.mauriciojuanes.pomodoro

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * ¿Qué tipo de app es? Decide qué se permite en cada actividad del horario ([Policy]).
 *
 * En orden: lo que tú digas (`phone_app_categories` en la config), lo que dedujo la IA del ciclo nocturno
 * (`app_categories` del hub, ver [Hub]), y si no, reglas por
 * paquete, nombre y la categoría que declara la propia app. Así, p. ej., cualquier app de banco nueva
 * cuenta como `finance` sin tener que agregarla a mano.
 */
object Apps {
    const val ESSENTIAL = "essential"   // llamadas, SMS, inicio, ajustes
    const val FINANCE = "finance"       // bancos, pagos, inversiones: nunca se bloquean
    const val MESSAGING = "messaging"   // WhatsApp y similares: con tope por pomodoro (ver [WhatsApp])
    const val MAPS = "maps"
    const val WORK = "work"             // correo, documentos, calendario, autenticadores, tareas
    const val EDUCATION = "education"
    const val READING = "reading"
    const val SHOPPING = "shopping"
    const val AUDIO = "audio"           // música y podcasts
    const val SOCIAL = "social"
    const val VIDEO = "video"
    const val GAME = "game"
    const val OTHER = "other"

    /** Lo que nunca se bloquea por trabajo, en ninguna actividad. */
    val ALWAYS = setOf(ESSENTIAL, FINANCE, MAPS)
    /** Lo que distrae salvo que la actividad lo permita. */
    val DISTRACTING = setOf(SOCIAL, VIDEO, GAME)

    val LABELS = linkedMapOf(
        FINANCE to "💳 Banco y pagos", MESSAGING to "💬 Mensajes", MAPS to "🗺 Mapas", WORK to "💼 Trabajo y oficina",
        EDUCATION to "🎓 Estudio", READING to "📖 Lectura", SHOPPING to "🛒 Compras", AUDIO to "🎵 Música y podcasts",
        SOCIAL to "📱 Redes sociales", VIDEO to "📺 Video", GAME to "🎮 Juegos", OTHER to "❔ Otras",
    )

    private val FINANCE_PKG = listOf("bank", "banco", "banca", "bbva", "banorte", "santander", "hsbc", "citibanamex",
        "banamex", "scotiabank", "bancoppel", "bancoazteca", "inbursa", "banregio", "afirme", "hey.banco", "heybanco",
        "nu.production", "stori", "mercadopago", "paypal", "klar", "spin", "oxxo", "uala", "vexi", "plata", "didi.pay",
        "rappipay", "openbank", "albo", "fondeadora", "cetesdirecto", "gbm", "bitso", "binance", "wise", "revolut",
        "finanzas", "wallet", "sat.gob", "afore", "clip.", "billpocket", "cuentas", "money", "fintual", "kueski")
    private val FINANCE_NAME = listOf("banco", "bank", "bbva", "banorte", "santander", "hsbc", "nu méxico", "nu mexico",
        "stori", "mercado pago", "paypal", "klar", "spin by oxxo", "hey banco", "ualá", "uala", "afore", "sat móvil",
        "cetes", "gbm", "bitso", "fondeadora", "billetera", "wallet", "pagos")
    private val MESSAGING_PKG = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.facebook.orca")
    private val MAPS_PKG = listOf("maps", "waze", "uber", "didi", "cabify", "moovit", "citymapper", "rappi", "ubereats")
    private val WORK_PKG = listOf("android.gm", "outlook", "office", "docs", "sheets", "slides", "drive", "calendar",
        "keep", "notion", "slack", "teams", "zoom", "meet", "authenticator", "authy", "ticktick", "todoist", "trello",
        "github", "jira", "confluence", "linkedin", "onenote", "evernote", "dropbox", "acrobat", "pdf", "scanner",
        "camscanner", "calculator", "deskclock", "clock", "contacts", "files", "filemanager", "fileexplorer", "1password",
        "bitwarden", "lastpass", "openproject", "claude", "chatgpt", "gemini", "termux", "anydesk", "teamviewer")
    private val EDU_PKG = listOf("duolingo", "coursera", "udemy", "platzi", "khan", "anki", "quizlet", "edx", "classroom",
        "brilliant", "babbel", "busuu", "photomath")
    private val READ_PKG = listOf("kindle", "books", "pocket", "readera", "moon", "librera", "wattpad", "medium", "news",
        "feedly", "inoreader", "scribd", "goodreads", "audible", "blinkist")
    private val SHOP_PKG = listOf("amazon.mShop", "mercadolibre", "shopee", "aliexpress", "temu", "shein", "walmart",
        "liverpool", "coppel", "costco", "chedraui", "soriana", "ebay", "etsy")
    private val AUDIO_PKG = listOf("spotify", "music", "deezer", "soundcloud", "podcast", "pocketcasts", "castbox", "tidal")
    private val SOCIAL_PKG = listOf("instagram", "facebook.katana", "facebook.lite", "twitter", "tiktok",
        "musically", "trill", "snapchat", "reddit", "pinterest", "tumblr", "threads", "bereal", "lemon8", "discord",
        "tinder", "bumble", "hinge", "grindr", "badoo", "happn", "quora", "9gag", "vk.", "weibo", "likee", "kwai")
    private val VIDEO_PKG = listOf("youtube", "netflix", "primevideo", "avod", "disney", "hbo", "wbd.stream", "twitch", "vix",
        "crunchyroll", "pluto", "plex", "mubi", "paramount", "kick.mobile", "vlc", "mxtech", "tubi", "viki")

    private val cache = mutableMapOf<String, String>()
    private var cacheKey = 0

    /** Categoría de `pkg` (con caché; se borra cuando cambian las categorías de la config). */
    fun category(ctx: Context, pkg: String): String {
        val key = System.identityHashCode(Engine.cfg) * 31 + Store.aiCategoriesRev
        if (key != cacheKey) {
            cache.clear()
            cacheKey = key
        }
        return cache.getOrPut(pkg) { compute(ctx, pkg) }
    }

    private fun compute(ctx: Context, pkg: String): String {
        Engine.cfg.optJSONObject("phone_app_categories")?.optString(pkg)?.takeIf { it.isNotEmpty() }?.let { return it }
        Store.aiCategories.optString(pkg).takeIf { it.isNotEmpty() }?.let { return it }
        if (BankMode.isBank(pkg)) return FINANCE
        if (pkg in MESSAGING_PKG) return MESSAGING
        if (pkg == ctx.packageName || isEssential(ctx, pkg)) return ESSENTIAL
        val info = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val name = info?.let { ctx.packageManager.getApplicationLabel(it).toString().lowercase() } ?: ""
        val p = pkg.lowercase()
        fun has(list: List<String>) = list.any { p.contains(it.lowercase()) }
        return when {
            has(FINANCE_PKG) || FINANCE_NAME.any { name.contains(it) } -> FINANCE
            info != null && (info.category == ApplicationInfo.CATEGORY_GAME ||
                info.flags and ApplicationInfo.FLAG_IS_GAME != 0) -> GAME
            has(SOCIAL_PKG) -> SOCIAL
            has(VIDEO_PKG) -> VIDEO
            has(MAPS_PKG) || info?.category == ApplicationInfo.CATEGORY_MAPS -> MAPS
            has(EDU_PKG) -> EDUCATION
            has(READ_PKG) || info?.category == ApplicationInfo.CATEGORY_NEWS -> READING
            has(SHOP_PKG) -> SHOPPING
            has(AUDIO_PKG) || info?.category == ApplicationInfo.CATEGORY_AUDIO -> AUDIO
            has(WORK_PKG) || info?.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> WORK
            info?.category == ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
            info?.category == ApplicationInfo.CATEGORY_VIDEO -> VIDEO
            else -> OTHER
        }
    }

    private var essentialPkgs: Set<String>? = null

    private fun isEssential(ctx: Context, pkg: String): Boolean {
        val set = essentialPkgs ?: run {
            val pm = ctx.packageManager
            val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
            val dialer = ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
            val sms = android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)
            (home + listOfNotNull(dialer, sms) + GuardService.ESSENTIAL).toSet().also { essentialPkgs = it }
        }
        return pkg in set
    }

    /** Apps con ícono en el lanzador (paquete → nombre), para que la IA las clasifique de noche. */
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }
}
