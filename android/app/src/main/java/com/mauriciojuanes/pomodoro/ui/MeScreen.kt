@file:OptIn(ExperimentalLayoutApi::class)

package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Curtain
import com.mauriciojuanes.pomodoro.Hub
import com.mauriciojuanes.pomodoro.Store
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TYPES = linkedMapOf(
    "urgencia" to ("🆘" to "Urgencia"), "victoria" to ("💪" to "Vencí"), "recaida" to ("🔴" to "Recaí"),
    "nota" to ("📝" to "Nota"), "sugerencia" to ("💡" to "Sugerencia"), "animo" to ("🙂" to "Ánimo"),
)
private val PROMPTS = mapOf(
    "victoria" to "¿Qué te ayudó a vencerla?", "recaida" to "¿Qué pasó y qué la disparó? Sin juzgarte: es para aprender.",
    "nota" to "Lo que quieras dejar anotado", "sugerencia" to "¿Qué le cambiarías a Faro? Cada noche se lee.",
    "animo" to "¿Cómo te sientes (1-10) y por qué?",
)
private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun entries(): List<JSONObject> = Store.journal.let { j -> (0 until j.length()).mapNotNull { j.optJSONObject(it) } }

private fun day(ts: Double): LocalDate = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalDate()

/** Días desde la última recaída (o desde la primera entrada si no hay). */
fun streakDays(): Int {
    val all = entries()
    val since = all.lastOrNull { it.optString("type") == "recaida" }?.optDouble("ts") ?: all.firstOrNull()?.optDouble("ts")
        ?: return 0
    return ((System.currentTimeMillis() / 1000.0 - since) / 86400).toInt()
}

@Composable
fun MeScreen(openInbox: String? = null) {
    observe()
    val ctx = LocalContext.current
    var write by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(openInbox) }
    val all = entries()

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            observe()
            ScreenTitle("Yo", "Tu racha, tu diario y lo que Faro te quiere decir.")
            Section {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${streakDays()} días", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        Text("sin recaer", color = Muted)
                    }
                    Text("🆘", fontSize = 34.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF5A3A10))
                        .clickable { Curtain.show(ctx, "sos") }.padding(14.dp))
                }
                Heatmap(all)
                FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPES.filterKeys { it != "urgencia" }.forEach { (k, v) ->
                        Text("${v.first} ${v.second}", color = TextMain, fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Card2).clickable { write = k }
                                .padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }

            Curtain.pendingAsk()?.let { a ->
                Section("Faro te pregunta") {
                    SettingRow("💡 ${a.optString("question")}", "Toca para contestar", onClick = { Curtain.show(ctx, "ask", a.optString("id")) })
                }
            }

            val inbox = Store.inbox.let { b -> (0 until b.length()).mapNotNull { b.optJSONObject(it) } }
            if (inbox.isNotEmpty()) Section("Buzón", footer = "Lo que antes eran mensajes largos de Telegram.") {
                inbox.take(25).forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    val id = m.optString("id")
                    val unread = !m.optBoolean("read")
                    Column(Modifier.fillMaxWidth().clickable {
                        open = if (open == id) null else id
                        if (unread) Hub.readInbox(id)
                    }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text((if (unread) "● " else "") + m.optString("title").ifEmpty { m.optString("short") },
                            color = if (unread) Warn else TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(Instant.ofEpochMilli((m.optDouble("ts") * 1000).toLong()).atZone(ZoneId.systemDefault()).format(WHEN),
                            color = Muted, fontSize = 12.sp)
                        if (open == id) Text(m.optString("body").ifEmpty { m.optString("short") }, color = TextMain,
                            fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            val beliefs = Store.mind.optJSONArray("beliefs")
            if (beliefs != null && beliefs.length() > 0) Section("Creencias que estás cambiando") {
                for (i in 0 until beliefs.length()) {
                    val b = beliefs.optJSONObject(i) ?: continue
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(b.optString("negative"), color = Muted, fontSize = 13.sp,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                        Text("→ " + b.optString("positive"), color = TextMain, fontSize = 15.sp)
                    }
                }
            }

            Section("Diario") {
                val shown = all.filter { it.optString("type") != "reaccion" }.takeLast(40).reversed()
                if (shown.isEmpty()) Text("Aún no hay nada.", color = Muted, modifier = Modifier.padding(18.dp))
                shown.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    val (icon, name) = TYPES[e.optString("type")] ?: ("•" to e.optString("type"))
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text("$icon $name · " + Instant.ofEpochMilli((e.optDouble("ts") * 1000).toLong())
                            .atZone(ZoneId.systemDefault()).format(WHEN) + if (e.optBoolean("auto")) " · auto" else "",
                            color = Muted, fontSize = 12.sp)
                        e.optString("text").takeIf { it.isNotEmpty() }?.let { Text(it, color = TextMain, fontSize = 14.sp) }
                    }
                }
            }
        }
    }

    write?.let { type ->
        var text by remember(type) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { write = null }, containerColor = Card1,
            title = { Text("${TYPES[type]?.first} ${TYPES[type]?.second}") },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text(PROMPTS[type] ?: "") })
            },
            confirmButton = {
                TextButton({
                    Hub.journal(type, text.trim())
                    write = null
                }) { Text("Guardar", color = Tomato) }
            },
            dismissButton = { TextButton({ write = null }) { Text("Cancelar", color = Muted) } },
        )
    }
}

/** Últimas 4 semanas: rojo recaída, amarillo urgencia sin victoria, verde victoria. */
@Composable
private fun Heatmap(all: List<JSONObject>) {
    val byDay = all.groupBy { day(it.optDouble("ts")) }
    val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (week in 3 downTo 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 6 downTo 0) {
                val date = today.minusDays((week * 7 + d).toLong())
                val types = byDay[date]?.map { it.optString("type") } ?: emptyList()
                val c = when {
                    "recaida" in types -> Tomato
                    "victoria" in types -> Ok
                    "urgencia" in types -> Warn
                    types.isNotEmpty() -> Color(0xFF3A4150)
                    else -> Card2
                }
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(c))
            }
        }
        Text("4 semanas · 🟥 recaída · 🟨 urgencia · 🟩 vencí", color = Muted, fontSize = 11.sp)
    }
}
