package com.mauriciojuanes.pomodoro.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.BuildConfigInfo
import com.mauriciojuanes.pomodoro.Engine
import com.mauriciojuanes.pomodoro.GuardService
import com.mauriciojuanes.pomodoro.Store
import com.mauriciojuanes.pomodoro.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---------- permisos ----------
data class Perm(val title: String, val why: String, val ok: Boolean, val open: (Activity) -> Unit)

fun permissions(ctx: Context): List<Perm> {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val me = ComponentName(ctx, GuardService::class.java).flattenToString()
    val acc = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    val pkg = ctx.packageName
    return listOf(
        Perm("Notificaciones", "Avisos y vibración",
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        },
        Perm("Vigilancia", "Accesibilidad → Pomodoro. Sacarte de apps y bloquear",
            GuardService.instance != null || acc.split(':').any { it.equals(me, true) }) {
            it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Perm("No molestar", "Silenciar el teléfono mientras trabajas", nm.isNotificationPolicyAccessGranted) {
            it.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
        Perm("Batería sin restricciones", "Que Android no la cierre", pm.isIgnoringBatteryOptimizations(pkg)) {
            it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
        },
    )
}

@Composable
fun SettingsScreen(openApps: () -> Unit) {
    observe()
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(false) }
    val perms = permissions(ctx)

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            observe()
            ScreenTitle("Ajustes", "Los cambios se aplican también en la compu.")

            Section("Tiempos") {
                StepperRow("Trabajo", cInt("work_min", 25), 1..180, unit = " min") { setCfg("work_min", it) }
                RowDivider()
                StepperRow("Descanso corto", cInt("short_min", 5), 1..60, unit = " min") { setCfg("short_min", it) }
                RowDivider()
                StepperRow("Descanso largo", cInt("long_min", 15), 1..180, unit = " min") { setCfg("long_min", it) }
                RowDivider()
                StepperRow("Pomodoros por ciclo", cInt("cycles", 4), 1..12, subtitle = "Luego toca el descanso largo") { setCfg("cycles", it) }
                RowDivider()
                StepperRow("Aviso previo", cInt("warn_sec", 60), 0..600, 15, " s", "Antes de que empiece el descanso") { setCfg("warn_sec", it) }
                RowDivider()
                SwitchRow("Encadenar pomodoros", "Al terminar el descanso arranca el siguiente", cBool("auto_start_work", true)) { setCfg("auto_start_work", it) }
            }

            Section("Descansos", footer = "Descanso flexible: p. ej. comida de 60 min con bloqueo de 10 → los primeros 10 min bloqueado, luego puedes salir, y en los últimos 10 se vuelve a bloquear.") {
                SwitchRow("Permitir saltar descansos", "Modo blando, en cualquier actividad", cBool("allow_skip", false)) { setCfg("allow_skip", it) }
                RowDivider()
                SwitchRow("Saltar en «Enfocado»", "Para reuniones", cBool("focus_allow_skip", true)) { setCfg("focus_allow_skip", it) }
                RowDivider()
                SwitchRow("Descanso largo flexible", "Poder salir a la mitad", cBool("long_flex", true)) { setCfg("long_flex", it) }
                if (cBool("long_flex", true)) {
                    RowDivider()
                    SwitchRow("Bloqueo = descanso corto", "Al inicio y al final", cBool("long_flex_same_as_short", true)) { setCfg("long_flex_same_as_short", it) }
                    if (!cBool("long_flex_same_as_short", true)) {
                        RowDivider()
                        StepperRow("Bloqueado al inicio", cInt("long_lock_first_min", 5), 1..120, unit = " min") { setCfg("long_lock_first_min", it) }
                        RowDivider()
                        StepperRow("Bloqueado al final", cInt("long_lock_last_min", 5), 1..120, unit = " min") { setCfg("long_lock_last_min", it) }
                    }
                }
            }

            val b = Engine.sched.bounds(LocalDate.now())
            val hm = DateTimeFormatter.ofPattern("HH:mm")
            Section("Jornada", footer = b?.let { "Hoy trabajas de ${it.first.format(hm)} a ${it.second.format(hm)} (sale de tu horario)." }
                ?: "Hoy no hay trabajo en tu horario.") {
                SwitchRow("Arrancar sola", "Al empezar el trabajo del día", cBool("autostart", true)) { setCfg("autostart", it) }
                RowDivider()
                SwitchRow("Modo pesado en horas extra", "Pomodoros a la mitad, descansos al doble", cBool("overtime_mode", true)) { setCfg("overtime_mode", it) }
            }

            Section("Teléfono durante el trabajo") {
                SwitchRow("No molestar", "Mientras corre un pomodoro", cBool("phone_dnd", false)) { setCfg("phone_dnd", it) }
                RowDivider()
                SwitchRow("Sacarme de apps que distraen", null, cBool("phone_distract", true)) { setCfg("phone_distract", it) }
                RowDivider()
                val n = uiCfg.optJSONArray("phone_distracting")?.length() ?: 0
                SettingRow("Apps que distraen", "$n elegidas", onClick = openApps) { Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted) }
                RowDivider()
                SwitchRow("Vigilar el uso", "Avisar y luego bloquear si sigo en el teléfono", cBool("phone_work_guard", true)) { setCfg("phone_work_guard", it) }
                if (cBool("phone_work_guard", true)) {
                    RowDivider()
                    StepperRow("Avisar tras", cInt("phone_work_warn_min", 3), 1..60, unit = " min") {
                        setCfg("phone_work_warn_min", it)
                        if (cInt("phone_work_lock_min", 5) <= it) setCfg("phone_work_lock_min", it + 1)
                    }
                    RowDivider()
                    StepperRow("Bloquear tras", cInt("phone_work_lock_min", 5), cInt("phone_work_warn_min", 3) + 1..120, unit = " min") {
                        setCfg("phone_work_lock_min", it)
                    }
                }
                RowDivider()
                SwitchRow("Bloquear al terminar el descanso", "Si sigo en el teléfono", cStr("phone_break_end", "lock") == "lock") {
                    setCfg("phone_break_end", if (it) "lock" else "notify")
                }
                if (cStr("phone_break_end", "lock") == "lock") {
                    RowDivider()
                    StepperRow("…tras", cInt("phone_lock_delay_sec", 45), 10..600, 5, " s") { setCfg("phone_lock_delay_sec", it) }
                }
                RowDivider()
                StepperRow("Tiempo para leer el aviso", cInt("phone_read_sec", 15), 5..60, unit = " s", subtitle = "Antes de sacarte o bloquear") { setCfg("phone_read_sec", it) }
            }

            Section("Permisos", footer = "Si el teléfono está conectado a la compu por ADB, ella los activa sola.") {
                perms.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    SettingRow(p.title, p.why, onClick = if (p.ok) null else ({ p.open(ctx as Activity) })) {
                        if (p.ok) Icon(Icons.Filled.CheckCircle, null, tint = Ok)
                        else Text("Activar", color = Warn, fontWeight = FontWeight.SemiBold)
                    }
                }
                RowDivider()
                SettingRow("Inicio automático (Xiaomi)", "MIUI cierra las apps sin este permiso", onClick = {
                    runCatching {
                        ctx.startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                    }.onFailure {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted) }
            }

            Section("Conexión") {
                val status = when {
                    !Sync.configured() -> "Sin configurar"
                    !Engine.online -> "Sin conexión" + if (Sync.lastError.isNotEmpty()) " · ${Sync.lastError}" else ""
                    Engine.desktopAlive -> "Conectado · compu prendida"
                    else -> "Conectado · compu apagada"
                }
                SettingRow("Servidor", status, onClick = { server = true },
                    leading = { Dot(if (Engine.online) Ok else Warn, 10) }) { Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted) }
            }
            Text("Pomodoro ${BuildConfigInfo.VERSION}", color = Line, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if (server) ServerDialog { server = false }
}

