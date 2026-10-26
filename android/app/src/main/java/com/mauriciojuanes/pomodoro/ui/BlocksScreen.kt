package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mauriciojuanes.pomodoro.Blocks
import com.mauriciojuanes.pomodoro.Engine
import kotlinx.coroutines.delay

/** Lo que bloqueaste en el Panel ADB: el teléfono lo cumple aunque la compu esté apagada. */
@Composable
fun BlocksScreen() {
    observe()
    var showApps by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            observe()
            ScreenTitle("Bloqueos", "Lo que bloqueaste en el Panel ADB. Se cumple aunque la compu esté apagada.")

            if (!Blocks.configured()) {
                Section(footer = "Abre el Panel ADB en la compu (con la sincronización del Pomodoro) y sus bloqueos aparecerán aquí.") {
                    SettingRow("Todavía no hay bloqueos", "El panel no ha publicado nada")
                }
                return@item
            }
            if (!Blocks.canWrite()) {
                Section(footer = "Conecta el teléfono a la compu por ADB: el Pomodoro o el Panel ADB le dan este permiso solos.") {
                    SettingRow("Falta un permiso", "Sin él no puedo volver a encender el DNS ni el bloqueo de Xiaomi",
                        leading = { Icon(Icons.Filled.Warning, null, tint = Warn) })
                }
            }

            val relocks = Blocks.relocks().sortedBy { it.at ?: Double.MAX_VALUE }
            if (relocks.isNotEmpty()) {
                BigButton("🔒  Bloquear todo ya") { Blocks.relockAll() }
                VSpace(14)
            }

            Section("Protecciones") {
                val dnsPending = Blocks.pending("dns")
                SettingRow("🌐  DNS privado", Blocks.dnsHost().ifEmpty { "—" }) {
                    StatusText(Blocks.dnsOn(), if (dnsPending) "desbloqueado" else null)
                }
                RowDivider()
                SettingRow("🔐  Bloqueo de apps de Xiaomi", if (Blocks.keepApplock()) "Siempre encendido" else "Lo manejas tú") {
                    StatusText(Blocks.applockOn(), if (Blocks.pending("applock")) "desbloqueado" else null)
                }
                RowDivider()
                val apps = Blocks.blockedApps()
                SettingRow("🚫  Apps bloqueadas", "${apps.size} · te saco de ellas si las abres",
                    onClick = { showApps = !showApps }) { Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted) }
                if (showApps) apps.forEach { pkg ->
                    RowDivider()
                    SettingRow("     " + Blocks.label(pkg), pkg)
                }
            }

            Section("Desbloqueos vigentes", footer = if (relocks.isEmpty()) null
                else "Al vencer, el teléfono vuelve a bloquear solo y le avisa al panel.") {
                if (relocks.isEmpty()) {
                    SettingRow("Ninguno", "Todo lo que bloqueaste sigue bloqueado",
                        leading = { Icon(Icons.Filled.CheckCircle, null, tint = Ok) })
                }
                relocks.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    val left = r.at?.let { "se vuelve a bloquear en " + Engine.fmt(it - Blocks.now()) } ?: "hasta que lo reactives"
                    SettingRow(r.label, left) {
                        TextButton({ Blocks.relock(listOf(r)) }) { Text("Bloquear ya", color = Tomato, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

            Section("Emergencia", footer = "Por si el DNS no te deja entrar a un WiFi con portal, o algo así. Pide motivo, " +
                "una espera, dura poco y queda en el historial del panel.") {
                SettingRow("Apagar el DNS un rato", onClick = { unlocking = "dns" }) {
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted)
                }
                RowDivider()
                SettingRow("Apagar el bloqueo de Xiaomi un rato", onClick = { unlocking = "applock" }) {
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted)
                }
            }
        }
    }
    unlocking?.let { kind -> UnlockDialog(kind) { unlocking = null } }
}

@Composable
private fun StatusText(on: Boolean, note: String?) {
    Text(note ?: if (on) "Encendido" else "Apagado", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        color = if (note != null) Warn else if (on) Ok else Tomato)
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tomato).clickable(onClick = onClick)
        .padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Desbloquear cuesta: motivo + espera para pensarlo + poco tiempo. */
@Composable
private fun UnlockDialog(kind: String, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(15) }
    var wait by remember { mutableIntStateOf(Blocks.unlockWait()) }
    LaunchedEffect(Unit) {
        while (wait > 0) {
            delay(1000)
            wait--
        }
    }
    val what = if (kind == "dns") "el DNS privado" else "el bloqueo de apps de Xiaomi"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card1,
        title = { Text("🔓 Apagar $what", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bloquear es fácil; desbloquear te pide pensarlo. ¿De verdad lo necesitas ahora?", color = Muted)
                OutlinedTextField(reason, { reason = it }, label = { Text("¿Para qué?") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (m == minutes) Tomato else Card2)
                            .clickable { minutes = m }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text("$m min", color = TextMain)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ok = wait <= 0 && reason.trim().length >= 4
            TextButton(enabled = ok, onClick = { Blocks.unlock(kind, minutes, reason.trim()); onDismiss() }) {
                Text(if (wait > 0) "Espera ${Engine.fmt(wait.toDouble())}" else "Desbloquear $minutes min",
                    color = if (ok) Tomato else Muted)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Mejor no", color = TextMain) } },
    )
}
