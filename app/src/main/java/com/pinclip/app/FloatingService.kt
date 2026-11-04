package com.pinclip.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        const val STOP = "com.pinclip.app.STOP"
        const val CH = "pinclip"
    }

    private lateinit var wm: WindowManager
    private lateinit var cm: ClipboardManager
    private val h = Handler(Looper.getMainLooper())

    private var bubble: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var body: FrameLayout? = null
    private var multiBar: LinearLayout? = null
    private var chipRow: LinearLayout? = null
    private var searchBox: EditText? = null
    private var fullH = 0
    private var shortH = 0

    private var autoOn = false
    private var obs: ContentObserver? = null
    private val seen = HashSet<Long>()

    private var filter = "all"
    private var query = ""
    private var multi = false
    private var editorOpen = false
    private val selected = LinkedHashSet<String>()
    private var pendingDelete: String? = null
    private var sep = 0
    private val seps = listOf("New line" to "\n", "Space" to " ", "Comma" to ", ")

    private val prefL = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == "alpha") applyAlpha() else if (k == "palpha") applyPanelAlpha() else if (k == "auto") applyAuto()
    }
    private val clipL = ClipboardManager.OnPrimaryClipChangedListener { onClip() }
    private val clipRun = Runnable { readClip() }
    private val shotRun = Runnable { Thread { checkShot() }.start() }
    private val keepRun = object : Runnable {
        override fun run() {
            PasteService.ensure(this@FloatingService)
            h.postDelayed(this, 30000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "PinClip", NotificationManager.IMPORTANCE_LOW))
        Store.prefs.registerOnSharedPreferenceChangeListener(prefL)
        Store.onChange = { if (panel != null) renderList() }
        if (Settings.canDrawOverlays(this)) addBubble()
        applyAuto()
        h.post(keepRun)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    /** If the system or the user swipes us away while the bubble should be on, come back. */
    private fun scheduleRestart() {
        if (!Store.prefs.getBoolean("bubble", false)) return
        try {
            val pi = PendingIntent.getForegroundService(
                this, 2, Intent(this, FloatingService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            (getSystemService(ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pi)
        } catch (e: Exception) {
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == STOP) {
            Store.prefs.edit().putBoolean("bubble", false).putBoolean("auto", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (bubble == null && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduleRestart()
        Store.prefs.unregisterOnSharedPreferenceChangeListener(prefL)
        if (autoOn) {
            cm.removePrimaryClipChangedListener(clipL)
            obs?.let { contentResolver.unregisterContentObserver(it) }
            autoOn = false
        }
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble = null
        Store.onChange = null
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle("PinClip is running")
            .setContentText("Tap the bubble to open your clipboard")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Hide bubble", stop).build())
            .setOngoing(true)
            .build()
    }

    // ───────────────────────── bubble ─────────────────────────

    private fun overlayLp(ww: Int, hh: Int) = WindowManager.LayoutParams(
        ww, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun addBubble() {
        val size = dp(52)
        val v = FrameLayout(this)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6C8CFF.toInt(), 0xFF8E5BFF.toInt()))
        bg.shape = GradientDrawable.OVAL
        v.background = bg
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_clip)
        ic.setColorFilter(Color.WHITE)
        v.addView(ic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val lp = overlayLp(size, size)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Store.prefs.getInt("bx", 0)
        lp.y = Store.prefs.getInt("by", dp(240))
        bubble = v
        bubbleLp = lp
        wm.addView(v, lp)
        applyAlpha()
        attachDrag(v, { bubble }, { bubbleLp }, { openPanel() }, { snap() })
    }

    private fun applyAlpha() {
        val p = Store.prefs.getInt("alpha", 10).coerceIn(0, 100)
        bubble?.alpha = (p / 100f).coerceAtLeast(0.03f)
    }

    private fun snap() {
        val lp = bubbleLp ?: return
        val v = bubble ?: return
        val dm = resources.displayMetrics
        lp.x = if (lp.x + v.width / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - v.width
        lp.y = lp.y.coerceIn(0, dm.heightPixels - v.height)
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
        Store.prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
    }

    private fun attachDrag(
        handle: View, target: () -> View?, lpOf: () -> WindowManager.LayoutParams?,
        onTap: (() -> Unit)?, onEnd: (() -> Unit)?
    ) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            val lp = lpOf()
            val t = target()
            if (lp == null || t == null) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    if (t === bubble) t.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = sx + dx
                        lp.y = sy + dy
                        try { wm.updateViewLayout(t, lp) } catch (ex: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (t === bubble) applyAlpha()
                    if (moved) onEnd?.invoke() else onTap?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (t === bubble) applyAlpha()
                }
            }
            true
        }
    }

    // ───────────────────────── panel ─────────────────────────

    private fun openPanel() {
        if (panel != null) return
        bubble?.visibility = View.GONE
        buildPanel()
    }

    private fun closePanel() {
        hideKb()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null; panelLp = null; body = null; multiBar = null; chipRow = null; searchBox = null
        multi = false; editorOpen = false; selected.clear(); pendingDelete = null
        bubble?.visibility = View.VISIBLE
        applyAlpha()
    }

    private fun setFocusable(on: Boolean) {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.height = if (on) shortH else fullH
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
        if (on) p.alpha = 1f else applyPanelAlpha()
    }

    private fun applyPanelAlpha() {
        val p = panel ?: return
        if (editorOpen) { p.alpha = 1f; return }
        p.alpha = (Store.prefs.getInt("palpha", 100).coerceIn(0, 100) / 100f).coerceAtLeast(0.15f)
    }

    private fun savePanelGeometry() {
        val lp = panelLp ?: return
        Store.prefs.edit().putInt("px", lp.x).putInt("py", lp.y).putInt("pw", lp.width).putInt("ph", fullH).apply()
    }

    private fun hideKb() {
        val p = panel ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(p.windowToken, 0)
        searchBox?.clearFocus()
    }

    private fun showKb(et: EditText) {
        h.postDelayed({
            et.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, 0)
        }, 220)
    }

    private fun curGroup(): String? = if (filter == "all" || filter == "pinned" || filter == "used") null else filter

    private fun buildPanel() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val w = Store.prefs.getInt("pw", minOf(dp(340), sw - dp(16))).coerceIn(dp(200), sw)
        fullH = Store.prefs.getInt("ph", (sh * 0.62f).toInt()).coerceIn(dp(220), sh)
        shortH = minOf(fullH, (sh * 0.42f).toInt())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(C.BG, 18)
        root.setPadding(dp(10), dp(8), dp(10), dp(10))
        root.elevation = dp(8).toFloat()

        // header (drag handle)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val minB = iconView(R.drawable.ic_minimize, C.TEXT)
        minB.background = rounded(C.CARD2, 16)
        minB.setOnClickListener { closePanel() }
        head.addView(minB)
        val title = label("PinClip", 16f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setPadding(dp(10), dp(8), 0, dp(8))
        head.addView(title, LP(0, WRAP, 1f))
        val multiB = iconView(R.drawable.ic_multi, C.SUB)
        multiB.setOnClickListener {
            multi = !multi
            selected.clear()
            multiB.setColorFilter(if (multi) C.ACCENT else C.SUB)
            updateMultiBar()
            renderList()
        }
        head.addView(multiB)
        val addB = iconView(R.drawable.ic_add, C.ACCENT)
        addB.setOnClickListener {
            showEditor("New text", "", curGroup(), true, false, null) { t, g ->
                Store.addText(t, g)
            }
        }
        head.addView(addB)
        root.addView(head, LP(MATCH, WRAP))

        // search
        val sRow = LinearLayout(this)
        sRow.orientation = LinearLayout.HORIZONTAL
        sRow.gravity = Gravity.CENTER_VERTICAL
        sRow.background = rounded(C.CARD, 12)
        sRow.setPadding(dp(8), 0, dp(8), 0)
        sRow.addView(iconView(R.drawable.ic_search, C.SUB, 18, 4))
        val et = EditText(this)
        et.hint = "Search"
        et.setHintTextColor(C.SUB)
        et.setTextColor(C.TEXT)
        et.textSize = 14f
        et.background = null
        et.isSingleLine = true
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setText(query)
        et.doAfterTextChanged { query = it?.toString() ?: ""; renderList() }
        et.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                setFocusable(true)
                showKb(et)
            }
            false
        }
        et.setOnFocusChangeListener { _, f -> if (!f && !editorOpen) setFocusable(false) }
        et.setOnEditorActionListener { _, _, _ -> hideKb(); setFocusable(false); true }
        sRow.addView(et, LP(0, WRAP, 1f))
        searchBox = et
        root.addView(sRow, LP(MATCH, dp(40)).apply { topMargin = dp(8) })

        // chips
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        hs.addView(row)
        chipRow = row
        root.addView(hs, LP(MATCH, WRAP).apply { topMargin = dp(8) })

        // body
        val b = FrameLayout(this)
        body = b
        root.addView(b, LP(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // multi bar
        val mb = LinearLayout(this)
        mb.orientation = LinearLayout.HORIZONTAL
        mb.gravity = Gravity.CENTER_VERTICAL
        mb.visibility = View.GONE
        multiBar = mb
        root.addView(mb, LP(MATCH, WRAP).apply { topMargin = dp(8) })

        // resize grip (bottom-right): drag to make the window bigger or smaller
        val gripRow = LinearLayout(this)
        gripRow.gravity = Gravity.END
        val grip = iconView(R.drawable.ic_resize, C.SUB, 18, 5)
        gripRow.addView(grip)
        root.addView(gripRow, LP(MATCH, WRAP))
        attachResize(grip)

        val lp = overlayLp(w, fullH)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Store.prefs.getInt("px", (sw - w) / 2).coerceIn(0, maxOf(0, sw - w))
        lp.y = Store.prefs.getInt("py", dp(60)).coerceIn(0, maxOf(0, sh - dp(120)))
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        panel = root
        panelLp = lp
        wm.addView(root, lp)
        attachDrag(head, { panel }, { panelLp }, null) { savePanelGeometry() }
        applyPanelAlpha()

        renderChips()
        renderList()
        updateMultiBar()
    }

    private fun attachResize(grip: View) {
        var sw0 = 0
        var sh0 = 0
        var tx = 0f
        var ty = 0f
        grip.setOnTouchListener { _, e ->
            val lp = panelLp
            val p = panel
            if (lp == null || p == null) return@setOnTouchListener false
            val dm = resources.displayMetrics
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw0 = lp.width; sh0 = lp.height; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw0 + (e.rawX - tx)).toInt().coerceIn(dp(200), maxOf(dp(200), dm.widthPixels - lp.x))
                    lp.height = (sh0 + (e.rawY - ty)).toInt().coerceIn(dp(220), maxOf(dp(220), dm.heightPixels - lp.y))
                    fullH = lp.height
                    shortH = minOf(fullH, (dm.heightPixels * 0.42f).toInt())
                    try { wm.updateViewLayout(p, lp) } catch (ex: Exception) {}
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanelGeometry()
            }
            true
        }
    }

    private fun renderChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        fun chip(text: String, key: String, g: Group?) {
            val sel = filter == key
            val t = pill(text, if (sel) C.ACCENT else C.CARD, if (sel) Color.WHITE else C.TEXT)
            t.setOnClickListener { filter = key; renderChips(); renderList() }
            if (g != null) t.setOnLongClickListener { groupMenu(g); true }
            row.addView(t, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        chip("All", "all", null)
        chip("Pinned", "pinned", null)
        chip("Top used", "used", null)
        Store.groups.forEach { chip(it.name, it.id, it) }
        val add = pill("+ Group", C.CARD2, C.ACCENT)
        add.setOnClickListener {
            showEditor("New group name", "", null, false, true, null) { t, _ -> Store.addGroup(t); renderChips() }
        }
        row.addView(add)
    }

    private fun groupMenu(g: Group) {
        showEditor("Rename group", g.name, null, false, true, "Delete group" to {
            Store.deleteGroup(g.id)
            if (filter == g.id) filter = "all"
        }) { t, _ -> Store.renameGroup(g, t); renderChips() }
    }

    private fun filtered(): List<Item> {
        val q = query.trim().lowercase()
        var l = Store.items.filter {
            when (filter) {
                "all" -> true
                "pinned" -> it.pinned
                "used" -> it.uses > 0
                else -> it.groupId == filter
            }
        }
        if (q.isNotEmpty()) {
            l = l.filter { x ->
                x.text.lowercase().contains(q) ||
                    (Store.groups.find { g -> g.id == x.groupId }?.name?.lowercase()?.contains(q) == true)
            }
        }
        return if (filter == "used")
            l.sortedWith(compareByDescending<Item> { it.uses }.thenByDescending { it.created })
        else
            l.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.created })
    }

    private fun renderList() {
        val b = body ?: return
        if (editorOpen) return
        b.removeAllViews()
        val list = filtered()
        if (list.isEmpty()) {
            val e = label("Nothing here yet.\nTap + to add text, or turn on Auto detect.", 13f, C.SUB)
            e.gravity = Gravity.CENTER
            b.addView(e, FrameLayout.LayoutParams(MATCH, MATCH))
            return
        }
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        for (it0 in list) col.addView(card(it0), LP(MATCH, WRAP).apply { bottomMargin = dp(6) })
        sv.addView(col)
        b.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun card(x: Item): View {
        val sel = selected.contains(x.id)
        val pend = pendingDelete == x.id
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(6), dp(4), dp(6))
        row.background = rounded(if (pend) 0xFF4A2630.toInt() else if (sel) 0xFF2E3A66.toInt() else C.CARD, 12)

        val pin = iconView(R.drawable.ic_pin, if (x.pinned) C.AMBER else C.SUB)
        pin.setOnClickListener { Store.togglePin(x) }
        row.addView(pin)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(4), 0, dp(4), 0)
        if (x.image != null) {
            val iv = ImageView(this)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.maxHeight = dp(110)
            val tf = Store.thumb(x)
            if (tf.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(tf.path))
            mid.addView(iv, LP(MATCH, WRAP))
        } else {
            val t = label(x.text, 14f)
            t.maxLines = 4
            t.ellipsize = TextUtils.TruncateAt.END
            mid.addView(t)
        }
        val meta = listOfNotNull(
            Store.groups.find { g -> g.id == x.groupId }?.name,
            if (x.uses > 0) "${x.uses}×" else null,
            if (x.image != null) "image" else null
        ).joinToString(" · ")
        if (meta.isNotEmpty()) mid.addView(label(meta, 11f, C.SUB))
        row.addView(mid, LP(0, WRAP, 1f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        if (x.image == null) {
            val ed = iconView(R.drawable.ic_edit, C.SUB, 16, 5)
            ed.setOnClickListener {
                showEditor("Edit text", x.text, x.groupId, true, false, null) { t, g -> Store.update(x, t, g) }
            }
            right.addView(ed)
        } else {
            val gr = iconView(R.drawable.ic_group, C.SUB, 16, 5)
            gr.setOnClickListener {
                val ids = listOf<String?>(null) + Store.groups.map { g -> g.id }
                val next = ids[(ids.indexOf(x.groupId) + 1) % ids.size]
                Store.setGroup(x, next)
                toast("Group: " + (Store.groups.find { g -> g.id == next }?.name ?: "None"))
            }
            right.addView(gr)
        }
        val cp = iconView(R.drawable.ic_copy, C.SUB, 16, 5)
        cp.setOnClickListener { copyOnly(x) }
        right.addView(cp)
        val del = iconView(if (pend) R.drawable.ic_check else R.drawable.ic_delete, if (pend) C.RED else C.SUB, 16, 5)
        del.setOnClickListener {
            if (pendingDelete == x.id) {
                pendingDelete = null
                selected.remove(x.id)
                Store.delete(x)
            } else {
                pendingDelete = x.id
                renderList()
                h.postDelayed({ if (pendingDelete == x.id) { pendingDelete = null; renderList() } }, 2500)
            }
        }
        right.addView(del)
        row.addView(right)

        row.setOnClickListener {
            if (multi) {
                if (!selected.add(x.id)) selected.remove(x.id)
                updateMultiBar()
                renderList()
            } else paste(x)
        }
        row.setOnLongClickListener {
            multi = true
            selected.add(x.id)
            updateMultiBar()
            renderList()
            true
        }
        return row
    }

    private fun updateMultiBar() {
        val bar = multiBar ?: return
        bar.removeAllViews()
        bar.visibility = if (multi) View.VISIBLE else View.GONE
        if (!multi) return
        bar.addView(label("${selected.size} selected", 13f, C.SUB), LP(0, WRAP, 1f))
        val sp = pill(seps[sep].first, C.CARD2)
        sp.setOnClickListener { sep = (sep + 1) % seps.size; updateMultiBar() }
        bar.addView(sp, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        val go = pill("Paste", C.ACCENT, Color.WHITE)
        go.setOnClickListener { pasteMany() }
        bar.addView(go, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        val done = pill("Done", C.CARD2)
        done.setOnClickListener { multi = false; selected.clear(); updateMultiBar(); renderList() }
        bar.addView(done)
    }

    // ───────────────────────── editor ─────────────────────────

    private fun showEditor(
        title: String, initial: String, group: String?, withGroup: Boolean, single: Boolean,
        danger: Pair<String, () -> Unit>?, onSave: (String, String?) -> Unit
    ) {
        val b = body ?: return
        editorOpen = true
        setFocusable(true)
        b.removeAllViews()
        var gid = group
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 13f, C.SUB))

        val et = EditText(this)
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        val cancel = pill("Cancel", C.CARD2)
        cancel.setOnClickListener { closeEditor() }
        val save = pill("Save", C.ACCENT, Color.WHITE)
        save.setOnClickListener {
            val v = et.text.toString()
            if (v.isNotBlank()) {
                closeEditor()
                onSave(v, gid)
            }
        }
        btns.addView(cancel, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        btns.addView(save, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        if (danger != null) {
            val d = pill(danger.first, C.CARD2, C.RED)
            d.setOnClickListener { closeEditor(); danger.second() ; renderChips() }
            btns.addView(d)
        }
        col.addView(btns, LP(MATCH, WRAP).apply { topMargin = dp(6) })

        if (withGroup) {
            fun gl() = "Group: " + (Store.groups.find { g -> g.id == gid }?.name ?: "None")
            val gb = pill(gl(), C.CARD2, C.ACCENT)
            gb.setOnClickListener {
                val ids = listOf<String?>(null) + Store.groups.map { g -> g.id }
                gid = ids[(ids.indexOf(gid) + 1) % ids.size]
                gb.text = gl()
            }
            col.addView(gb, LP(WRAP, WRAP).apply { topMargin = dp(6) })
        }

        et.setText(initial)
        et.setTextColor(C.TEXT)
        et.setHintTextColor(C.SUB)
        et.hint = if (single) "Name" else "Type or paste text… ({date} and {time} work too)"
        et.textSize = 15f
        et.background = rounded(C.CARD, 12)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (single) et.isSingleLine = true else {
            et.minLines = 3
            et.gravity = Gravity.TOP or Gravity.START
        }
        et.setSelection(et.text.length)
        col.addView(et, LP(MATCH, if (single) WRAP else 0, if (single) 0f else 1f).apply { topMargin = dp(8) })
        b.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        showKb(et)
    }

    private fun closeEditor() {
        hideKb()
        editorOpen = false
        setFocusable(false)
        renderList()
    }

    // ───────────────────────── paste / copy ─────────────────────────

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun paste(x: Item) {
        Store.touch(x)
        hideKb()
        setFocusable(false)
        h.postDelayed({
            if (x.image != null) pasteImage(x) else pasteText(Store.expand(x.text))
        }, 150)
    }

    private fun pasteText(t: String) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        cm.setPrimaryClip(ClipData.newPlainText("PinClip", t))
        val ok = PasteService.paste(t)
        toast(
            if (ok) "Pasted" else if (PasteService.running()) "Copied – no text field is focused"
            else "Copied – turn on Instant paste in the app"
        )
    }

    private fun imageClip(x: Item): ClipData {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", Store.imageFile(x))
        return ClipData.newUri(contentResolver, "PinClip image", uri)
    }

    private fun pasteImage(x: Item) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        cm.setPrimaryClip(imageClip(x))
        val ok = PasteService.paste(null)
        toast(if (ok) "Pasted" else "Image copied – paste it in the app")
    }

    private fun copyOnly(x: Item) {
        Store.ignoreClipUntil = SystemClock.elapsedRealtime() + 3000
        if (x.image != null) cm.setPrimaryClip(imageClip(x))
        else cm.setPrimaryClip(ClipData.newPlainText("PinClip", Store.expand(x.text)))
        toast("Copied")
    }

    private fun pasteMany() {
        val picked = filtered().filter { selected.contains(it.id) && it.image == null }
        if (picked.isEmpty()) { toast("Select some text cards first"); return }
        picked.forEach { Store.touch(it) }
        val joined = picked.joinToString(seps[sep].second) { Store.expand(it.text) }
        hideKb()
        setFocusable(false)
        h.postDelayed({ pasteText(joined) }, 150)
    }

    // ───────────────────────── auto detect ─────────────────────────

    private fun applyAuto() {
        val on = Store.prefs.getBoolean("auto", false)
        if (on && !autoOn) {
            cm.addPrimaryClipChangedListener(clipL)
            val o = object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean) {
                    h.removeCallbacks(shotRun)
                    h.postDelayed(shotRun, 900)
                }
            }
            obs = o
            try {
                contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            } catch (e: Exception) {
            }
            autoOn = true
        } else if (!on && autoOn) {
            cm.removePrimaryClipChangedListener(clipL)
            obs?.let { contentResolver.unregisterContentObserver(it) }
            obs = null
            autoOn = false
        }
    }

    private fun launchReader() {
        try {
            startActivity(
                Intent(this, ClipReadActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
        } catch (e: Exception) {
        }
    }

    /**
     * Android lets only the window that has focus read the clipboard. We add a 1px focusable
     * overlay for a moment (works without any "background pop-up" permission), read the clip,
     * and fall back to the invisible activity if that fails.
     */
    private fun readClip() {
        if (!Settings.canDrawOverlays(this)) { launchReader(); return }
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try { wm.removeView(v) } catch (e: Exception) {}
            if (!ok) launchReader()
        }
        v.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            if (has && !finished) {
                val ok = try { ClipReader.read(this) } catch (e: Exception) { false }
                finish(ok)
            }
        }
        try { wm.addView(v, lp) } catch (e: Exception) { launchReader(); return }
        h.postDelayed({ finish(false) }, 800)
    }

    private fun onClip() {
        if (SystemClock.elapsedRealtime() < Store.ignoreClipUntil) return
        h.removeCallbacks(clipRun)
        h.postDelayed(clipRun, 300)
    }

    private fun hasMediaPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun checkShot() {
        try {
            if (!hasMediaPerm()) return
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val since = System.currentTimeMillis() / 1000 - 20
            contentResolver.query(
                base, proj, MediaStore.Images.Media.DATE_ADDED + ">=?", arrayOf(since.toString()),
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val path = c.getString(2) ?: ""
                    val bucket = c.getString(3) ?: ""
                    if (!(name.contains("screenshot", true) || path.contains("screenshot", true) ||
                            bucket.contains("screenshot", true))) continue
                    val fresh = synchronized(seen) { seen.add(id) }
                    if (fresh) Store.addImage(ContentUris.withAppendedId(base, id))
                }
            }
        } catch (e: Exception) {
        }
    }
}
