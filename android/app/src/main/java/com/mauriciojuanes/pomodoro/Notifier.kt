package com.mauriciojuanes.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Avisos al usuario: notificación emergente + vibración fuerte (como `_notify` por ADB). */
object Notifier {
    const val CH_TIMER = "timer"
    const val CH_ALERT = "avisos"
    private var nextId = 100

    // Tres pulsos fuertes (≈2.5 s): se nota aunque el teléfono esté en la bolsa.
    private val TIMINGS = longArrayOf(0, 600, 200, 600, 200, 1000)
    private val AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Temporizador", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Avisos", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(false) })   // vibramos nosotros, más fuerte
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    fun alert(ctx: Context, title: String, text: String, vibrate: Boolean = true) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // En "No molestar" puesto por nosotros no sonaría: lo quitamos un momento para avisar.
        val dnd = Store.dndByUs && nm.isNotificationPolicyAccessGranted
        if (dnd) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        nm.notify(nextId++, n)
        if (vibrate) vibrate(ctx)
        if (dnd) Handler(Looper.getMainLooper()).postDelayed({
            if (Store.dndByUs) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }, 4000)
    }

    fun vibrate(ctx: Context) {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(TIMINGS, AMPLITUDES, -1))
    }
}
