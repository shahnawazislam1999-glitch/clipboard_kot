package com.pinclip.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var bubbleSw: Switch
    private lateinit var autoSw: Switch
    private lateinit var alphaLabel: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var stats: TextView
    private lateinit var seek: SeekBar
    private lateinit var autoStatus: TextView
    private lateinit var seek2: SeekBar
    private lateinit var palphaLabel: TextView
    private lateinit var keepStatus: TextView
    private var sup = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        build()
    }

    override fun onResume() {
        super.onResume()
        PasteService.ensure(this)
        if (Store.prefs.getBoolean("bubble", false) && Settings.canDrawOverlays(this)) {
            try { startForegroundService(Intent(this, FloatingService::class.java)) } catch (e: Exception) {}
        }
        refresh()
    }

    private fun card(title: String, desc: String?, vararg views: View): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(C.CARD, 16)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val t = label(title, 16f)
        t.setTypeface(t.typeface, Typeface.BOLD)
        c.addView(t)
        if (desc != null) {
            val d = label(desc, 12f, C.SUB)
            d.setPadding(0, dp(2), 0, dp(8))
            c.addView(d)
        }
        for (v in views) c.addView(v)
        return c
    }

    private fun switchRow(text: String, sw: Switch): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(label(text, 14f), LP(0, WRAP, 1f))
        r.addView(sw)
        return r
    }

    private fun build() {
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(32))
        sv.addView(root)

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = iconView(R.drawable.ic_pin, android.graphics.Color.WHITE, 28, 10)
        logo.background = rounded(C.ACCENT, 16)
        head.addView(logo)
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(12), 0, 0, 0)
        val tt = label("PinClip", 26f)
        tt.setTypeface(tt.typeface, Typeface.BOLD)
        titles.addView(tt)
        titles.addView(label("Floating smart clipboard", 13f, C.SUB))
        head.addView(titles)
        root.addView(head, LP(MATCH, WRAP))

        // bubble
        bubbleSw = Switch(this)
        bubbleSw.setOnCheckedChangeListener { _, on -> if (!sup) onBubble(on) }
        root.addView(
            card("Edge handle (one tap)", "Tap the colored handle on the screen edge once to open your clipboard. Press and hold it, then drag, to move it anywhere along the left or right edge.", switchRow("Show handle", bubbleSw)),
            LP(MATCH, WRAP).apply { topMargin = dp(20) }
        )

        // dim
        alphaLabel = label("", 14f)
        seek = SeekBar(this)
        seek.max = 100
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                alphaLabel.text = "Handle opacity: $p%"
                if (fromUser) Store.prefs.edit().putInt("halpha", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(
            card("Handle opacity", "0 – 100%. At 0 the handle is invisible but still tappable. It lights up while you touch it.", alphaLabel, seek),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // handle colour
        val swatches = LinearLayout(this)
        swatches.orientation = LinearLayout.HORIZONTAL
        fun drawSwatches() {
            swatches.removeAllViews()
            val cur = Store.prefs.getInt("hcolor", HANDLE_COLORS[0])
            for (c in HANDLE_COLORS) {
                val dot = View(this)
                val g = android.graphics.drawable.GradientDrawable()
                g.shape = android.graphics.drawable.GradientDrawable.OVAL
                g.setColor(c)
                if (c == cur) g.setStroke(dp(3), android.graphics.Color.WHITE) else g.setStroke(dp(1), 0x55FFFFFF)
                dot.background = g
                dot.setOnClickListener { Store.prefs.edit().putInt("hcolor", c).apply(); drawSwatches() }
                swatches.addView(dot, LP(dp(36), dp(36)).apply { rightMargin = dp(12) })
            }
        }
        drawSwatches()
        root.addView(
            card("Handle color", "Pick a color so this handle is never confused with another app's edge handle.", swatches),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // window opacity
        palphaLabel = label("", 14f)
        seek2 = SeekBar(this)
        seek2.max = 100
        seek2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                palphaLabel.text = "Window opacity: $p%"
                if (fromUser) Store.prefs.edit().putInt("palpha", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(
            card("Window transparency", "Make the open clipboard window see-through so you can read the app behind it (minimum 15%). It turns solid while you type.", palphaLabel, seek2),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // auto detect
        autoSw = Switch(this)
        autoStatus = label("", 12f, C.SUB)
        autoSw.setOnCheckedChangeListener { _, on -> if (!sup) onAuto(on) }
        root.addView(
            card("Auto detect", "Saves everything you copy (text and images) and every screenshot as a card. Needs photo access for screenshots.", switchRow("Auto save copies & screenshots", autoSw), autoStatus),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // paste access
        a11yStatus = label("", 13f, C.SUB)
        val openA11y = pill("Open Accessibility settings", C.ACCENT, android.graphics.Color.WHITE)
        openA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(
            card(
                "Instant paste",
                "Enable PinClip under Installed apps so tapping a card pastes straight into the field you are typing in. Without it the card is only copied. If the switch is greyed out: App info → ⋮ → Allow restricted settings.",
                a11yStatus, openA11y
            ),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // keep alive
        keepStatus = label("", 13f, C.SUB)
        val bat = pill("Battery: set Unrestricted", C.ACCENT, android.graphics.Color.WHITE)
        bat.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) {}
            }
        }
        val auto = pill("Autostart / background settings", C.CARD2)
        auto.setOnClickListener { openAutostart() }
        val info = pill("App info", C.CARD2)
        info.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val cmdCopy = pill("Copy adb command (auto-keep Accessibility on)", C.CARD2, C.AMBER)
        cmdCopy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmd))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        val kcol = LinearLayout(this)
        kcol.orientation = LinearLayout.VERTICAL
        for (v in listOf<View>(bat, auto, info, cmdCopy)) kcol.addView(v, LP(WRAP, WRAP).apply { topMargin = dp(6) })
        root.addView(
            card(
                "Keep running",
                "The handle runs as a protected service, restarts itself after a swipe from recents, a reboot or an app update. In your phone's recents screen also lock PinClip (long-press its card → Lock). Autostart must be allowed on Xiaomi, Oppo, Vivo, Huawei.",
                keepStatus, kcol
            ),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // library
        stats = label("", 13f, C.SUB)
        val exp = pill("Export text", C.CARD2)
        exp.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, Store.exportText())
            startActivity(Intent.createChooser(i, "Export clipboard"))
        }
        val clr = pill("Clear unpinned", C.CARD2, C.RED)
        clr.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear unpinned cards?")
                .setMessage("Pinned cards stay. This cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> Store.clearUnpinned(); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(exp, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        btns.addView(clr)

        val bk = pill("Backup (export)", C.ACCENT, android.graphics.Color.WHITE)
        bk.setOnClickListener { doBackup() }
        val rs = pill("Restore (import)", C.CARD2)
        rs.setOnClickListener { doRestore() }
        val b2 = LinearLayout(this)
        b2.orientation = LinearLayout.HORIZONTAL
        b2.addView(bk, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        b2.addView(rs)
        val addImg = pill("Add images", C.CARD2, C.ACCENT)
        addImg.setOnClickListener { startActivity(Intent(this, ImagePickActivity::class.java)) }
        val b3 = LinearLayout(this)
        b3.orientation = LinearLayout.HORIZONTAL
        b3.addView(addImg)
        b2.layoutParams = LP(MATCH, WRAP).apply { topMargin = dp(8) }
        b3.layoutParams = LP(MATCH, WRAP).apply { topMargin = dp(8) }
        root.addView(
            card("Library", "Backup saves all cards, groups and images into one .zip file you can restore on any phone.", stats, btns, b2, b3),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // tips
        val tips = label(
            "• Image cards work like text cards: add them with the picture button, add a caption, group, pin or paste them.\n" +
                "• Tap a card → paste. Long-press a card → multi-select, then paste them together.\n" +
                "• Cards: pin on the left; edit, copy and delete on the right.\n" +
                "• Use {date} and {time} in a card; they turn into today's date/time when pasted.\n" +
                "• Long-press a group chip to rename or delete it.\n" +
                "• Search + 'Top used' chip find your most reused snippets fast.\n" +
                "• Notification: tap it (or its Open button) to show the clipboard window even when the handle is hidden; Hide/Show handle toggles the handle.\n" +
                "• ⇕ icon = reading mode (full text). Select mode → Select all → Move to group or Delete. Paste all pastes the whole current view, oldest first.\n" +
                "• Drag the window by its title bar; drag the grip at the bottom-right corner to resize. Position and size are remembered.\n" +
                "• The ▾ button at the top of the panel shrinks it back to the edge handle.",
            12f, C.SUB
        )
        root.addView(
            card("Tips", null, tips),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )
        setContentView(sv)
    }

    private fun refresh() {
        sup = true
        bubbleSw.isChecked = Store.prefs.getBoolean("bubble", false) && Settings.canDrawOverlays(this)
        autoSw.isChecked = Store.prefs.getBoolean("auto", false) && bubbleSw.isChecked
        val a = Store.prefs.getInt("halpha", 40)
        seek.progress = a
        alphaLabel.text = "Handle opacity: $a%"
        val pa = Store.prefs.getInt("palpha", 100)
        seek2.progress = pa
        palphaLabel.text = "Window opacity: $pa%"
        sup = false
        val on = a11yOn()
        val mp = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val photos = checkSelfPermission(mp) == PackageManager.PERMISSION_GRANTED
        autoStatus.text = "Text copies: " + (if (autoSw.isChecked) "✓ on" else "✗ switch is off") +
            "\nScreenshots: " + (if (photos) "✓ photo access granted" else "✗ photo access missing – turn the switch off and on again and tap Allow")
        val ign = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val sec = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        keepStatus.text = (if (ign) "✓ Battery unrestricted" else "✗ Battery optimisation still on") + "\n" +
            (if (sec) "✓ Accessibility is kept on automatically" else "Optional: run the adb command once so Accessibility can never switch off")
        autoStatus.text = autoStatus.text.toString() + "\nAccessibility: " + (if (on) "✓ on" else "✗ OFF – turn it on to catch every copy") +
            "\n\nRecent activity:\n" + (Store.prefs.getString("log", "") ?: "").ifEmpty { "(nothing yet)" }
        a11yStatus.text = if (on) "✓ Instant paste is on" else "Not enabled yet"
        a11yStatus.setTextColor(if (on) C.AMBER else C.SUB)
        stats.text = "${Store.items.size} cards · ${Store.groups.size} groups · ${Store.items.count { it.pinned }} pinned"
    }

    private fun doBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_TITLE, "PinClip-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".zip")
        startActivityForResult(i, 10)
    }

    private fun doRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 11)
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (rc == 10) {
            val js = Store.backupJson()
            val imgs = Store.backupImages()
            Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { Store.writeZip(it, js, imgs) }
                    true
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Backup saved (${imgs.size} images)" else "Backup failed", Toast.LENGTH_LONG).show()
                }
            }.start()
        } else if (rc == 11) {
            AlertDialog.Builder(this)
                .setTitle("Restore backup")
                .setMessage("Merge adds missing cards and keeps what you have. Replace wipes everything first.")
                .setPositiveButton("Merge") { _, _ -> runImport(uri, false) }
                .setNegativeButton("Replace all") { _, _ -> runImport(uri, true) }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun runImport(uri: Uri, replace: Boolean) {
        Thread {
            val ins = try { contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            if (ins == null) {
                runOnUiThread { Toast.makeText(this, "Could not open that file", Toast.LENGTH_LONG).show() }
            } else {
                Store.importZip(ins, replace) { n ->
                    Toast.makeText(this, if (n >= 0) "Restored $n cards" else "Not a valid PinClip backup", Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }.start()
    }

    private fun openAutostart() {
        val list = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        for ((pkg, cls) in list) {
            try {
                startActivity(Intent().setComponent(ComponentName(pkg, cls)))
                return
            } catch (e: Exception) {
            }
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(packageName) && s.contains("PasteService")
    }

    private fun onBubble(on: Boolean) {
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                sup = true; bubbleSw.isChecked = false; sup = false
                Toast.makeText(this, "Allow \"Display over other apps\", then switch on again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            Store.prefs.edit().putBoolean("bubble", true).apply()
            startForegroundService(Intent(this, FloatingService::class.java))
        } else {
            Store.prefs.edit().putBoolean("bubble", false).putBoolean("auto", false).apply()
            stopService(Intent(this, FloatingService::class.java))
            sup = true; autoSw.isChecked = false; sup = false
        }
    }

    private fun onAuto(on: Boolean) {
        if (on) {
            if (!bubbleSw.isChecked) bubbleSw.isChecked = true
            if (!bubbleSw.isChecked) {
                sup = true; autoSw.isChecked = false; sup = false
                return
            }
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(p), 2)
        }
        Store.prefs.edit().putBoolean("auto", on).apply()
    }
}
