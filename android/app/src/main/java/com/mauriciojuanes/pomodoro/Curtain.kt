package com.mauriciojuanes.pomodoro

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauriciojuanes.pomodoro.ui.Card2
import com.mauriciojuanes.pomodoro.ui.Muted
import com.mauriciojuanes.pomodoro.ui.Ok
import com.mauriciojuanes.pomodoro.ui.PomodoroTheme
import com.mauriciojuanes.pomodoro.ui.TextMain
import com.mauriciojuanes.pomodoro.ui.Tomato
import com.mauriciojuanes.pomodoro.ui.Warn
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * El «telón»: pantalla completa del teléfono, como la de la compu, para:
 *  - `break`: el descanso (corto o largo) tapa el teléfono; se puede llamar, usar el banco y contestar
 *    WhatsApp (`break_allow`), y se quita sola cuando termina;
 *  - `mind`: una frase animada al desbloquear (ver [Mind]), con 👍/👎 para que el ciclo nocturno aprenda;
 *  - `ask`: una pregunta del sistema (p. ej. qué mejorarías); se puede dejar para luego dos veces;
 *  - `task`: una tarea de TickTick que ya venció (ver [Tasks]);
 *  - `offpc`: te fuiste de la compu en horario de trabajo: ¿qué estás haciendo? (ver [Offpc]);
 *  - `sos`: tengo una urgencia: respiración guiada y luego «¿la venciste?», con dónde, con quién y alcohol.
 *
 * La abre [Curtain.show] desde el servicio o la accesibilidad (una app con accesibilidad activa puede abrir
 * pantallas desde segundo plano).
 */
