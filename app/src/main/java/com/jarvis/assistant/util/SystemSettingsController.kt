package com.jarvis.assistant.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Opens Android settings screens and changes the few settings an app may change directly.
 * Android does not let apps flip Wi-Fi, Bluetooth, airplane mode or mobile data switches themselves,
 * so for those HOPE opens the right screen or quick panel. Brightness, screen timeout and auto-rotate
 * are changed directly once "Modify system settings" is allowed.
 */
object SystemSettingsController {
    private const val TAG = "SystemSettings"

    class Result(val ok: Boolean, val message: String)

    private fun intentFor(page: SystemSettingsLogic.Page): Intent = when (page) {
        SystemSettingsLogic.Page.WIFI ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
        SystemSettingsLogic.Page.INTERNET ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            else Intent(Settings.ACTION_WIRELESS_SETTINGS)
        SystemSettingsLogic.Page.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SystemSettingsLogic.Page.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        SystemSettingsLogic.Page.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        SystemSettingsLogic.Page.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SystemSettingsLogic.Page.BATTERY -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        SystemSettingsLogic.Page.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SystemSettingsLogic.Page.NFC -> Intent(Settings.ACTION_NFC_SETTINGS)
        SystemSettingsLogic.Page.APPS -> Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        SystemSettingsLogic.Page.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SystemSettingsLogic.Page.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SystemSettingsLogic.Page.DEFAULT_APPS -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        SystemSettingsLogic.Page.DATE_TIME -> Intent(Settings.ACTION_DATE_SETTINGS)
        SystemSettingsLogic.Page.LANGUAGE -> Intent(Settings.ACTION_LOCALE_SETTINGS)
        SystemSettingsLogic.Page.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        SystemSettingsLogic.Page.PRIVACY -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
        SystemSettingsLogic.Page.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        SystemSettingsLogic.Page.MOBILE_NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        SystemSettingsLogic.Page.DEVELOPER -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        SystemSettingsLogic.Page.ALL -> Intent(Settings.ACTION_SETTINGS)
    }

    fun open(context: Context, name: String): Result {
        val page = SystemSettingsLogic.pageFor(name)
            ?: return Result(
                false,
                "I do not know a settings screen called \"$name\". I can open: ${SystemSettingsLogic.knownPages.joinToString(", ")}."
            )
        return try {
            context.startActivity(intentFor(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result(
                true,
                "Opened the ${page.name.lowercase().replace('_', ' ')} screen. Android lets the owner flip the switch " +
                    "there; I cannot press system switches such as Wi-Fi or Bluetooth by myself."
            )
        } catch (error: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Result(true, "That exact screen is not on this phone, so I opened the main Settings app.")
            } catch (second: Exception) {
                Log.w(TAG, "Could not open settings.", second)
                Result(false, "I could not open the Settings app on this phone.")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not open settings page $page.", error)
            Result(false, "I could not open that settings screen: ${error.message ?: "unknown error"}.")
        }
    }

    fun set(context: Context, setting: String, value: String): Result {
        val which = SystemSettingsLogic.settingFor(setting)
            ?: return Result(
                false,
                "I can change brightness_percent, screen_timeout_seconds and auto_rotate directly. " +
                    "For anything else I can open its settings screen."
            )
        if (!Settings.System.canWrite(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (error: Exception) {
                Log.w(TAG, "Could not open the write-settings screen.", error)
            }
            return Result(
                false,
                "The \"Modify system settings\" permission is off. I opened its screen; the owner must switch HOPE on there, then ask again."
            )
        }
        val resolver = context.contentResolver
        return try {
            when (which) {
                SystemSettingsLogic.Setting.BRIGHTNESS_PERCENT -> {
                    val percent = SystemSettingsLogic.parseBrightnessPercent(value)
                        ?: return Result(false, "Tell me the brightness as a percentage from 1 to 100.")
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, SystemSettingsLogic.percentToBrightness(percent))
                    Result(true, "Brightness set to $percent percent.")
                }
                SystemSettingsLogic.Setting.SCREEN_TIMEOUT_SECONDS -> {
                    val seconds = SystemSettingsLogic.parseTimeoutSeconds(value)
                        ?: return Result(false, "Screen timeout must be between 5 seconds and 30 minutes.")
                    Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
                    Result(true, "Screen timeout set to $seconds seconds.")
                }
                SystemSettingsLogic.Setting.AUTO_ROTATE -> {
                    val on = SystemSettingsLogic.parseOnOff(value)
                        ?: return Result(false, "Say on or off for auto-rotate.")
                    Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                    Result(true, "Auto-rotate is now ${if (on) "on" else "off"}.")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not change $setting.", error)
            Result(false, "Android refused that change: ${error.message ?: "unknown error"}.")
        }
    }
}
