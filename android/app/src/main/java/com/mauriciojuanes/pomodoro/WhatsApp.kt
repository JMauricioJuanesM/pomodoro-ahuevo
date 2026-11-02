package com.mauriciojuanes.pomodoro

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * WhatsApp en horario de trabajo: contestar se vale, perderse no.
 *
 *  - Chats (la lista o una conversación con una persona): con tope de `wa_budget_min` minutos por pomodoro
 *    (5 por defecto). Al acabarse, avisa y te saca como de cualquier app que distrae.
 *  - Estados, canales, comunidades y grupos que no estén en `wa_groups_allowed`: distraen desde el primer segundo.
 *  - En los descansos y fuera del horario no hay tope. Si la actividad permite mensajes ([Policy.P.chatFree],
 *    p. ej. «💬 chat»), tampoco.
 *
 * Lo que se ve en pantalla se lee con la accesibilidad (nombre de la pantalla, pestaña seleccionada y la
 * línea bajo el nombre del chat, que en los grupos dice «toca para info del grupo» o lista a los miembros).
 */
object WhatsApp {
    val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")
    const val CHAT = "chat"
    const val GROUP = "group"
    const val STATUS = "status"   // estados, canales, comunidades

    private val STATUS_TABS = setOf("novedades", "updates", "comunidades", "communities", "estados", "status", "canales", "channels")
    private val GROUP_HINTS = listOf("info del grupo", "group info", "info. del grupo", "miembros", "members")

    private var cls = ""
    private var mode = CHAT
    private var title = ""
    private var lastCheck = 0L

    /** Segundos de chat usados en el pomodoro `key`. */
    private var usedKey = ""
    private var usedMs = 0L
    private var lastTick = 0L

    fun budgetSec() = (Engine.cfg.optDouble("wa_budget_min", 5.0) * 60).toInt()
    fun usedSec(): Int = if (usedKey == pomodoroKey()) (usedMs / 1000).toInt() else 0
    fun leftSec() = maxOf(0, budgetSec() - usedSec())

    /** El pomodoro en curso: cambia cada vez que se completa uno. */
    private fun pomodoroKey() = "${Store.today()}#${Engine.pomodorosToday()}"

    /** Cambió la ventana de WhatsApp (o su contenido): recalcula en qué parte estás. */
    fun onEvent(s: AccessibilityService, className: String?, state: Boolean) {
        if (state && className != null && !className.startsWith("android.")) cls = className
        val now = System.currentTimeMillis()
        if (!state && now - lastCheck < 1200) return
        lastCheck = now
        mode = detect(s)
    }

    fun current(): String = mode
    fun chatTitle(): String = title

    private fun detect(s: AccessibilityService): String {
        val c = cls.lowercase()
        if ("status" in c || "newsletter" in c || "community" in c || "channel" in c) return STATUS
        val root = s.rootInActiveWindow ?: return mode
        if ("conversation" in c) {
            title = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                .firstOrNull()?.text?.toString() ?: ""
            val sub = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_status")
                .firstOrNull()?.text?.toString()?.lowercase() ?: ""
            val group = GROUP_HINTS.any { it in sub } || sub.count { it == ',' } >= 2
            if (!group) return CHAT
            val ok = Engine.cfg.optJSONArray("wa_groups_allowed")
            val allowed = ok != null && (0 until ok.length()).any { title.contains(ok.optString(it), ignoreCase = true) }
            return if (allowed || Engine.cfg.optBoolean("wa_groups_ok", false)) CHAT else GROUP
        }
        title = ""
        return if (selectedTab(root) in STATUS_TABS) STATUS else CHAT
    }

    /** Texto de la pestaña seleccionada de la barra de abajo (Chats, Novedades, Comunidades, Llamadas). */
    private fun selectedTab(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < 400) {
            val n = stack.removeFirst()
            seen++
            if (n.isSelected) {
                val txt = (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim()
                    ?: (0 until n.childCount).firstNotNullOfOrNull { n.getChild(it)?.text?.toString()?.lowercase() }
                if (txt != null && STATUS_TABS.any { txt.startsWith(it) }) return STATUS_TABS.first { txt.startsWith(it) }
                if (txt != null && (txt.startsWith("chats") || txt.startsWith("llamadas") || txt.startsWith("calls"))) return txt
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /**
     * Cada pocos segundos mientras WhatsApp está al frente en horario de trabajo: suma al tope. Regresa
     * true si ya se acabó (o si estás en estados/grupos): toca sacarte.
     */
    fun tick(p: Policy.P): Boolean {
        val now = System.currentTimeMillis()
        val key = pomodoroKey()
        if (key != usedKey) {
            usedKey = key
            usedMs = 0
        }
        val dt = if (lastTick > 0 && now - lastTick < 10_000) now - lastTick else 0
        lastTick = now
        if (mode == STATUS || mode == GROUP) return true
        if (p.chatFree) return false
        usedMs += dt
        return usedMs >= budgetSec() * 1000L
    }

    /** Saliste de WhatsApp: el siguiente tick no cuenta el hueco. */
    fun away() {
        lastTick = 0
    }

    fun why(): String = when (mode) {
        STATUS -> "Los estados y canales no se valen en horario de trabajo."
        GROUP -> "Ese grupo no está en tus grupos permitidos."
        else -> "Ya usaste tus ${budgetSec() / 60} min de WhatsApp de este pomodoro."
    }
}
