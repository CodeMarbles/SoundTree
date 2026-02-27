package com.treecast.app.util

import android.content.Context
import androidx.annotation.AttrRes

public fun Context.themeColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    val color = a.getColor(0, 0)
    a.recycle()
    return color
}