class CurtainActivity : ComponentActivity() {
    private var mode by mutableStateOf("mind")
    private var payload by mutableStateOf("")
    private val tick = mutableIntStateOf(0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == "mind") finish()   // las demás no se quitan con «atrás»
            }
        })
        setContent {
            PomodoroTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        tick.intValue++
                        if (mode == "break" && !Curtain.breakLocked()) finish()
                        delay(1000)
                    }
                }
                tick.intValue
                when (mode) {
                    "break" -> BreakCurtain()
                    "ask" -> AskCurtain(payload)
                    "task" -> TaskCurtain(payload)
                    "sos" -> SosCurtain()
                    "offpc" -> OffpcCurtain()
                    else -> MindCurtain(payload)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    override fun onResume() {
        super.onResume()
        Curtain.visible = mode
    }

    override fun onPause() {
        Curtain.visible = null
        super.onPause()
    }

    private fun read(i: Intent?) {
        mode = i?.getStringExtra("mode") ?: "mind"
        payload = i?.getStringExtra("payload") ?: ""
    }

    private fun open(intent: Intent) = runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ---------- piezas ----------

    @Composable
    private fun Backdrop(top: Color, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Color(0xFF07080B)))).systemBarsPadding()
            .imePadding().padding(28.dp), contentAlignment = Alignment.Center) { content() }
    }

    @Composable
    private fun Big(text: String, size: Int = 30, modifier: Modifier = Modifier) =
        Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = TextMain, textAlign = TextAlign.Center,
            lineHeight = (size * 1.2).sp, modifier = modifier)

    @Composable
    private fun Small(text: String) = Text(text, fontSize = 16.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp)

    @Composable
    private fun Action(label: String, color: Color = Color(0xFF252933), onClick: () -> Unit) =
        Button(onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = TextMain)) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
        }

    @Composable
    private fun Breath(seconds: Int = 4) {
        val t = rememberInfiniteTransition(label = "breath")
        val s by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(seconds * 1000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse), label = "s")
        Box(Modifier.size(180.dp).scale(s).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0x8846B37B), Color(0x2246B37B)))))
    }

    @Composable
    private fun Phrase(m: JSONObject?, big: Int = 30) {
        if (m == null) return
        var a by remember(m) { mutableFloatStateOf(0f) }
        LaunchedEffect(m) {
            while (a < 1f) {
                a = minOf(1f, a + 0.05f)
                delay(30)
            }
        }
        val rise = if (m.optString("anim") == "rise") (1 - a) * 40 else 0f
        Column(Modifier.alpha(a).padding(top = rise.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Big(m.optString("text"), big)
            m.optString("sub").takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 18.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }

    // ---------- modos ----------

    @Composable
    private fun MindCurtain(id: String) {
        val m = remember(id) { Mind.deck().firstOrNull { it.optString("id") == id } ?: Mind.pick() }
        var reacted by remember { mutableStateOf(false) }
        LaunchedEffect(m) {
            delay(Engine.cfg.optLong("mind_show_sec", 7) * 1000)
            if (!reacted) finish()
        }
        Backdrop(Color(0xFF1B2333)) {
            Column(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) { finish() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (m?.optString("anim") == "breath") Breath()
                Phrase(m)
                Row(Modifier.padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf("👍" to 1, "👎" to -1).forEach { (e, v) ->
                        Text(e, fontSize = 30.sp, modifier = Modifier.clip(CircleShape).clickable {
                            reacted = true
                            m?.optString("id")?.let { Hub.react(it, v) }
                            finish()
                        }.padding(10.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun BreakCurtain() {
        val t = Engine.timer
        val m = remember { Mind.pick("break") }
        Backdrop(Color(0xFF123524)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Small("☕ ${t.title}")
                Text(Engine.fmt(Engine.remaining()), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Small("Lejos de la pantalla: levántate, estírate, toma agua.")
                Box(Modifier.padding(vertical = 26.dp)) { Phrase(m, 24) }
                Action("📞 Llamar") { open(Intent(Intent.ACTION_DIAL)) }
                if (Curtain.breakAllows(Apps.MESSAGING)) Action("💬 WhatsApp") {
                    packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { open(it) }
                }
                Action("🆘 Tengo una urgencia", Color(0xFF5A3A10)) { Curtain.show(this@CurtainActivity, "sos") }
                if (t.skippable) TextButton({ Engine.skip(); finish() }) { Text("Saltar descanso", color = Muted) }
            }
        }
    }

    @Composable
    private fun AskCurtain(id: String) {
        val ask = remember(id) { Curtain.ask(id) }
        var text by remember { mutableStateOf("") }
        if (ask == null) {
            LaunchedEffect(Unit) { finish() }
            return
        }
        val later = Store.long("ask_later_${ask.optString("id")}").toInt()
        Backdrop(Color(0xFF1E2140)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Small("💡 Faro quiere mejorar contigo")
                Big(ask.optString("question"), 24)
                val opts = ask.optJSONArray("options")
                if (opts != null) for (i in 0 until opts.length()) Action(opts.optString(i)) {
                    Hub.answer(ask.optString("id"), opts.optString(i))
                    finish()
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text("Escribe lo que se te ocurra…") })
                Action("Enviar", Tomato.copy(alpha = if (text.isBlank()) 0.4f else 1f)) {
                    if (text.isNotBlank()) {
                        Hub.answer(ask.optString("id"), text.trim())
                        finish()
                    }
                }
                if (later < 2) TextButton({
                    Store.setLong("ask_later_${ask.optString("id")}", later + 1L)
                    Store.setLong("ask_next", System.currentTimeMillis() + 90 * 60_000L)
                    finish()
                }) { Text("Ahora no (${2 - later})", color = Muted) }
            }
        }
    }

    @Composable
    private fun TaskCurtain(id: String) {
        val task = remember(id) { Tasks.items().firstOrNull { it.optString("id") == id } }
        var reason by remember { mutableStateOf("") }
        var askReason by remember { mutableStateOf<Int?>(null) }
        if (task == null) {
            LaunchedEffect(Unit) { finish() }
            return
        }
        val count = Tasks.snoozeCount(id)
        fun snooze(min: Int) {
            if (count >= 2 && reason.trim().length < 15) {
                askReason = min
                return
            }
            Hub.snoozeTask(task, min, reason.trim())
            finish()
        }
        Backdrop(Color(0xFF3A1F12)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Small("⏰ ${task.optString("project")} · ${Tasks.whenText(task)}")
                Big(task.optString("title"), 28)
                if (count > 0) Text("Ya la pospusiste $count ${if (count == 1) "vez" else "veces"}.", color = Warn, fontSize = 15.sp)
                Box(Modifier.padding(top = 18.dp))
                Action("✅ Ya la hice", Ok.copy(alpha = 0.85f)) { Hub.completeTask(task); finish() }
                Action("🎯 La hago ahora") { Hub.focusTask(task); Hub.snoozeTask(task, 60, "", counts = false); finish() }
                if (askReason != null) {
                    OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("¿Por qué la vuelves a posponer?") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 min", 60 to "1 h", 180 to "3 h").forEach { (m, l) ->
                        TextButton({ snooze(m) }) { Text("⏰ $l", color = Muted, fontSize = 16.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun OffpcCurtain() {
        var text by remember { mutableStateOf("") }
        var sent by remember { mutableStateOf<String?>(null) }
        val o = Offpc.current()
        val active = Offpc.active()
        LaunchedEffect(sent) {
            if (sent != null) {
                delay(1800)
                finish()
            }
        }
        fun send(t: String) {
            Offpc.answer(t)
            sent = if (t == Offpc.AWAY) "Va: ese rato es descanso." else "Pensándolo… te aviso en un momento."
        }
        Backdrop(Color(0xFF1F2A1A)) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sent != null) {
                    Big(sent!!, 24)
                    return@Column
                }
                if (active != null) {
                    Small("🚶 Trabajando lejos de la compu")
                    Big(active.optString("what"), 26)
                    val left = active.optDouble("until") - Engine.now()
                    Small("Cuenta como trabajo ${Engine.fmt(left)} más. " + when (active.optString("where")) {
                        "none" -> "Suelta el teléfono: si lo usas para otra cosa, se acaba."
                        else -> "En el teléfono, solo lo que necesitas para eso."
                    })
                    Box(Modifier.padding(top = 14.dp))
                    Action(Offpc.DONE, Ok.copy(alpha = 0.85f)) { Offpc.finish(); finish() }
                    TextButton({ finish() }) { Text("Cerrar", color = Muted) }
                    return@Column
                }
                val asking = o?.optString("state") == "asking"
                Small(if (asking) "🚶 No te veo en la compu" else "🚶 Trabajo lejos de la compu")
                Big(if (asking) o!!.optString("question") else "¿Qué vas a hacer lejos de la compu?", 26)
                Small("Si es trabajo, cuenta. Si es descanso, no pasa nada: dilo.")
                Box(Modifier.padding(top = 8.dp))
                val opts = o?.optJSONArray("options")
                val list = if (opts != null && opts.length() > 0) (0 until opts.length()).map { opts.optString(it) }
                else listOf("📞 Llamada de trabajo", "🖨️ Imprimir o firmar documentos", "📖 Leer o anotar en papel")
                list.forEach { Action(it) { send(it) } }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("O escribe qué estás haciendo…") })
                Action("Cuenta como trabajo", Tomato.copy(alpha = if (text.trim().length < 4) 0.4f else 1f)) {
                    if (text.trim().length >= 4) send(text.trim())
                }
                if (asking) Action(Offpc.AWAY, Color(0xFF3A3020)) { send(Offpc.AWAY) }
                TextButton({ finish() }) { Text("Ahora no", color = Muted) }
            }
        }
    }

    /** Una pregunta de un toque (opcional): resalta la opción escogida; tocarla otra vez la quita. */
    @Composable
    private fun Choice(label: String, options: List<Pair<String, String>>, chosen: String?, pick: (String?) -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 14.sp, color = Muted)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (shown, value) ->
                    Text(shown, fontSize = 15.sp, color = TextMain, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (value == chosen) Ok.copy(alpha = 0.6f) else Card2)
                        .clickable { pick(if (value == chosen) null else value) }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }

    @Composable
    private fun SosCurtain() {
        var secs by remember { mutableIntStateOf(0) }
        val logged = remember { mutableStateOf(false) }
        // Contexto de la urgencia (la hora ya va en la entrada): llega al diario como «disparador» para la IA.
        var place by remember { mutableStateOf<String?>(null) }
        var company by remember { mutableStateOf<String?>(null) }
        var alcohol by remember { mutableStateOf<String?>(null) }
        fun situation() = listOfNotNull(place, company, alcohol).joinToString(" · ").ifEmpty { null }
        fun withContext(text: String) = situation()?.let { "$text ($it)" } ?: text
        LaunchedEffect(Unit) {
            if (!logged.value) {
                logged.value = true
                Hub.journal("urgencia", "Pedí ayuda desde el teléfono", auto = true)
            }
            while (true) {
                delay(1000)
                secs++
            }
        }
        val phase = listOf("Inhala", "Sostén", "Exhala", "Sostén")[(secs / 4) % 4]
        Backdrop(Color(0xFF0F2A2A)) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Big("La ola sube, llega al pico y baja.", 26)
                Small("Respira conmigo. Levántate, sal del cuarto, toma agua fría. No estás solo en esto.")
                Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    Breath()
                    Text(phase, fontSize = 22.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
                }
                if (secs < 120) Small("${(120 - secs) / 60}:${"%02d".format((120 - secs) % 60)}")
                else {
                    Choice("¿Dónde estás?", listOf("🛏️ Cuarto" to "en el cuarto", "🏠 Casa" to "en casa",
                        "🚶 Fuera" to "fuera de casa"), place) { place = it }
                    Choice("¿Con alguien?", listOf("🙋 Solo" to "solo", "👥 Acompañado" to "acompañado"), company) { company = it }
                    Choice("¿Tomaste alcohol?", listOf("🍺 Sí" to "con alcohol", "🚫 No" to "sin alcohol"), alcohol) { alcohol = it }
                    // Las recaídas se han dado estando solo: que el siguiente paso sea hablar con alguien.
                    if (company == "solo") Action("📞 Llamar a alguien") { open(Intent(Intent.ACTION_DIAL)) }
                    Action("💪 La vencí", Ok.copy(alpha = 0.85f)) {
                        Hub.journal("victoria", withContext("Vencí la urgencia con la respiración guiada"), trigger = situation())
                        finish()
                    }
                    Action("Sigo con ganas: otra ronda") { secs = 0 }
                    TextButton({
                        Hub.journal("recaida", withContext("Registrada después del SOS"), trigger = situation())
                        finish()
                    }) { Text("Recaí (registrarlo sin juzgarme)", color = Muted) }
                }
            }
        }
    }
}

/** Cuándo y cómo se abre el telón (ver [CurtainActivity]). */
object Curtain {
    @Volatile var visible: String? = null

    fun show(ctx: Context, mode: String, payload: String = "") {
        runCatching {
            ctx.startActivity(Intent(ctx, CurtainActivity::class.java).putExtra("mode", mode).putExtra("payload", payload)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        if (mode == "mind") Mind.shown()
    }

    /** ¿El descanso tapa el teléfono ahora? */
    fun breakLocked(): Boolean {
        val t = Engine.timer
        if (!Engine.cfg.optBoolean("phone_break_lock", true) || !t.running) return false
        if (t.phase != "short" && t.phase != "long") return false
        if (Engine.remaining() <= 0) return false
        return Policy.at().phone
    }

    fun breakAllows(cat: String): Boolean {
        val arr = Engine.cfg.optJSONArray("break_allow") ?: return cat == Apps.MESSAGING
        return (0 until arr.length()).any { arr.optString(it) == cat }
    }

    /** ¿Se puede usar `pkg` durante el descanso tapado? */
    fun breakAllowed(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return true
        val cat = Apps.category(ctx, pkg)
        return cat in Apps.ALWAYS || breakAllows(cat)
    }

    /** Pregunta pendiente para el teléfono (no contestada, vigente y no pospuesta). */
    fun pendingAsk(): JSONObject? {
        if (System.currentTimeMillis() < Store.long("ask_next")) return null
        val now = System.currentTimeMillis() / 1000.0
        val asks = Store.asks
        return (0 until asks.length()).mapNotNull { asks.optJSONObject(it) }.firstOrNull { a ->
            a.isNull("answer") && a.optString("id") !in Store.answeredAsks && a.optDouble("expires", 0.0) > now &&
                a.optDouble("not_before", 0.0) <= now &&
                (a.optJSONArray("devices")?.let { d -> (0 until d.length()).any { d.optString(it) == "phone" } } ?: true)
        }
    }

    fun ask(id: String): JSONObject? = (0 until Store.asks.length()).mapNotNull { Store.asks.optJSONObject(it) }
        .firstOrNull { it.optString("id") == id }

    /**
     * Al desbloquear (y cada tanto con la pantalla prendida): lo más importante primero. Regresa true si mostró algo.
     */
    fun onUnlock(ctx: Context, unlocked: Boolean): Boolean {
        if (visible != null || inCall(ctx) || Updater.confirming() || Strikes.busy()) return false
        if (breakLocked()) {
            show(ctx, "break")
            return true
        }
        Tasks.dueNow()?.let {
            show(ctx, "task", it.optString("id"))
            return true
        }
        // Las preguntas no interrumpen un pomodoro: salen en descansos, fuera del trabajo o al desbloquear.
        if (unlocked || !Engine.working()) pendingAsk()?.let {
            show(ctx, "ask", it.optString("id"))
            return true
        }
        if (unlocked && Mind.dueOnUnlock()) {
            show(ctx, "mind")
            return true
        }
        return false
    }

    /** En llamada (normal o de WhatsApp) nunca se tapa la pantalla. */
    fun inCall(ctx: Context): Boolean {
        val mode = ctx.getSystemService(android.media.AudioManager::class.java).mode
        return mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    fun openUrl(ctx: Context, url: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
