package com.pinclip.app

import android.app.Activity
import android.content.ClipboardManager
import android.os.Build

/**
 * Invisible activity: Android only lets the app that has focus read the clipboard,
 * so the service flashes this for a moment, reads the clip, saves it and closes.
 */
class ClipReadActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            Store.init(this)
            try { read() } catch (e: Exception) {}
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun read() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val type = contentResolver.getType(uri) ?: ""
            if (desc.hasMimeType("image/*") || type.startsWith("image/")) {
                Store.addImage(uri)
                return
            }
        }
        val t = item.coerceToText(this)?.toString() ?: return
        Store.addText(t)
    }
}
