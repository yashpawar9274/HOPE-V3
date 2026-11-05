package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Status checks and Settings shortcuts for the extra permissions on the Permissions screen:
 * personal files, location all the time, secure settings (adb) and the SMS / call-info group.
 * Android never lets an app grant itself these: the owner switches them on in the Android screens
 * these helpers open.
 */
object PermissionExtras {
    const val ADB_COMMAND = "adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS"

    /** Runtime permissions for photos, videos and audio (Android 13+) or storage read (older). */
    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun missingMediaPermissions(context: Context): List<String> =
        mediaPermissions().filter { !granted(context, it) }

    fun hasMediaAccess(context: Context): Boolean = missingMediaPermissions(context).isEmpty()

    /** "All files access" (Android 11+). Older versions use the storage read permission only. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun hasFilesAccess(context: Context): Boolean = hasMediaAccess(context) && hasAllFilesAccess()

    fun allFilesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    /** Fallback for phones that have no per-app "all files" screen. */
    fun allFilesFallbackIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        else Intent(Settings.ACTION_SETTINGS)

    fun hasForegroundLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** "Allow all the time" location. Before Android 10 foreground location already works in the background. */
    fun hasBackgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasForegroundLocation(context)
        }

    /** SMS (Meeting Mode texts), call log and phone state (call detection). */
    fun callAndSmsPermissions(): Array<String> = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE
    )

    fun missingCallAndSms(context: Context): List<String> = callAndSmsPermissions().filter { !granted(context, it) }

    /** True only after the owner ran the adb command (see [ADB_COMMAND]). */
    fun hasWriteSecureSettings(context: Context): Boolean =
        granted(context, Manifest.permission.WRITE_SECURE_SETTINGS)

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
