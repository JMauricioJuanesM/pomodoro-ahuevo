package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Blocks
import com.mauriciojuanes.pomodoro.Engine

/** Lo que bloqueaste en el Panel ADB: el teléfono lo cumple aunque la compu esté apagada. */
@Composable
fun BlocksScreen() {
    observe()
    var showApps by remember { mutableStateOf(false) }

    LazyColumn(Modifier.padding(horizontal = 20.dp)) {
        item {
            observe()
            ScreenTitle("Bloqueos", "Lo que bloqueaste en el Panel ADB. Se cumple aunque la compu esté apagada; " +
                "desbloquear solo se puede desde la compu.")

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

            val relocks = Blocks.relocks().sortedBy { Blocks.effectiveAt(it) ?: Double.MAX_VALUE }
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
                    val left = Blocks.effectiveAt(r)?.let { "se vuelve a bloquear en " + Engine.fmt(it - Blocks.now()) }
                        ?: "hasta que lo reactives en la compu"
                    SettingRow(r.label, left) {
                        TextButton({ Blocks.relock(listOf(r)) }) { Text("Bloquear ya", color = Tomato, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

        }
    }
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
