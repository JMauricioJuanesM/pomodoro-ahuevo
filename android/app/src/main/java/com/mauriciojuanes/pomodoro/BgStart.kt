package com.mauriciojuanes.pomodoro

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * MIUI tiene un permiso propio, «Mostrar ventanas emergentes en segundo plano» (op 10021), que viene negado
 * para las apps que no son de la tienda. Sin él Android no deja abrir pantallas desde el servicio: no sale
 * el telón (frases al desbloquear, descanso, preguntas, «¿qué estás haciendo?») ni el diálogo para instalar
 * la versión nueva. La compu lo da por ADB (`appops set … 10021 allow`); aquí solo se revisa y se avisa.
 */
object BgStart {
    private const val OP = 10021

    /** true/false en MIUI; null si no se puede saber (otro Android: ahí no existe y no hace falta). */
    fun allowed(ctx: Context): Boolean? = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java)
        (m.invoke(ops, OP, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** La pantalla de permisos de MIUI para esta app (o la de detalles de la app). */
    fun settingsIntent(ctx: Context): Intent =
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName)
            .takeIf { it.resolveActivity(ctx.packageManager) != null }
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${ctx.packageName}"))
}