@Composable
private fun ServerDialog(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(Store.url) }
    var token by remember { mutableStateOf(Store.token) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card1,
        title = { Text("Servidor") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Dirección") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Text("Si conectas el teléfono a la compu por ADB, ella lo configura sola.", color = Muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({ Store.url = url; Store.token = token; onDismiss() }) { Text("Guardar", color = Tomato) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar", color = Muted) } },
    )
}

// ---------- apps que distraen ----------
private data class AppInfo(val pkg: String, val name: String, val icon: ImageBitmap?)

private fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val size = (40 * ctx.resources.displayMetrics.density).toInt()
    return pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
        .filter { it != ctx.packageName }
        .map { pkg ->
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon = runCatching {
                val d = pm.getApplicationIcon(ai)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
            AppInfo(pkg, pm.getApplicationLabel(ai).toString(), icon)
        }.sortedBy { it.name.lowercase() }
}

@Composable
fun DistractingAppsScreen(onBack: () -> Unit) {
    observe()
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val arr = uiCfg.optJSONArray("phone_distracting") ?: JSONArray()
    val chosen = (0 until arr.length()).map { arr.optString(it) }.toSet()

    fun toggle(pkg: String) = Engine.editConfig { c ->
        val now = (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
        if (!now.add(pkg)) now.remove(pkg)
        c.put("phone_distracting", JSONArray(now.sorted()))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Atrás", tint = TextMain) }
            Text("Apps que distraen", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        Text("Durante el trabajo, si abres una de estas, te aviso y a los pocos segundos te saco.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        val list = apps
        if (list == null) {
            Text("Cargando apps…", color = Muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        val known = list.map { it.pkg }.toSet()
        val all = list + (chosen - known).map { AppInfo(it, it.substringAfterLast('.') + " (no instalada)", null) }
        val shown = all.filter { query.isBlank() || it.name.contains(query, true) }
            .sortedWith(compareBy({ it.pkg !in chosen }, { it.name.lowercase() }))
        LazyColumn(Modifier.padding(horizontal = 20.dp)) {
            items(shown, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { toggle(a.pkg) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp))
                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Card2))
                    Spacer(Modifier.width(14.dp))
                    Text(a.name, color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Checkbox(a.pkg in chosen, { toggle(a.pkg) }, colors = CheckboxDefaults.colors(checkedColor = Tomato))
                }
            }
        }
    }
}
