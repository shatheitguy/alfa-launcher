package com.alfa.launcher

import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * A launchable activity. [user] is the Android profile it lives in (main user, work
 * profile, or a "dual / clone app" profile). [userTag] is empty for the main user so
 * saved pins from older versions keep working.
 */
data class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
    val user: UserHandle? = null,
    val userTag: String = "",
) {
    val key: String get() = if (userTag.isEmpty()) "$pkg/$cls" else "$pkg/$cls#$userTag"
    val isClone: Boolean get() = userTag.isNotEmpty()
}
