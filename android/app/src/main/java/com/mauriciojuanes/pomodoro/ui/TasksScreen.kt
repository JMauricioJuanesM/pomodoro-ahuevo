package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Hub
import com.mauriciojuanes.pomodoro.Store
import com.mauriciojuanes.pomodoro.Sync
import com.mauriciojuanes.pomodoro.Tasks
import org.json.JSONObject
import java.time.LocalDate

/** Las tareas de TickTick: completar, escoger en cuál trabajas (sale en el pomodoro) y agregar. */
@Composable
fun TasksScreen() {
    observe()
    var title by remember { mutableStateOf("") }
    val items = Tasks.items()
    val today = LocalDate.now()
    val late = items.filter { Tasks.dueDate(it)?.let { d -> d <= today } == true }.sortedBy { Tasks.due(it)?.toEpochSecond() }
    val next = items.filter { Tasks.dueDate(it)?.let { d -> d > today } == true }.sortedBy { Tasks.due(it)?.toEpochSecond() }
    val none = items.filter { Tasks.due(it) == null }.sortedByDescending { it.optInt("priority") }

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            observe()
            val updated = Store.tasks.optDouble("updated", 0.0)
            ScreenTitle("Tareas", if (updated > 0) "TickTick · ${items.size} abiertas" else "Esperando a TickTick…")
            Store.currentTask?.let { t ->
                Section("En esto trabajas") {
                    SettingRow("🎯 ${t.optString("title")}", "Sale en el pomodoro de la compu y del teléfono") {
                        TextButton({ Hub.focusTask(null) }) { Text("Quitar", color = Muted) }
                    }
                }
            }
            Section {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Nueva tarea…") })
                    TextButton({
                        if (title.isNotBlank()) {
                            Hub.createTask(title.trim())
                            title = ""
                        }
                    }) { Text("Agregar", color = Tomato) }
                }
            }
            TaskGroup("Hoy y vencidas", late)
            TaskGroup("Próximas", next.take(30))
            TaskGroup("Sin fecha", none.take(40))
            TextButton({ Sync.post("/api/tasks/refresh", JSONObject()) }, Modifier.fillMaxWidth()) {
                Text("Actualizar desde TickTick", color = Muted)
            }
        }
    }
}

@Composable
private fun TaskGroup(title: String, items: List<JSONObject>) {
    if (items.isEmpty()) return
    Section(title) {
        items.forEachIndexed { i, t ->
            if (i > 0) RowDivider()
            val overdue = Tasks.due(t)?.let { it.toInstant().toEpochMilli() < System.currentTimeMillis() && !t.optBoolean("all_day") } == true
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.optString("title"), color = TextMain, fontSize = 15.sp)
                    Text("${t.optString("project")} · ${Tasks.whenText(t)}", color = if (overdue) Tomato else Muted, fontSize = 12.sp)
                }
                Text("🎯", fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Hub.focusTask(t) }.padding(8.dp))
                Text("✅", fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Hub.completeTask(t) }.padding(8.dp))
            }
        }
    }
}
