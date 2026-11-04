package com.pinclip.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Group(val id: String, var name: String)

data class Item(
    val id: String,
    var text: String,
    val image: String?,
    var groupId: String?,
    var pinned: Boolean,
    var uses: Int,
    var created: Long
)

/** All data lives here. Every list mutation happens on the main thread. */
object Store {
    lateinit var app: Context
    lateinit var prefs: SharedPreferences
    val items = ArrayList<Item>()
    val groups = ArrayList<Group>()
    @Volatile var ignoreClipUntil = 0L
    var onChange: (() -> Unit)? = null
    private var inited = false
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("pinclip", Context.MODE_PRIVATE)
        load()
        inited = true
    }

    private fun notifyChange() { onChange?.invoke() }

    fun imageFile(i: Item) = File(app.filesDir, "img/" + i.image)
    fun thumb(i: Item) = File(app.filesDir, "img/" + i.id + ".jpg")

    /** {date} and {time} placeholders are expanded when a card is pasted. */
    fun expand(t: String): String {
        val now = Date()
        return t.replace("{date}", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
            .replace("{time}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
    }

    private fun load() {
        val s = prefs.getString("data", null) ?: return
        try {
            val o = JSONObject(s)
            val ga = o.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val g = ga.getJSONObject(i)
                groups.add(Group(g.getString("id"), g.getString("name")))
            }
            val ia = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until ia.length()) {
                val x = ia.getJSONObject(i)
                items.add(
                    Item(
                        x.getString("id"),
                        x.optString("text", ""),
                        x.optString("image", "").ifEmpty { null },
                        x.optString("group", "").ifEmpty { null },
                        x.optBoolean("pinned", false),
                        x.optInt("uses", 0),
                        x.optLong("created", 0L)
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun save() {
        val ga = JSONArray()
        groups.forEach { ga.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val ia = JSONArray()
        items.forEach {
            ia.put(
                JSONObject().put("id", it.id).put("text", it.text).put("image", it.image ?: "")
                    .put("group", it.groupId ?: "").put("pinned", it.pinned)
                    .put("uses", it.uses).put("created", it.created)
            )
        }
        val o = JSONObject().put("groups", ga).put("items", ia)
        prefs.edit().putString("data", o.toString()).apply()
    }

    fun addText(text: String, group: String? = null): Boolean {
        if (text.isBlank()) return false
        val ex = items.firstOrNull { it.image == null && it.text == text }
        if (ex != null) {
            ex.created = System.currentTimeMillis()
            save(); notifyChange()
            return false
        }
        items.add(Item(UUID.randomUUID().toString(), text, null, group, false, 0, System.currentTimeMillis()))
        save(); notifyChange()
        return true
    }

    /** Copies the image into private storage (safe to call from any thread). */
    fun addImage(uri: Uri, group: String? = null) {
        try {
            val id = UUID.randomUUID().toString()
            val dir = File(app.filesDir, "img")
            dir.mkdirs()
            val name = "$id.png"
            val f = File(dir, name)
            val ins = app.contentResolver.openInputStream(uri) ?: return
            ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bo)
            if (bo.outWidth <= 0) { f.delete(); return }
            var s = 1
            while (bo.outWidth / s > 640) s *= 2
            val so = BitmapFactory.Options()
            so.inSampleSize = s
            val t = BitmapFactory.decodeFile(f.path, so)
            if (t != null) File(dir, "$id.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            main.post {
                items.add(Item(id, "", name, group, false, 0, System.currentTimeMillis()))
                save(); notifyChange()
            }
        } catch (e: Exception) {
        }
    }

    fun update(i: Item, text: String, group: String?) { i.text = text; i.groupId = group; save(); notifyChange() }
    fun togglePin(i: Item) { i.pinned = !i.pinned; save(); notifyChange() }
    fun touch(i: Item) { i.uses++; save(); notifyChange() }
    fun setGroup(i: Item, g: String?) { i.groupId = g; save(); notifyChange() }

    fun delete(i: Item) {
        items.remove(i)
        if (i.image != null) { imageFile(i).delete(); thumb(i).delete() }
        save(); notifyChange()
    }

    fun clearUnpinned() {
        items.filter { !it.pinned }.forEach {
            if (it.image != null) { imageFile(it).delete(); thumb(it).delete() }
        }
        items.removeAll { !it.pinned }
        save(); notifyChange()
    }

    fun addGroup(name: String) {
        if (name.isBlank()) return
        groups.add(Group(UUID.randomUUID().toString(), name.trim()))
        save(); notifyChange()
    }

    fun renameGroup(g: Group, name: String) { if (name.isNotBlank()) { g.name = name.trim(); save(); notifyChange() } }

    fun deleteGroup(id: String) {
        groups.removeAll { it.id == id }
        items.forEach { if (it.groupId == id) it.groupId = null }
        save(); notifyChange()
    }

    fun exportText(): String =
        items.filter { it.image == null }.joinToString("\n\n-----\n\n") { it.text }
}
