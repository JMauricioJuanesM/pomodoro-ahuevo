@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Engine
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

val DAYS = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
val DAY_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")
val PRESET_COLORS = listOf("#00ffff", "#46bdc6", "#4a86e8", "#0000ff", "#9900ff", "#ff00ff", "#990000", "#e06666",
    "#ff9900", "#ffff00", "#00ff00", "#3c9e5c", "#b7b7b7", "#ffffff")
val EMOJIS = "💻 📚 🎓 ⚙️ 👤 💬 🍽 🍳 ☕ 🏃 🚶 🧘 🎮 📖 🚿 🛏 ❤️ 🎵 🛒 🧹 🚗 📞".split(" ")

private fun hourLabel(h: Int) = "%02d:00".format(h % 24)

/** Fecha de esta semana que cae en ese día (0 = lunes). */
private fun dateOf(day: Int): LocalDate = LocalDate.now().let { it.plusDays((day - (it.dayOfWeek.value - 1)).toLong()) }

private fun activities(): List<Pair<String, JSONObject>> {
    val acts = uiCfg.optJSONObject("activities") ?: return emptyList()
    val order = KINDS.map { it.first }
    return acts.keys().asSequence().map { it to acts.getJSONObject(it) }
        .sortedWith(compareBy({ order.indexOf(it.second.optString("kind")) }, { it.second.optString("label") })).toList()
}

private fun paint(day: Int, from: Int, hours: Int, aid: String?) = Engine.editConfig { c ->
    val sched = c.optJSONObject("schedule") ?: JSONObject().also { c.put("schedule", it) }
    val d = sched.optJSONObject(day.toString()) ?: JSONObject().also { sched.put(day.toString(), it) }
    for (h in from until minOf(24, from + hours)) if (aid == null) d.remove(h.toString()) else d.put(h.toString(), aid)
}

@Composable
fun ScheduleScreen() {
    observe()
    var day by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value - 1) }
    var editHour by remember { mutableStateOf<Int?>(null) }
    var editAct by remember { mutableStateOf<String?>(null) }   // "" = nueva
    var copying by remember { mutableStateOf(false) }
    val s = Engine.sched
    val grid = uiCfg.optJSONArray("grid_hours")
    val h0 = grid?.optInt(0, 6) ?: 6
    val h1 = grid?.optInt(1, 23) ?: 23
    val bounds = s.bounds(dateOf(day))
    val acts = activities()

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            ScreenTitle("Horario", bounds?.let { "Jornada del ${DAYS[day].lowercase()}: %s–%s".format(
                hourLabel(it.first.hour), hourLabel(if (it.second.hour == 0) 24 else it.second.hour)) }
                ?: "El ${DAYS[day].lowercase()} no hay trabajo")
            DayPicker(day) { day = it }
            VSpace(16)
        }
        // Las horas del día, agrupadas en bloques de la misma actividad
        val hours = (h0..h1).toList()
        items(hours.size) { i ->
            observe()
            val h = hours[i]
            val at = dateOf(day).atTime(h, 0)
            val aid = s.cell(at)
            val prev = i > 0 && s.cell(at.minusHours(1)) == aid
            val next = i < hours.size - 1 && s.cell(at.plusHours(1)) == aid
            var end = h + 1
            while (end <= h1 && s.cell(dateOf(day).atTime(end % 24, 0)) == aid && end < 24) end++
            HourRow(h, aid, prev, next, end, isNow = day == LocalDate.now().dayOfWeek.value - 1 && h == LocalDateTime.now().hour) { editHour = h }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ copying = true }) { Text("Copiar el ${DAYS[day].lowercase()} a otros días", color = Tomato) }
            }
            VSpace(14)
            Section("Actividades", footer = "Toca una para cambiar su nombre, color o tipo.") {
                acts.forEachIndexed { i, (id, a) ->
                    if (i > 0) RowDivider()
                    SettingRow(a.optString("label"), KINDS.firstOrNull { it.first == a.optString("kind") }?.second?.substringBefore(":"),
                        onClick = { editAct = id },
                        leading = { ColorChip(hex(a.optString("color")), KIND_ICONS[a.optString("kind")] ?: "") }) {
                        Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted)
                    }
                }
                if (acts.isNotEmpty()) RowDivider()
                SettingRow("Nueva actividad", onClick = { editAct = "" },
                    leading = { Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Card2), Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = Tomato) } })
            }
            VSpace(24)
        }
    }

    editHour?.let { h -> PaintSheet(day, h, acts, onDismiss = { editHour = null }) }
    editAct?.let { id -> ActivityDialog(id.ifEmpty { null }) { editAct = null } }
    if (copying) CopyDayDialog(day) { copying = false }
}

