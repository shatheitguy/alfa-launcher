package com.alfa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Runtime permissions that keep working after a denial. Android stops showing the prompt once
 * the user has said no (twice on Android 11+); asking again then fails silently, which left the
 * Assistant stuck at "I need access to your contacts". In that case ALFA explains and opens its
 * own permission page instead.
 */
object Perms {

    fun granted(c: Context, perm: String) = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun request(a: Activity, perm: String, requestCode: Int, what: String) {
        if (granted(a, perm)) return
        val prefs = a.getSharedPreferences("alfa", Context.MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked_$perm", false)
        if (askedBefore && !a.shouldShowRequestPermissionRationale(perm)) {
            AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Allow $what")
                .setMessage("Android won't show the permission prompt for ALFA again. " +
                    "Open ALFA's app settings, tap Permissions, and allow $what.")
                .setPositiveButton("Open settings") { _, _ -> openAppSettings(a) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        prefs.edit().putBoolean("asked_$perm", true).apply()
        a.requestPermissions(arrayOf(perm), requestCode)
    }

    fun openAppSettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
