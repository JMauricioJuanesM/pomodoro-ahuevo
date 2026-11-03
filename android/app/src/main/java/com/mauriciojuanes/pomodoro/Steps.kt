package com.mauriciojuanes.pomodoro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Pasos recientes (contador de pasos del teléfono, de bajo consumo): ayudan a la IA a ver si «fui a
 * imprimir» cuadra. Guarda unas horas de muestras en memoria. Sin el permiso ACTIVITY_RECOGNITION (se
 * otorga por ADB) o sin el sensor, simplemente no hay pasos.
 */
object Steps : SensorEventListener {
    private val samples = ArrayDeque<Pair<Double, Float>>()   // (epoch s, pasos acumulados desde el arranque)
    private const val KEEP_SEC = 4 * 3600
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        if (ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        started = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
    }

    @Synchronized
    override fun onSensorChanged(e: SensorEvent) {
        val now = Engine.now()
        samples.addLast(now to e.values[0])
        while (samples.size > 2 && now - samples.first().first > KEEP_SEC) samples.removeFirst()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Pasos desde `epoch` (segundos), o null si no se sabe. */
    @Synchronized
    fun since(epoch: Double): Int? {
        if (!started || samples.isEmpty()) return null
        val base = samples.lastOrNull { it.first <= epoch } ?: samples.first()
        return maxOf(0, (samples.last().second - base.second).toInt())
    }
}