@Composable
fun DayPicker(selected: Int, onPick: (Int) -> Unit) {
    val today = LocalDate.now().dayOfWeek.value - 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DAY_LETTERS.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (sel) Tomato else Card1)
                    .border(if (i == today && !sel) BorderStroke(1.5.dp, Tomato) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l, color = if (sel) Color.White else TextMain, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HourRow(h: Int, aid: String?, prev: Boolean, next: Boolean, end: Int, isNow: Boolean, onClick: () -> Unit) {
    val s = Engine.sched
    val c = if (aid != null) hex(s.color(aid)) else Card1
    // Las horas seguidas de la misma actividad se ven como un solo bloque.
    val joinUp = prev && aid != null
    val joinDown = next && aid != null
    val top = if (joinUp) 0.dp else 14.dp
    val bottom = if (joinDown) 0.dp else 14.dp
    Row(Modifier.fillMaxWidth().height(if (joinDown) 50.dp else 54.dp).padding(bottom = if (joinDown) 0.dp else 4.dp)) {
        Text(hourLabel(h), color = if (isNow) Tomato else Muted, fontSize = 13.sp,
            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(54.dp).padding(top = 2.dp))
        Box(
            Modifier.weight(1f).height(50.dp)
                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                .background(c).clickable(onClick = onClick).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (aid == null) Text("+  Asignar", color = Muted.copy(alpha = 0.5f), fontSize = 13.sp)
            else if (!joinUp) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.label(aid), color = onColor(c), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Text("${hourLabel(h)} – ${hourLabel(end)}", color = onColor(c).copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ColorChip(c: Color, icon: String) =
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c), Alignment.Center) { Text(icon, fontSize = 16.sp) }

@Composable
private fun PaintSheet(day: Int, hour: Int, acts: List<Pair<String, JSONObject>>, onDismiss: () -> Unit) {
    var hours by remember { mutableIntStateOf(1) }
    val current = Engine.sched.cell(dateOf(day).atTime(hour, 0))
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card1) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("${DAYS[day]} · ${hourLabel(hour)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMain)
            VSpace(14)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card2).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Durante", color = TextMain, modifier = Modifier.weight(1f))
                RoundButton("−", hours > 1) { hours-- }
                Text("$hours h  (hasta ${hourLabel(hour + hours)})", color = TextMain, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp))
                RoundButton("+", hour + hours < 24) { hours++ }
            }
            VSpace(12)
            acts.forEach { (id, a) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { paint(day, hour, hours, id); onDismiss() }
                    .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorChip(hex(a.optString("color")), KIND_ICONS[a.optString("kind")] ?: "")
                    Spacer(Modifier.width(14.dp))
                    Text(a.optString("label"), color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (id == current) Icon(Icons.Filled.Check, null, tint = Tomato)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { paint(day, hour, hours, null); onDismiss() }
                .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(14.dp))
                Text("Vacío", color = Muted, fontSize = 16.sp)
            }
            VSpace(20)
        }
    }
}

