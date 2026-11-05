package com.pinclip.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Invisible activity that opens the system picker and saves the chosen images as image cards. */
class ImagePickActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, 1) } catch (e: Exception) { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val cd = data.clipData
            if (cd != null) {
                for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri)
            } else {
                val u = data.data
                if (u != null) uris.add(u)
            }
        }
        if (uris.isEmpty()) { finish(); return }
        val group = intent.getStringExtra("group")
        Thread {
            for (u in uris) Store.addImage(u, group)
            runOnUiThread { finish() }
        }.start()
    }
}
