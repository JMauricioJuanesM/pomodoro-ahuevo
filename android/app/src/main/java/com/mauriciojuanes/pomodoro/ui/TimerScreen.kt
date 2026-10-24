package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Engine
import com.mauriciojuanes.pomodoro.Store
import com.mauriciojuanes.pomodoro.Sync
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TimerScreen(missingPerms: Int, openSettings: () -> Unit) {
    observe()
    val t = Engine.timer
    val s = Engine.sched
    val now = LocalDateTime.now()
    val work = t.phase == "work"
    val ot = if (t.running) t.overtime else Engine.inOvertime()
    val bounds = s.bounds(LocalDate.now())
    val waiting = work && !t.running && cBool("autostart", true) && bounds != null &&
        now < bounds.first && now.hour >= 5 && Store.autoDate != Store.today()
    val accent = when {
        !work -> hex(t.color, Ok)
        waiting -> Muted
        ot -> Overtime
        else -> WorkRed
    }
    val anim by animateColorAsState(accent, label = "accent")
    val title = when {
        !work -> t.title.uppercase()
        waiting -> "EN ESPERA"
        !t.running -> "EN PAUSA"
        ot -> "HORAS EXTRA"
        else -> "TRABAJO"
    }
    val rem = Engine.remaining()
    val frac by animateFloatAsState((rem / t.total).toFloat().coerceIn(0f, 1f), label = "frac")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(14)
        SyncPill()
        VSpace(14)
        ActivityCard(now)
        VSpace(22)

        // Anillo con el tiempo
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(Card2, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(anim, -90f, 360f * frac, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = anim, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 36.dp))
                Text(Engine.fmt(rem), color = TextMain, fontSize = if (rem >= 3600) 52.sp else 64.sp, fontWeight = FontWeight.Bold)
                val cycles = cInt("cycles", 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    repeat(cycles) { Dot(if (it < t.doneInCycle) anim else Card2, 10) }
                }
            }
        }
        VSpace(10)
        Text(statusText(work, waiting, ot, t.running, bounds?.first), color = Muted, fontSize = 14.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp).height(40.dp))
        VSpace(8)

        // Controles
        if (work) {
            Button(
                onClick = { Engine.toggle() },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = anim, contentColor = onColor(accent)),
            ) {
                Icon(if (t.running) PauseIcon else Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (t.running) "Pausar" else "Iniciar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tonal("Reiniciar", Modifier.weight(1f), { Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp)) }) { Engine.reset() }
                Tonal("Descanso ya", Modifier.weight(1f), { Text("☕", fontSize = 16.sp) }) { Engine.breakNow() }
            }
        } else if (t.skippable) {
            Tonal("Saltar descanso", Modifier.fillMaxWidth(), { Icon(SkipIcon, null, Modifier.size(20.dp)) }) { Engine.skip() }
        } else {
            Text("Descansa: se acaba solo.", color = Muted, fontSize = 14.sp)
        }

        VSpace(24)
        Text("HOY", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val n = Engine.pomodorosToday()
            Stat("$n", if (n == 1) "pomodoro" else "pomodoros", Modifier.weight(1f))
            Stat("${(Store.count("phone_stats") / 60).toInt()} min", "en el cel", Modifier.weight(1f))
            Stat(bounds?.let { "${it.first.hour}–${if (it.second.hour == 0) 24 else it.second.hour} h" } ?: "Libre", "jornada", Modifier.weight(1f))
        }
        if (missingPerms > 0) {
            VSpace(14)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Warn.copy(alpha = 0.14f))
                    .clickable(onClick = openSettings).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Warn)
                Spacer(Modifier.width(12.dp))
                Text("Faltan $missingPerms permiso${if (missingPerms > 1) "s" else ""} para que funcione sin la compu. Toca para activarlos.",
                    color = TextMain, fontSize = 14.sp)
            }
        }
        VSpace(24)
    }
}

private fun statusText(work: Boolean, waiting: Boolean, ot: Boolean, running: Boolean, start: LocalDateTime?): String = when {
    !work -> "Levántate, estírate, toma agua."
    waiting -> "Tu jornada empieza a las ${start?.format(HHMM)}. Arranco sola."
    !running -> "Pulsa Iniciar cuando estés listo."
    ot -> "Tu jornada ya terminó: pomodoros cortos y descansos dobles."
    else -> "A darle. El teléfono te vigila mientras trabajas."
}

@Composable
private fun SyncPill() {
    observe()
    val (color, text) = when {
        !Sync.configured() -> Warn to "Sin servidor configurado"
        !Engine.online -> Warn to "Sin conexión · el teléfono sigue solo"
        Engine.authority -> Ok to "Compu apagada · el teléfono lleva el pomodoro"
        else -> Ok to "Sincronizado con la compu"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Card1).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, 8)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ActivityCard(now: LocalDateTime) {
    observe()
    val s = Engine.sched
    val b = s.blockAt(now)
    val next = s.nextChange(now)
    val c = if (b.aid != null) hex(s.color(b.aid)) else Card2
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card1).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c), contentAlignment = Alignment.Center) {
            Text(KIND_ICONS[s.kind(b.aid)] ?: "", fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (b.aid != null) s.label(b.aid) else if (uiCfg.length() == 0) "Esperando tu horario…" else "Nada en tu horario",
                color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                buildString {
                    if (b.aid != null) append("Hasta las ${b.end.format(HHMM)}")
                    if (next != null) append(if (isEmpty()) "" else "  ·  ").append("Después: ${s.label(next.first)} ${next.second.format(HHMM)}")
                },
                color = Muted, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Card1).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun Tonal(label: String, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.height(52.dp), shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Card1, contentColor = TextMain)) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

val KIND_ICONS = mapOf("trabajo" to "💻", "enfocado" to "🎯", "descanso" to "🔒", "libre" to "🌿")
val KINDS = listOf(
    "trabajo" to "Trabajo: corre el pomodoro",
    "enfocado" to "Enfocado: como trabajo, pero puedo saltar los descansos (reuniones)",
    "descanso" to "Descanso programado: bloquea la compu (p. ej. la comida)",
    "libre" to "Fuera de trabajo",
)
