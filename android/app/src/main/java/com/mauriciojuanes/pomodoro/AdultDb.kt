package com.mauriciojuanes.pomodoro

/**
 * Lista de lo que se reconoce como contenido sexual sin ver la pantalla: nombres de apps, paquetes,
 * dominios y búsquedas. Es barato (texto), así que se revisa en cada cambio de pantalla.
 *
 * Se puede ampliar sin recompilar desde la configuración de la compu:
 *  - `phone_adult_apps`: paquetes exactos;
 *  - `phone_adult_words`: palabras extra (cuentan para apps y para URLs/búsquedas).
 */
object AdultDb {
    // Marcas y sitios: basta con que aparezcan en cualquier parte del texto.
    private val BRANDS = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "tube8", "spankbang", "eporner",
        "onlyfans", "fansly", "chaturbate", "stripchat", "bongacams", "camsoda", "livejasmin", "myfreecams",
        "brazzers", "rule34", "nhentai", "e-hentai", "motherless", "erome", "thothub", "fapello", "coomer.",
        "kemono.", "redgifs", "beeg.", "txxx", "hqporner", "imagefap", "literotica", "f95zone", "manyvids",
        "clips4sale", "camwhores", "simpcity", "noodlemagazine", "sxyprn", "porntrex", "cumlouder",
        "serviporno", "peachhub", "xchat", "sexchat", "spicychat", "candy.ai", "pornpics", "playboy", "18+",
    )

    // Palabras sueltas (con borde de palabra, para que «culo» no atrape a «artículo»).
    private val STRONG = Regex(
        "\\b(porn\\w*|porno|xxx|nsfw|hentai|sex|sexo|sexy\\s?(chat|cam)|nude|nudes|desnud[oa]s?|" +
            "erotic\\w*|er[oó]tic[oa]s?|tetas|culo|nalgas|verga|puta|putas|cachond\\w*|caliente(s)?\\s?chat|" +
            "hot\\s?chat|x\\s?chat|adult(o|os|s)?\\s?(chat|video|content|contenido)|fetish|fetiche|" +
            "ai\\s?girlfriend|novia\\s?(virtual|ia)|waifu|lust|lustful|spicy\\s?(ai|chat)|" +
            "packs?\\s?(filtrad\\w*|hot|xxx)|only\\s?fans)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Búsquedas sugerentes: no son porno, pero son la puerta de entrada.
    private val SUGGESTIVE = Regex(
        "\\b(bikini|bikinis|lencer[ií]a|lingerie|sexy|hot\\s?(girls?|women|chicas)|chicas\\s?hot|" +
            "modelos?\\s?hot|tetonas?|nalgonas?|culonas?|twerk\\w*|escote|cosplay\\s?(sexy|hot)|" +
            "instagram\\s?models?|thirst\\s?trap)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun extraWords(): List<String> {
        val arr = Engine.cfg.optJSONArray("phone_adult_words") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it).lowercase() }.filter { it.isNotBlank() }
    }

    private fun extraApps(): Set<String> {
        val arr = Engine.cfg.optJSONArray("phone_adult_apps") ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it) }.toSet()
    }

    /** «www.google.com/search?q=chicas+sexy» → «www google com/search q chicas sexy» */
    private fun normalize(s: String) = java.net.URLDecoder.decode(
        s.replace("%(?![0-9a-fA-F]{2})".toRegex(), "%25"), "UTF-8",
    ).lowercase().replace(Regex("[+_.?=&#-]+"), " ")

    private fun strong(raw: String): Boolean {
        val low = raw.lowercase()
        if (BRANDS.any { it in low } || extraWords().any { it in low }) return true
        return STRONG.containsMatchIn(normalize(raw))
    }

    /** ¿El nombre o el paquete de la app dicen que es de contenido sexual? */
    fun isAdultApp(pkg: String, label: String): Boolean =
        pkg in extraApps() || strong("$label $pkg")

    /** Lo que dice la barra de direcciones (URL o búsqueda): "explicit", "suggestive" o null. */
    fun urlLevel(text: String): String? = when {
        text.isBlank() -> null
        strong(text) -> "explicit"
        SUGGESTIVE.containsMatchIn(normalize(text)) -> "suggestive"
        else -> null
    }
}
