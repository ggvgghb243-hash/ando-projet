package com.example.myapplicationtestweb

import android.content.Context
import android.content.Intent
import timber.log.Timber

class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        Timber.e(e, "Uncaught exception in thread %s", t.name)
        // Attempt to restart the foreground service for continuity
        try {
            val restartIntent = Intent(context, ForegroundService::class.java)
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(restartIntent)
            } else {
                context.startService(restartIntent)
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to restart ForegroundService after crash")
        }
        // Pass to default handler (may show system crash dialog)
        defaultHandler?.uncaughtException(t, e)
    }
}
