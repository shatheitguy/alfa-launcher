package com.alfa.launcher

import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
) {
    val key: String get() = "$pkg/$cls"
}
