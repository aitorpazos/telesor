package dev.telesor

import android.app.Application

class TelesorApp : Application() {

    lateinit var preferences: dev.telesor.data.TelesorPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = dev.telesor.data.TelesorPreferences(this)
    }

    companion object {
        lateinit var instance: TelesorApp
            private set
    }
}
