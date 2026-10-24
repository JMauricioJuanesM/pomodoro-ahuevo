package com.mauriciojuanes.pomodoro

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.init(this)
        Notifier.channels(this)
    }
}
