package com.mauriciojuanes.pomodoro

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * Servicio en primer plano: mueve el reloj cada segundo, sincroniza, pone "No molestar" durante el
 * trabajo y cuenta el tiempo de pantalla encendida en horario de trabajo.
 *
 * La compu lo arranca por ADB (`am start-foreground-service … --es url … --es token …`) si MIUI lo mata.
 */
class PomodoroService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastKey = ""
    private var lastHeartbeat = 0L
    private var lastBank = false
    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } finally {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        Sync.start()
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("url")?.let { Store.url = it }
        intent?.getStringExtra("token")?.let { Store.token = it }
        when (intent?.action) {
            ACTION_TOGGLE -> Engine.toggle()
            ACTION_BREAK -> Engine.breakNow()
            ACTION_SKIP -> Engine.skip()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(loop)
        setDnd(false)
        super.onDestroy()
    }

    private fun tick() {
        Engine.tick()
        Blocks.tick()
        BankMode.tick(this)
        val working = Engine.working()
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (working && screenOn) Store.setCount("phone_stats", Store.count("phone_stats") + 1)
        setDnd(working && Engine.cfg.optBoolean("phone_dnd", false))
        val t = Engine.timer
        val key = "${t.phase}|${t.running}|${t.endTime}|${t.remaining?.toInt()}|${t.title}|${Engine.authority}|${Engine.online}"
        if (key != lastKey) {
            lastKey = key
            getSystemService(NotificationManager::class.java).notify(ID, build())
        }
        val bank = Store.bankSince > 0
        if (System.currentTimeMillis() - lastHeartbeat > 45_000 || bank != lastBank) {   // modo banco: avisar ya
            lastBank = bank
            lastHeartbeat = System.currentTimeMillis()
            val bm = getSystemService(BatteryManager::class.java)
            Sync.heartbeat(JSONObject()
                .put("guard", GuardService.instance != null)
                .put("secure", Blocks.canWrite())
                .put("bank", Store.bankSince > 0)
                .put("dnd_access", getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
                .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("model", Build.MODEL)
                .put("version", BuildConfigInfo.VERSION))
        }
    }

    private fun setDnd(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        if (on && !Store.dndByUs) {
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {  // no pisar el tuyo
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Store.dndByUs = true
            }
        } else if (!on && Store.dndByUs) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Store.dndByUs = false
        }
    }

    private fun action(action: String, req: Int) = PendingIntent.getService(this, req,
        Intent(this, PomodoroService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    /** Notificación fija con la cuenta regresiva y los botones. */
    private fun build(): Notification {
        val t = Engine.timer
        val who = when {
            !Sync.configured() -> "Sin servidor configurado"
            !Engine.online -> "Sin conexión · el teléfono sigue solo"
            Engine.authority -> "📱 La compu está apagada: el teléfono lleva el pomodoro"
            else -> "🖥 Sincronizado con la compu"
        }
        val b = Notification.Builder(this, Notifier.CH_TIMER)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openApp(this))
            .setContentTitle(if (t.phase == "work" && !t.running) "${t.title} · en pausa ${Engine.fmt(Engine.remaining())}"
                             else t.title)
            .setContentText(who)
            .setCategory(Notification.CATEGORY_STOPWATCH)
        if (t.running && t.endTime != null) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen((t.endTime * 1000).toLong()).setShowWhen(true)
        } else {
            b.setShowWhen(false)
        }
        if (t.phase == "work") {
            b.addAction(Notification.Action.Builder(null, if (t.running) "Pausar" else "Iniciar", action(ACTION_TOGGLE, 1)).build())
            b.addAction(Notification.Action.Builder(null, "Descanso ya", action(ACTION_BREAK, 2)).build())
        } else if (t.skippable) {
            b.addAction(Notification.Action.Builder(null, "Saltar descanso", action(ACTION_SKIP, 3)).build())
        }
        return b.build()
    }

    companion object {
        const val ID = 1
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_BREAK = "break"
        const val ACTION_SKIP = "skip"
        @Volatile var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PomodoroService::class.java))
        }
    }
}

object BuildConfigInfo {
    const val VERSION = "2.4"
}
