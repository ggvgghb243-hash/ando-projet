package com.example.myapplicationtestweb

import android.app.Application
import android.content.Intent
import android.content.Context
import timber.log.Timber

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // TODO: plant a release tree if needed
        }
        // Set global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(this))
        // Start foreground service for continuous operation
        val intent = Intent(this, ForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
