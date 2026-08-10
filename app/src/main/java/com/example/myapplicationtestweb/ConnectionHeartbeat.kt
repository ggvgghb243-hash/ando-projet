package com.example.myapplicationtestweb

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber

class ConnectionHeartbeat(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = 30_000L // 30 seconds
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                // Simple lightweight ping to Firebase to keep connection alive
                // Assuming a reference to deviceRef is accessible via a singleton or passed in
                // Here we just log for demonstration
                Timber.d("Heartbeat ping")
                // TODO: implement actual ping if needed, e.g., deviceRef?.child("heartbeat").setValue(ServerValue.TIMESTAMP)
            } catch (e: Exception) {
                Timber.e(e, "Heartbeat error")
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        handler.post(heartbeatRunnable)
    }

    fun stop() {
        handler.removeCallbacks(heartbeatRunnable)
    }
}
