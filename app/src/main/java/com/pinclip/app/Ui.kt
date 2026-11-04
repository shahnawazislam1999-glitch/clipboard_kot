package com.pinclip.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BG = Color.parseColor("#151922")
    val CARD = Color.parseColor("#1F2430")
    val CARD2 = Color.parseColor("#2A3040")
    val ACCENT = Color.parseColor("#6C8CFF")
    val TEXT = Color.parseColor("#ECEFF6")
    val SUB = Color.parseColor("#8E97AB")
    val AMBER = Color.parseColor("#FFB224")
    val RED = Color.parseColor("#FF5C6C")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, rDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(rDp).toFloat()
    return g
}

fun Context.iconView(res: Int, tint: Int, sizeDp: Int = 20, padDp: Int = 6): ImageView {
    val v = ImageView(this)
    v.setImageResource(res)
    v.setColorFilter(tint)
    val p = dp(padDp)
    v.setPadding(p, p, p, p)
    val s = dp(sizeDp + padDp * 2)
    v.layoutParams = LinearLayout.LayoutParams(s, s)
    return v
}

fun Context.label(t: String, sp: Float = 14f, color: Int = C.TEXT): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = sp
    v.setTextColor(color)
    return v
}

fun Context.pill(t: String, bg: Int, fg: Int = C.TEXT): TextView {
    val v = label(t, 13f, fg)
    v.setPadding(dp(12), dp(7), dp(12), dp(7))
    v.background = rounded(bg, 16)
    v.gravity = Gravity.CENTER
    return v
}

fun LP(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
