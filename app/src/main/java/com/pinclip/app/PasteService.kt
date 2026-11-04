package com.pinclip.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Used only to press "paste" in the field that currently has input focus. */
class PasteService : AccessibilityService() {

    companion object {
        @Volatile private var inst: PasteService? = null
        fun running() = inst != null
        fun paste(text: String?): Boolean = inst?.doPaste(text) ?: false
    }

    override fun onServiceConnected() { inst = this }

    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun doPaste(text: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        if (text == null || !n.isEditable) return false
        val hint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val cur = if (hint) "" else (n.text?.toString() ?: "")
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0) s = cur.length
        if (e < 0) e = cur.length
        val a = minOf(s, e).coerceIn(0, cur.length)
        val b = maxOf(s, e).coerceIn(0, cur.length)
        val merged = cur.substring(0, a) + text + cur.substring(b)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val pos = a + text.length
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}
