package com.example.myapplicationtestweb

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "com.example.myapplicationtestweb.ACTION_RESTART_SERVICES") {
            
            // Start Foreground and Streaming services
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val fgService = Intent(context, ForegroundService::class.java)
                    val streamService = Intent(context, StreamingService::class.java)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(fgService)
                        context.startForegroundService(streamService)
                    } else {
                        context.startService(fgService)
                        context.startService(streamService)
                    }
                } catch (e: Exception) {
                    // Ignore background start exceptions
                }
            }, 3000)

            // Setup periodic alarm watchdog (every 15 min)
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val alarmIntent = Intent(context, BootReceiver::class.java).apply {
                    this.action = "com.example.myapplicationtestweb.ACTION_RESTART_SERVICES"
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 1001, alarmIntent, flags)
                
                alarmManager?.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + (15 * 60 * 1000),
                    15 * 60 * 1000L,
                    pendingIntent
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