@Composable
private fun CopyDayDialog(from: Int, onDismiss: () -> Unit) {
    var targets by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card1,
        title = { Text("Copiar el ${DAYS[from].lowercase()} a…") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    DAY_LETTERS.forEachIndexed { i, l ->
                        if (i != from) DayToggle(l, i in targets) { targets = if (i in targets) targets - i else targets + i }
                    }
                }
                VSpace(10)
                Text("Se reemplaza todo el horario de esos días.", color = Muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = targets.isNotEmpty(), onClick = {
                Engine.editConfig { c ->
                    val sched = c.optJSONObject("schedule") ?: JSONObject().also { c.put("schedule", it) }
                    val src = sched.optJSONObject(from.toString()) ?: JSONObject()
                    targets.forEach { sched.put(it.toString(), JSONObject(src.toString())) }
                }
                onDismiss()
            }) { Text("Copiar", color = Tomato) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar", color = Muted) } },
    )
}

@Composable
fun DayToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) Tomato else Card2).clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = if (on) Color.White else TextMain, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActivityDialog(id: String?, onDismiss: () -> Unit) {
    val a = id?.let { uiCfg.optJSONObject("activities")?.optJSONObject(it) }
    var label by remember { mutableStateOf(a?.optString("label") ?: "") }
    var color by remember { mutableStateOf(a?.optString("color") ?: "#00ffff") }
    var kind by remember { mutableStateOf(a?.optString("kind") ?: "trabajo") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card1,
        title = { Text(if (id == null) "Nueva actividad" else "Editar actividad") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(hex(color)), Alignment.Center) {
                    Text(label.ifEmpty { "Vista previa" }, color = onColor(hex(color)), fontWeight = FontWeight.SemiBold)
                }
                VSpace(12)
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Texto o emoji") }, modifier = Modifier.fillMaxWidth())
                VSpace(8)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    EMOJIS.forEach { e ->
                        Text(e, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { label = (label.trim() + " " + e).trim() }.padding(4.dp))
                    }
                }
                VSpace(12)
                Text("Color", color = Muted, fontSize = 13.sp)
                VSpace(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_COLORS.forEach { c ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(hex(c))
                            .border(if (c.equals(color, true)) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { color = c })
                    }
                }
                VSpace(14)
                Text("¿Qué pasa en esta actividad?", color = Muted, fontSize = 13.sp)
                KINDS.forEach { (k, desc) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { kind = k }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(kind == k, { kind = k }, colors = RadioButtonDefaults.colors(selectedColor = Tomato))
                        Text("${KIND_ICONS[k]}  $desc", color = TextMain, fontSize = 14.sp)
                    }
                }
                if (id != null) {
                    VSpace(6)
                    TextButton({ confirmDelete = true }) { Text("Eliminar actividad", color = Tomato) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = {
                Engine.editConfig { c ->
                    val acts = c.optJSONObject("activities") ?: JSONObject().also { c.put("activities", it) }
                    acts.put(id ?: "a${System.nanoTime().toString(16)}",
                        JSONObject().put("label", label.trim()).put("color", color).put("kind", kind))
                }
                onDismiss()
            }) { Text("Guardar", color = Tomato) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar", color = Muted) } },
    )
    if (confirmDelete && id != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Card1,
        title = { Text("¿Eliminar «${label}»?") },
        text = { Text("Sus horas quedarán vacías en el horario.") },
        confirmButton = {
            TextButton({
                Engine.editConfig { c ->
                    c.optJSONObject("activities")?.remove(id)
                    val sched = c.optJSONObject("schedule") ?: return@editConfig
                    sched.keys().forEach { d ->
                        val day = sched.getJSONObject(d)
                        day.keys().asSequence().filter { day.optString(it) == id }.toList().forEach { day.remove(it) }
                    }
                }
                confirmDelete = false
                onDismiss()
            }) { Text("Eliminar", color = Tomato) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar", color = Muted) } },
    )
}
