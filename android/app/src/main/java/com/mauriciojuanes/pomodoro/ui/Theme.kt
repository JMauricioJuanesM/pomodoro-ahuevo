package com.mauriciojuanes.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.Engine

val Bg = Color(0xFF101217)
val Card1 = Color(0xFF1A1D24)
val Card2 = Color(0xFF252933)
val Line = Color(0xFF2C313C)
val Muted = Color(0xFF8B919C)
val TextMain = Color(0xFFECEEF2)
val Tomato = Color(0xFFE5533D)
val Ok = Color(0xFF3FB27F)
val Warn = Color(0xFFF5A623)
val WorkRed = Color(0xFFC0392B)
val Overtime = Color(0xFF8E44AD)

private val scheme = darkColorScheme(
    primary = Tomato, onPrimary = Color.White,
    secondary = Tomato, onSecondary = Color.White,
    secondaryContainer = Card2, onSecondaryContainer = TextMain,
    background = Bg, onBackground = TextMain,
    surface = Bg, onSurface = TextMain,
    surfaceVariant = Card1, onSurfaceVariant = Muted,
    outline = Line, outlineVariant = Line,
)

@Composable
fun PomodoroTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

/** Todo se redibuja cuando cambia el motor (y cada medio segundo, por el reloj). */
object Ui {
    val tick = mutableIntStateOf(0)
    fun bump() {
        tick.intValue++
    }
}

@Composable
fun observe(): Int = Ui.tick.intValue

fun hex(s: String?, def: Color = Card2): Color =
    runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(def)

fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF15171C) else Color.White

// ---------- configuración compartida con la compu ----------
// Leer `Ui.tick` aquí suscribe a quien lo llame (aunque sea dentro de una sección que Compose se saltaría)
// a los cambios de configuración: sin esto, una tarjeta podía mostrar un valor viejo.
val uiCfg get() = Engine.cfg.also { Ui.tick.intValue }
fun cInt(key: String, def: Int) = uiCfg.optInt(key, def)
fun cBool(key: String, def: Boolean) = uiCfg.optBoolean(key, def)
fun cStr(key: String, def: String) = uiCfg.optString(key, def)
fun setCfg(key: String, value: Any) = Engine.editConfig { it.put(key, value) }

// ---------- iconos que no vienen en material-icons-core ----------
val PauseIcon: ImageVector = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }
}.build()

val SkipIcon: ImageVector = ImageVector.Builder("Skip", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 6f); lineTo(14.5f, 12f); lineTo(6f, 18f); close()
        moveTo(16f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(16f, 18f); close()
    }
}.build()

// ---------- piezas reutilizables ----------
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun Section(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        if (title != null) Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted,
            letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card1), content = content)
        if (footer != null) Text(footer, fontSize = 12.sp, color = Muted, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 18.dp), color = Line, thickness = 0.5.dp)

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = TextMain)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Muted, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = Tomato, uncheckedTrackColor = Card2, uncheckedBorderColor = Line, uncheckedThumbColor = Muted))
    }

@Composable
fun StepperRow(title: String, value: Int, range: IntRange, step: Int = 1, unit: String = "", subtitle: String? = null,
               onChange: (Int) -> Unit) =
    SettingRow(title, subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RoundButton("−", enabled = value > range.first) { onChange((value - step).coerceIn(range)) }
            Text("$value$unit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain,
                modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            RoundButton("+", enabled = value < range.last) { onChange((value + step).coerceIn(range)) }
        }
    }

@Composable
fun RoundButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (enabled) Card2 else Card1)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = if (enabled) TextMain else Line, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Dot(color: Color, size: Int = 14) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
