package dev.remoty

import android.app.Application

class RemotyApp : Application() {

    lateinit var preferences: dev.remoty.data.RemotyPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = dev.remoty.data.RemotyPreferences(this)
    }

    companion object {
        lateinit var instance: RemotyApp
            private set
    }
}
