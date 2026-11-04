package com.pinclip.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/** Reads the current clipboard and saves it as a card. Returns false if the clipboard could not be read. */
object ClipReader {
    fun read(ctx: Context): Boolean {
        Store.init(ctx)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return true
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            if (desc.hasMimeType("image/*") || type.startsWith("image/")) {
                Store.addImage(uri)
                return true
            }
        }
        val t = item.coerceToText(ctx)?.toString() ?: return false
        if (Store.addText(t)) Toast.makeText(ctx, "Saved to PinClip", Toast.LENGTH_SHORT).show()
        return true
    }
}

/** Fallback: Android only lets the focused app read the clipboard, so this invisible screen takes focus briefly. */
class ClipReadActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            try { ClipReader.read(this) } catch (e: Exception) {}
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
