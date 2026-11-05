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
        const val OPEN = "com.pinclip.app.OPEN"
        const val TOGGLE = "com.pinclip.app.TOGGLE"
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
    private var searchRow: LinearLayout? = null
    private var compact = false
    private var a11yAsked = false
    private var actionRow: LinearLayout? = null
    private var confirmBulk = false
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
    private val clipL = ClipboardManager.OnPrimaryClipChangedListener { onClip("listener") }
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
        PasteService.onCopyHint = { onClip("copy button") }
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
        when (i?.action) {
            OPEN -> if (panel != null) closePanel() else openPanel()
            TOGGLE -> {
                Store.prefs.edit().putBoolean("bubbleVisible", !Store.prefs.getBoolean("bubbleVisible", true)).apply()
                applyBubbleVis()
                getSystemService(NotificationManager::class.java).notify(1, notif())
            }
        }
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
        PasteService.onCopyHint = null
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun svc(code: Int, action: String): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, FloatingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notif(): Notification {
        val vis = Store.prefs.getBoolean("bubbleVisible", true)
        val openPi = svc(0, OPEN)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle("PinClip")
            .setContentText("Tap to open your clipboard")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_clip), "Open", openPi).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pin), if (vis) "Hide bubble" else "Show bubble", svc(3, TOGGLE)
                ).build()
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Stop", svc(1, STOP)).build())
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
        applyBubbleVis()
        attachDrag(v, { bubble }, { bubbleLp }, { openPanel() }, { snap() })
    }

    private fun applyBubbleVis() {
        bubble?.visibility = if (panel == null && Store.prefs.getBoolean("bubbleVisible", true)) View.VISIBLE else View.GONE
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
        buildPanel()
        applyBubbleVis()
        // catch up: if something was copied while no focus was available, save it now
        if (autoOn) h.postDelayed({ if (autoOn && panel != null) readClip() }, 350)
    }

    private fun closePanel() {
        hideKb()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null; panelLp = null; body = null; multiBar = null; chipRow = null; actionRow = null; confirmBulk = false; searchBox = null; searchRow = null
        multi = false; editorOpen = false; selected.clear(); pendingDelete = null
        applyBubbleVis()
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

    private fun ic(res: Int, tint: Int): ImageView = if (compact) iconView(res, tint, 16, 4) else iconView(res, tint, 18, 5)
    private fun tiny(t: String, bg: Int, fg: Int = C.TEXT): TextView = pill(t, bg, fg, true)
    private fun gp(): Int = dp(if (compact) 4 else 7)

    private fun setActionVis(v: Int) {
        val ar = actionRow ?: return
        ar.visibility = v
        (ar.parent as? View)?.visibility = v
    }

    private fun rebuildPanel() {
        hideKb()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null; panelLp = null; body = null; multiBar = null; chipRow = null; actionRow = null
        searchBox = null; searchRow = null; editorOpen = false
        buildPanel()
        applyBubbleVis()
    }

    private fun curGroup(): String? = if (filter == "all" || filter == "pinned" || filter == "used") null else filter

    private fun buildPanel() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val w = Store.prefs.getInt("pw", minOf(dp(340), sw - dp(16))).coerceIn(dp(150), sw)
        fullH = Store.prefs.getInt("ph", (sh * 0.62f).toInt()).coerceIn(dp(140), sh)
        shortH = maxOf(minOf(fullH, (sh * 0.42f).toInt()), dp(200))
        compact = w < dp(300)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(C.BG, 18)
        val pd = dp(if (compact) 6 else 10)
        root.setPadding(pd, pd, pd, pd)
        root.elevation = dp(8).toFloat()

        // header: [back] [pin grip] [title] [sideways-scrolling row of tool icons]
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val minB = ic(R.drawable.ic_minimize, C.TEXT)
        minB.background = rounded(C.CARD2, 14)
        minB.setOnClickListener { closePanel() }
        head.addView(minB)
        head.addView(ic(R.drawable.ic_pin, C.AMBER))
        if (!compact) {
            val title = label("PinClip", 15f)
            title.setTypeface(title.typeface, Typeface.BOLD)
            title.maxLines = 1
            head.addView(title)
        }
        val hScroll = HorizontalScrollView(this)
        hScroll.isHorizontalScrollBarEnabled = false
        hScroll.isFillViewport = true
        val icons = LinearLayout(this)
        icons.orientation = LinearLayout.HORIZONTAL
        icons.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        hScroll.addView(icons, FrameLayout.LayoutParams(WRAP, WRAP))
        head.addView(hScroll, LP(0, WRAP, 1f))

        val searchB = ic(R.drawable.ic_search, if (query.isNotEmpty()) C.ACCENT else C.SUB)
        searchB.setOnClickListener {
            val sr = searchRow
            val box = searchBox
            if (sr != null && box != null) {
                if (sr.visibility != View.VISIBLE) {
                    sr.visibility = View.VISIBLE
                    searchB.setColorFilter(C.ACCENT)
                    setFocusable(true)
                    showKb(box)
                } else {
                    box.setText("")
                    sr.visibility = View.GONE
                    searchB.setColorFilter(C.SUB)
                    hideKb()
                    setFocusable(false)
                }
            }
        }
        icons.addView(searchB)

        val readB = ic(R.drawable.ic_read, if (Store.prefs.getBoolean("reading", false)) C.ACCENT else C.SUB)
        readB.setOnClickListener {
            val on = !Store.prefs.getBoolean("reading", false)
            Store.prefs.edit().putBoolean("reading", on).apply()
            readB.setColorFilter(if (on) C.ACCENT else C.SUB)
            toast(if (on) "Reading mode: full text" else "Reading mode off")
            renderList()
        }
        icons.addView(readB)

        val multiB = ic(R.drawable.ic_multi, if (multi) C.ACCENT else C.SUB)
        multiB.setOnClickListener {
            multi = !multi
            selected.clear()
            multiB.setColorFilter(if (multi) C.ACCENT else C.SUB)
            updateMultiBar()
            renderList()
        }
        icons.addView(multiB)

        val imgB = ic(R.drawable.ic_image, C.ACCENT)
        imgB.setOnClickListener {
            try {
                val i = Intent(this, ImagePickActivity::class.java)
                i.putExtra("group", curGroup())
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {
                toast("Open the PinClip app and tap Add images")
            }
        }
        icons.addView(imgB)

        val addB = ic(R.drawable.ic_add, C.ACCENT)
        addB.setOnClickListener {
            showEditor("New text", "", curGroup(), true, false, null) { t, g ->
                Store.addText(t, g)
            }
        }
        icons.addView(addB)
        root.addView(head, LP(MATCH, WRAP))

        // search box: hidden until the search button is tapped
        val sRow = LinearLayout(this)
        sRow.orientation = LinearLayout.HORIZONTAL
        sRow.gravity = Gravity.CENTER_VERTICAL
        sRow.background = rounded(C.CARD, 10)
        sRow.setPadding(dp(8), 0, dp(2), 0)
        val et = EditText(this)
        et.hint = "Search"
        et.setHintTextColor(C.SUB)
        et.setTextColor(C.TEXT)
        et.textSize = 13f
        et.background = null
        et.setPadding(dp(2), 0, dp(2), 0)
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
        val clearB = ic(R.drawable.ic_close, C.SUB)
        clearB.setOnClickListener { et.setText("") }
        sRow.addView(clearB)
        searchBox = et
        searchRow = sRow
        sRow.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        root.addView(sRow, LP(MATCH, dp(32)).apply { topMargin = gp() })

        // chips
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        hs.addView(row)
        chipRow = row
        root.addView(hs, LP(MATCH, WRAP).apply { topMargin = gp() })

        // paste-all row (scrolls sideways in a tiny window)
        val ar = LinearLayout(this)
        ar.orientation = LinearLayout.HORIZONTAL
        ar.gravity = Gravity.CENTER_VERTICAL
        val ahs = HorizontalScrollView(this)
        ahs.isHorizontalScrollBarEnabled = false
        ahs.visibility = View.GONE
        ahs.addView(ar, FrameLayout.LayoutParams(WRAP, WRAP))
        actionRow = ar
        root.addView(ahs, LP(MATCH, WRAP).apply { topMargin = gp() })

        // body
        val b = FrameLayout(this)
        body = b
        root.addView(b, LP(MATCH, 0, 1f).apply { topMargin = gp() })

        // multi bar
        val mb = LinearLayout(this)
        mb.orientation = LinearLayout.VERTICAL
        mb.visibility = View.GONE
        multiBar = mb
        root.addView(mb, LP(MATCH, WRAP).apply { topMargin = gp() })

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
                    lp.width = (sw0 + (e.rawX - tx)).toInt().coerceIn(dp(150), maxOf(dp(150), dm.widthPixels - lp.x))
                    lp.height = (sh0 + (e.rawY - ty)).toInt().coerceIn(dp(140), maxOf(dp(140), dm.heightPixels - lp.y))
                    fullH = lp.height
                    shortH = maxOf(minOf(fullH, (dm.heightPixels * 0.42f).toInt()), dp(200))
                    try { wm.updateViewLayout(p, lp) } catch (ex: Exception) {}
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelGeometry()
                    if ((lp.width < dp(300)) != compact) rebuildPanel()
                }
            }
            true
        }
    }

    private fun renderChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        fun chip(text: String, key: String, g: Group?) {
            val sel = filter == key
            val t = tiny(text, if (sel) C.ACCENT else C.CARD, if (sel) Color.WHITE else C.TEXT)
            t.setOnClickListener { filter = key; renderChips(); renderList() }
            if (g != null) t.setOnLongClickListener { groupMenu(g); true }
            row.addView(t, LP(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        chip("All", "all", null)
        chip("Pinned", "pinned", null)
        chip("Top used", "used", null)
        Store.groups.forEach { chip(it.name, it.id, it) }
        val add = tiny("+ Group", C.CARD2, C.ACCENT)
        add.setOnClickListener {
            showEditor("New group name", "", null, false, true, null) { t, _ -> Store.addGroup(t); renderChips() }
        }
        row.addView(add)
    }

    private fun groupMenu(g: Group) {
        showEditor("Rename group", g.name, null, false, true, "Delete group" to {
            Store.deleteGroup(g.id)
            if (filter == g.id) filter = "all"
            Unit
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
        val list = filtered()
        updateActionRow(list)
        if (editorOpen) return
        b.removeAllViews()
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
        row.setPadding(dp(3), dp(4), dp(3), dp(4))
        row.background = rounded(if (pend) 0xFF4A2630.toInt() else if (sel) 0xFF2E3A66.toInt() else C.CARD, 12)

        val pin = ic(R.drawable.ic_pin, if (x.pinned) C.AMBER else C.SUB)
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
            if (x.text.isNotBlank()) {
                val cl = label(x.text, 12f)
                cl.maxLines = 2
                cl.ellipsize = TextUtils.TruncateAt.END
                cl.setPadding(0, dp(4), 0, 0)
                mid.addView(cl)
            }
        } else {
            val t = label(x.text, if (compact) 12f else 13f)
            if (!Store.prefs.getBoolean("reading", false)) {
                t.maxLines = 4
                t.ellipsize = TextUtils.TruncateAt.END
            }
            mid.addView(t)
        }
        val meta = listOfNotNull(
            Store.groups.find { g -> g.id == x.groupId }?.name,
            if (x.uses > 0) "${x.uses}×" else null,
            if (x.image != null) "image" else null
        ).joinToString(" · ")
        if (meta.isNotEmpty()) mid.addView(label(meta, 10f, C.SUB))
        row.addView(mid, LP(0, WRAP, 1f))

        val right = LinearLayout(this)
        right.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val ia = if (compact) 14 else 16
        val ip = if (compact) 4 else 5
        val ed = iconView(R.drawable.ic_edit, C.SUB, ia, ip)
        ed.setOnClickListener {
            if (x.image == null) {
                showEditor("Edit text", x.text, x.groupId, true, false, null) { t, g -> Store.update(x, t, g) }
            } else {
                showEditor("Edit image card", x.text, x.groupId, true, false, null, true, x) { t, g -> Store.update(x, t.trim(), g) }
            }
        }
        right.addView(ed)
        val cp = iconView(R.drawable.ic_copy, C.SUB, ia, ip)
        cp.setOnClickListener { copyOnly(x) }
        right.addView(cp)
        val del = iconView(if (pend) R.drawable.ic_check else R.drawable.ic_delete, if (pend) C.RED else C.SUB, ia, ip)
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
        if (compact) {
            right.gravity = Gravity.END
            mid.addView(right, LP(MATCH, WRAP))
        } else {
            row.addView(right)
        }

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

    private fun updateActionRow(list: List<Item>) {
        val ar = actionRow ?: return
        ar.removeAllViews()
        val n = list.count { it.image == null }
        if (editorOpen || multi || n == 0) { setActionVis(View.GONE); return }
        setActionVis(View.VISIBLE)
        val go = tiny("Paste all ($n)", C.ACCENT, Color.WHITE)
        go.setOnClickListener { pasteTexts(list) }
        ar.addView(go, LP(WRAP, WRAP).apply { rightMargin = dp(4) })
        val sp = tiny("Join: " + seps[sep].first, C.CARD2)
        sp.setOnClickListener { sep = (sep + 1) % seps.size; updateActionRow(filtered()) }
        ar.addView(sp)
    }

    private fun updateMultiBar() {
        val bar = multiBar ?: return
        bar.removeAllViews()
        bar.visibility = if (multi) View.VISIBLE else View.GONE
        if (!multi) return
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        hs.addView(r, FrameLayout.LayoutParams(WRAP, WRAP))
        fun add(v: View) { r.addView(v, LP(WRAP, WRAP).apply { rightMargin = dp(4) }) }

        add(label("${selected.size}✓", 12f, C.SUB))

        val all = tiny("All", C.CARD2)
        all.setOnClickListener {
            val ids = filtered().map { it.id }
            if (selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            updateMultiBar()
            renderList()
        }
        add(all)

        val go = tiny("Paste", C.ACCENT, Color.WHITE)
        go.setOnClickListener {
            if (selected.isEmpty()) toast("Select cards first")
            else pasteTexts(filtered().filter { selected.contains(it.id) })
        }
        add(go)

        val mv = tiny("Move", C.CARD2, C.ACCENT)
        mv.setOnClickListener {
            if (selected.isEmpty()) {
                toast("Select cards first")
            } else {
                showPicker("Move ${selected.size} cards to…") { g ->
                    Store.moveMany(selected.toList(), g)
                    multi = false
                    selected.clear()
                    updateMultiBar()
                    renderList()
                }
            }
        }
        add(mv)

        val del = tiny(if (confirmBulk) "Tap again" else "Delete", C.CARD2, C.RED)
        del.setOnClickListener {
            if (selected.isEmpty()) {
                toast("Select cards first")
            } else if (confirmBulk) {
                Store.deleteMany(selected.toList())
                selected.clear()
                confirmBulk = false
                multi = false
                updateMultiBar()
                renderList()
            } else {
                confirmBulk = true
                updateMultiBar()
                h.postDelayed({ if (confirmBulk) { confirmBulk = false; updateMultiBar() } }, 3000)
            }
        }
        add(del)

        val sp = tiny(seps[sep].first, C.CARD2)
        sp.setOnClickListener { sep = (sep + 1) % seps.size; updateMultiBar() }
        add(sp)

        val done = tiny("Done", C.CARD2)
        done.setOnClickListener { multi = false; selected.clear(); confirmBulk = false; updateMultiBar(); renderList() }
        add(done)

        bar.addView(hs, LP(MATCH, WRAP))
    }

    private fun showPicker(title: String, onPick: (String?) -> Unit) {
        val b = body ?: return
        editorOpen = true
        setActionVis(View.GONE)
        b.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 13f, C.SUB))
        val sv = ScrollView(this)
        val opts = LinearLayout(this)
        opts.orientation = LinearLayout.VERTICAL
        fun opt(text: String, gid: String?) {
            val p = tiny(text, C.CARD, C.TEXT)
            p.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            p.setOnClickListener { editorOpen = false; renderList(); onPick(gid) }
            opts.addView(p, LP(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        opt("No group", null)
        Store.groups.forEach { opt(it.name, it.id) }
        if (Store.groups.isEmpty()) opts.addView(label("Create groups with “+ Group” first.", 12f, C.SUB))
        sv.addView(opts)
        col.addView(sv, LP(MATCH, 0, 1f))
        val cancel = tiny("Cancel", C.CARD2)
        cancel.setOnClickListener { editorOpen = false; renderList() }
        col.addView(cancel, LP(WRAP, WRAP).apply { topMargin = dp(8) })
        b.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ───────────────────────── editor ─────────────────────────

    private fun showEditor(
        title: String, initial: String, group: String?, withGroup: Boolean, single: Boolean,
        danger: Pair<String, () -> Unit>?, allowBlank: Boolean = false, preview: Item? = null,
        onSave: (String, String?) -> Unit
    ) {
        val b = body ?: return
        editorOpen = true
        setActionVis(View.GONE)
        setFocusable(true)
        b.removeAllViews()
        var gid = group
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 13f, C.SUB))
        if (preview != null) {
            val pv = ImageView(this)
            pv.adjustViewBounds = true
            pv.scaleType = ImageView.ScaleType.CENTER_CROP
            pv.maxHeight = dp(80)
            val tf = Store.thumb(preview)
            if (tf.exists()) pv.setImageBitmap(BitmapFactory.decodeFile(tf.path))
            col.addView(pv, LP(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        val et = EditText(this)
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.gravity = Gravity.CENTER_VERTICAL
        val cancel = tiny("Cancel", C.CARD2)
        cancel.setOnClickListener { closeEditor() }
        val save = tiny("Save", C.ACCENT, Color.WHITE)
        save.setOnClickListener {
            val v = et.text.toString()
            if (allowBlank || v.isNotBlank()) {
                closeEditor()
                onSave(v, gid)
            }
        }
        btns.addView(cancel, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        btns.addView(save, LP(WRAP, WRAP).apply { rightMargin = dp(8) })
        if (danger != null) {
            val d = tiny(danger.first, C.CARD2, C.RED)
            d.setOnClickListener { closeEditor(); danger.second() ; renderChips() }
            btns.addView(d)
        }
        col.addView(btns, LP(MATCH, WRAP).apply { topMargin = dp(6) })

        if (withGroup) {
            fun gl() = "Group: " + (Store.groups.find { g -> g.id == gid }?.name ?: "None")
            val gb = tiny(gl(), C.CARD2, C.ACCENT)
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
        et.hint = if (single) "Name" else if (preview != null) "Caption (optional)" else "Type or paste text… ({date} and {time} work too)"
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
        Store.lastClip = t
        cm.setPrimaryClip(ClipData.newPlainText("PinClip", t))
        PasteService.ensure(this)
        val ok = PasteService.paste(t)
        if (ok) {
            toast("Pasted")
        } else if (PasteService.running()) {
            toast("Copied – tap inside a text field first")
        } else {
            toast("Copied – now switch ON PinClip in Accessibility")
            // open the Accessibility screen once per run so it is one tap away
            if (!a11yAsked) {
                a11yAsked = true
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                }
            }
        }
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
        else {
            Store.lastClip = Store.expand(x.text)
            cm.setPrimaryClip(ClipData.newPlainText("PinClip", Store.lastClip))
        }
        toast("Copied")
    }

    /** Pastes many text cards as one block, oldest first. */
    private fun pasteTexts(src: List<Item>) {
        val picked = src.filter { it.image == null }.sortedBy { it.created }
        if (picked.isEmpty()) { toast("No text cards to paste"); return }
        Store.touchMany(picked)
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
        val i = Intent(this, ClipReadActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            // the accessibility service is allowed to start screens from the background on most phones
            val c: android.content.Context = PasteService.instance() ?: this
            c.startActivity(i)
        } catch (e: Exception) {
            try { startActivity(i) } catch (e2: Exception) { Store.log("could not open reader screen") }
        }
    }

    /**
     * Android lets only the window that has focus read the clipboard. We add a 1px focusable
     * overlay for a moment, read the clip, and fall back to an invisible activity if that fails.
     */
    private fun readClip() {
        if (!Settings.canDrawOverlays(this)) { launchReader(); return }
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                Store.log(if (ok) "overlay read ok" else "overlay read blocked")
                finish(ok)
            }
        }
        try { wm.addView(v, lp) } catch (e: Exception) { launchReader(); return }
        h.postDelayed({
            if (!finished) Store.log("overlay never got focus")
            finish(false)
        }, 900)
    }

    private fun onClip(src: String) {
        if (!autoOn) return
        if (SystemClock.elapsedRealtime() < Store.ignoreClipUntil) return
        Store.log("copy signal: $src")
        h.removeCallbacks(clipRun)
        h.postDelayed(clipRun, 350)
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
