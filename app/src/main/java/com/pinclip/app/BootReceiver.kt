package com.pinclip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Restarts the bubble after reboot or after the APK is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Store.init(c)
        PasteService.ensure(c)
        if (Store.prefs.getBoolean("bubble", false) && Settings.canDrawOverlays(c)) {
            try { c.startForegroundService(Intent(c, FloatingService::class.java)) } catch (e: Exception) {}
        }
    }
}
