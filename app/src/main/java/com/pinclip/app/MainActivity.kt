package com.pinclip.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
            card("Floating bubble", "A small draggable bubble that floats over every app. Tap it to open your clipboard.", switchRow("Show bubble", bubbleSw)),
            LP(MATCH, WRAP).apply { topMargin = dp(20) }
        )

        // dim
        alphaLabel = label("", 14f)
        seek = SeekBar(this)
        seek.max = 100
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                alphaLabel.text = "Bubble opacity: $p%"
                if (fromUser) Store.prefs.edit().putInt("alpha", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(
            card("Bubble dim", "0 – 100%. Low values hide the bubble in the corner of your eye (it lights up while you drag it).", alphaLabel, seek),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // auto detect
        autoSw = Switch(this)
        autoSw.setOnCheckedChangeListener { _, on -> if (!sup) onAuto(on) }
        root.addView(
            card("Auto detect", "Saves everything you copy (text and images) and every screenshot as a card. Needs photo access for screenshots.", switchRow("Auto save copies & screenshots", autoSw)),
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
        root.addView(
            card("Library", null, stats, btns),
            LP(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // tips
        val tips = label(
            "• Tap a card → paste. Long-press a card → multi-select, then paste them together.\n" +
                "• Cards: pin on the left; edit, copy and delete on the right.\n" +
                "• Use {date} and {time} in a card; they turn into today's date/time when pasted.\n" +
                "• Long-press a group chip to rename or delete it.\n" +
                "• Search + 'Top used' chip find your most reused snippets fast.\n" +
                "• The ▾ button at the top of the panel shrinks it back to the bubble.",
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
        val a = Store.prefs.getInt("alpha", 10)
        seek.progress = a
        alphaLabel.text = "Bubble opacity: $a%"
        sup = false
        val on = a11yOn()
        a11yStatus.text = if (on) "✓ Instant paste is on" else "Not enabled yet"
        a11yStatus.setTextColor(if (on) C.AMBER else C.SUB)
        stats.text = "${Store.items.size} cards · ${Store.groups.size} groups · ${Store.items.count { it.pinned }} pinned"
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
