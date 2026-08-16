package com.example.myapplicationtestweb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import timber.log.Timber

class ScreenCaptureActivity : Activity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001

        fun requestPermission(context: Context) {
            try {
                val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch ScreenCaptureActivity")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mgr != null) {
                startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            } else {
                finish()
            }
        } catch (e: Exception) {
            Timber.e(e, "ScreenCaptureActivity createScreenCaptureIntent failed")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Timber.d("MediaProjection permission granted!")
                StreamingService.handleMediaProjectionGranted(resultCode, data)
            } else {
                Timber.w("MediaProjection permission denied or canceled: resultCode=$resultCode")
            }
            finish()
        }
    }
}
