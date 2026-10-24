@file:OptIn(ExperimentalMaterial3Api::class)

package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun reminders(): List<JSONObject> {
    val arr = uiCfg.optJSONArray("reminders") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedBy { it.optString("time") }
}

private fun days(r: JSONObject): List<Int> {
    val a = r.optJSONArray("days") ?: return emptyList()
    return (0 until a.length()).map { a.optInt(it) }.sorted()
}

fun reminderWhen(r: JSONObject): String {
    val time = r.optString("time")
    if (r.optString("type") == "once") {
        val d = runCatching { LocalDate.parse(r.optString("date")) }.getOrNull()
        val day = when (d) {
            LocalDate.now() -> "Hoy"
            LocalDate.now().plusDays(1) -> "Mañana"
            null -> r.optString("date")
            else -> "${d.dayOfMonth}/${d.monthValue}/${d.year}"
        }
        return "$day · $time"
    }
    val ds = days(r)
    val txt = when (ds) {
        (0..6).toList() -> "Diario"
        (0..4).toList() -> "Lun a Vie"
        listOf(5, 6) -> "Fines de semana"
        else -> ds.joinToString(" ") { DAY_LETTERS[it] }
    }
    return "$txt · $time"
}

private fun saveReminder(r: JSONObject) = Engine.editConfig { c ->
    val old = c.optJSONArray("reminders") ?: JSONArray()
    val out = JSONArray()
    var replaced = false
    for (i in 0 until old.length()) {
        val x = old.optJSONObject(i) ?: continue
        if (x.optString("id") == r.optString("id")) {
            out.put(r)
            replaced = true
        } else out.put(x)
    }
    if (!replaced) out.put(r)
    c.put("reminders", out)
}

private fun deleteReminder(id: String) = Engine.editConfig { c ->
    val old = c.optJSONArray("reminders") ?: return@editConfig
    val out = JSONArray()
    for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { out.put(it) }
    c.put("reminders", out)
}

@Composable
fun RemindersScreen() {
    observe()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    val list = reminders()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.padding(horizontal = 20.dp)) {
            item { ScreenTitle("Recordatorios", "Te aviso en el teléfono y en la compu.") }
            if (list.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏰", fontSize = 48.sp)
                    VSpace(10)
                    Text("Aún no tienes recordatorios", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tomar agua, una pastilla, una reunión…", color = Muted, fontSize = 14.sp)
                }
            }
            items(list, key = { it.optString("id") }) { r -> ReminderCard(r) { editing = r } }
            item { VSpace(96) }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = JSONObject() },
            containerColor = Tomato, contentColor = androidx.compose.ui.graphics.Color.White,
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nuevo", fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
    editing?.let { ReminderSheet(it) { editing = null } }
}

@Composable
private fun ReminderCard(r: JSONObject, onClick: () -> Unit) {
    val enabled = r.optBoolean("enabled", true)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Card1)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.optString("time"), color = if (enabled) TextMain else Muted, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(r.optString("text"), color = if (enabled) TextMain else Muted, fontSize = 16.sp, maxLines = 2)
            val dur = r.optInt("duration")
            Text(buildString {
                append(reminderWhen(r).substringBefore(" ·"))
                if (dur > 0) append(" · $dur min")
                if (r.optBoolean("lock")) append(" · 🔒 bloquea la compu")
            }, color = Muted, fontSize = 13.sp)
        }
        Switch(enabled, { saveReminder(JSONObject(r.toString()).put("enabled", it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Tomato, uncheckedTrackColor = Card2, uncheckedBorderColor = Line, uncheckedThumbColor = Muted))
    }
}

@Composable
private fun ReminderSheet(r: JSONObject, onDismiss: () -> Unit) {
    val isNew = !r.has("id")
    var text by remember { mutableStateOf(r.optString("text")) }
    var once by remember { mutableStateOf(r.optString("type") == "once") }
    var daySet by remember { mutableStateOf(if (isNew) (0..4).toSet() else days(r).toSet()) }
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(r.optString("date")) }.getOrDefault(LocalDate.now())) }
    val (h0, m0) = r.optString("time", "09:00").split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 9) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    var hour by remember { mutableIntStateOf(h0) }
    var minute by remember { mutableIntStateOf(m0) }
    var duration by remember { mutableIntStateOf(r.optInt("duration")) }
    var lock by remember { mutableStateOf(r.optBoolean("lock")) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    val valid = text.isNotBlank() && (once || daySet.isNotEmpty())

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card1) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(if (isNew) "Nuevo recordatorio" else "Editar recordatorio", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
            VSpace(14)
            OutlinedTextField(text, { text = it }, label = { Text("¿Qué te recuerdo?") }, modifier = Modifier.fillMaxWidth())
            VSpace(16)
            // Hora grande, tocable
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card2).clickable { pickTime = true }.padding(14.dp),
                contentAlignment = Alignment.Center) {
                Text("%02d:%02d".format(hour, minute), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = TextMain)
            }
            VSpace(16)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Card2).padding(4.dp)) {
                listOf(false to "Se repite", true to "Una sola vez").forEach { (v, label) ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (once == v) Tomato else Card2)
                        .clickable { once = v }.padding(vertical = 10.dp), Alignment.Center) {
                        Text(label, color = TextMain, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            VSpace(14)
            if (once) {
                SettingRow("Fecha", onClick = { pickDate = true }) {
                    Text("${date.dayOfMonth}/${date.monthValue}/${date.year}", color = Tomato, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DAY_LETTERS.forEachIndexed { i, l -> DayToggle(l, i in daySet) { daySet = if (i in daySet) daySet - i else daySet + i } }
                }
                Row {
                    TextButton({ daySet = (0..4).toSet() }) { Text("Lun a Vie", color = Tomato) }
                    TextButton({ daySet = (0..6).toSet() }) { Text("Diario", color = Tomato) }
                }
            }
            VSpace(4)
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Card2)) {
                StepperRow("Duración", duration, 0..240, 5, " min", subtitle = if (duration == 0) "Solo un aviso" else null) { duration = it }
                SwitchRow("Bloquear la compu", "Como un descanso: no se puede cerrar", lock) { lock = it }
            }
            VSpace(18)
            Button(
                enabled = valid,
                onClick = {
                    val out = JSONObject(r.toString())
                        .put("id", r.optString("id").ifEmpty { "r${System.nanoTime().toString(16)}" })
                        .put("text", text.trim()).put("type", if (once) "once" else "repeat")
                        .put("time", "%02d:%02d".format(hour, minute)).put("duration", duration)
                        .put("lock", lock).put("enabled", true)
                    if (once) {
                        out.put("date", date.toString()); out.remove("days")
                    } else {
                        out.put("days", JSONArray(daySet.sorted())); out.remove("date")
                    }
                    saveReminder(out)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tomato),
            ) { Text("Guardar", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            if (!isNew) {
                TextButton({ deleteReminder(r.optString("id")); onDismiss() }, Modifier.fillMaxWidth()) {
                    Text("Eliminar", color = Tomato)
                }
            }
            VSpace(20)
        }
    }

    if (pickTime) {
        val st = rememberTimePickerState(hour, minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false }, containerColor = Card1,
            text = { TimePicker(st) },
            confirmButton = { TextButton({ hour = st.hour; minute = st.minute; pickTime = false }) { Text("Listo", color = Tomato) } },
            dismissButton = { TextButton({ pickTime = false }) { Text("Cancelar", color = Muted) } },
        )
    }
    if (pickDate) {
        val st = rememberDatePickerState(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("Listo", color = Tomato) }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Cancelar", color = Muted) } },
        ) { DatePicker(st) }
    }
}
