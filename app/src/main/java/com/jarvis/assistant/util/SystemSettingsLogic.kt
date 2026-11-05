package com.jarvis.assistant.util

import java.util.Locale

/** Pure parsing rules for HOPE's "open a settings screen / change a system setting" tools. */
object SystemSettingsLogic {
    enum class Page {
        WIFI, INTERNET, BLUETOOTH, AIRPLANE, DISPLAY, SOUND, BATTERY, LOCATION, NFC, APPS,
        NOTIFICATION_ACCESS, ACCESSIBILITY, DEFAULT_APPS, DATE_TIME, LANGUAGE, STORAGE,
        PRIVACY, SECURITY, MOBILE_NETWORK, DEVELOPER, ALL
    }

    private val pageAliases: Map<String, Page> = buildMap {
        fun add(page: Page, vararg names: String) = names.forEach { put(it, page) }
        add(Page.WIFI, "wifi", "wlan")
        add(Page.INTERNET, "internet", "network", "connectivity")
        add(Page.BLUETOOTH, "bluetooth", "bt")
        add(Page.AIRPLANE, "airplane", "airplanemode", "flightmode", "flight")
        add(Page.DISPLAY, "display", "brightness", "screen", "screentimeout", "darkmode", "theme")
        add(Page.SOUND, "sound", "volume", "audio", "ringtone", "silent", "vibration")
        add(Page.BATTERY, "battery", "batterysaver", "power", "powersaving")
        add(Page.LOCATION, "location", "gps")
        add(Page.NFC, "nfc")
        add(Page.APPS, "apps", "applications", "appmanager")
        add(Page.NOTIFICATION_ACCESS, "notifications", "notificationaccess", "notification")
        add(Page.ACCESSIBILITY, "accessibility")
        add(Page.DEFAULT_APPS, "defaultapps", "defaultapp", "defaults")
        add(Page.DATE_TIME, "date", "time", "datetime", "datetimesettings")
        add(Page.LANGUAGE, "language", "languages", "locale", "keyboard")
        add(Page.STORAGE, "storage", "memory")
        add(Page.PRIVACY, "privacy")
        add(Page.SECURITY, "security", "lockscreen", "screenlock", "fingerprint")
        add(Page.MOBILE_NETWORK, "mobiledata", "mobilenetwork", "hotspot", "tethering", "sim", "wireless")
        add(Page.DEVELOPER, "developer", "developeroptions")
        add(Page.ALL, "all", "settings", "main", "home")
    }

    val knownPages: List<String> = listOf(
        "wifi", "internet", "bluetooth", "airplane", "display", "sound", "battery", "location", "nfc", "apps",
        "notification access", "accessibility", "default apps", "date and time", "language", "storage",
        "privacy", "security", "mobile network", "developer options", "all settings"
    )

    fun normalizeKey(raw: String): String = raw.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun pageFor(raw: String): Page? = pageAliases[normalizeKey(raw)]

    /** Settings HOPE can change directly (needs "Modify system settings"). */
    enum class Setting { BRIGHTNESS_PERCENT, SCREEN_TIMEOUT_SECONDS, AUTO_ROTATE }

    private val settingAliases = mapOf(
        "brightness" to Setting.BRIGHTNESS_PERCENT,
        "brightnesspercent" to Setting.BRIGHTNESS_PERCENT,
        "screenbrightness" to Setting.BRIGHTNESS_PERCENT,
        "screentimeout" to Setting.SCREEN_TIMEOUT_SECONDS,
        "screentimeoutseconds" to Setting.SCREEN_TIMEOUT_SECONDS,
        "sleep" to Setting.SCREEN_TIMEOUT_SECONDS,
        "timeout" to Setting.SCREEN_TIMEOUT_SECONDS,
        "autorotate" to Setting.AUTO_ROTATE,
        "rotation" to Setting.AUTO_ROTATE,
        "screenrotation" to Setting.AUTO_ROTATE
    )

    fun settingFor(raw: String): Setting? = settingAliases[normalizeKey(raw)]

    /** "70", "70%", "70 percent", "max"/"full" -> 100, "min"/"lowest" -> 1. Result is 1..100. */
    fun parseBrightnessPercent(value: String): Int? {
        val text = value.trim().lowercase(Locale.ROOT)
        if (text in setOf("max", "maximum", "full", "highest")) return 100
        if (text in setOf("min", "minimum", "lowest", "low")) return 1
        val number = Regex("""\d{1,3}""").find(text)?.value?.toIntOrNull() ?: return null
        return if (number in 0..100) number.coerceAtLeast(1) else null
    }

    fun percentToBrightness(percent: Int): Int = (percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)

    /** "30", "30s", "30 seconds", "2 min", "1 minute", "1.5 minutes". Result in 5..1800 seconds. */
    fun parseTimeoutSeconds(value: String): Int? {
        val text = value.trim().lowercase(Locale.ROOT)
        val match = Regex("""(\d+(?:\.\d+)?)\s*([a-z]*)""").find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        val seconds = if (unit.startsWith("m")) amount * 60.0 else amount
        val rounded = seconds.toInt()
        return if (rounded in 5..1800) rounded else null
    }

    fun parseOnOff(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "on", "true", "1", "yes", "enable", "enabled", "chalu", "haan" -> true
        "off", "false", "0", "no", "disable", "disabled", "band", "nahi" -> false
        else -> null
    }
}
