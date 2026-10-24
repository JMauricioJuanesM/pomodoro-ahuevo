package com.mauriciojuanes.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca el servicio al prender el teléfono y al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PomodoroService.start(ctx)
    }
}
