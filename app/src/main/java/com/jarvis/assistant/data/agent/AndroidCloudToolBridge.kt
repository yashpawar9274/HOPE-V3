package com.jarvis.assistant.data.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.jarvis.assistant.util.SystemSettingsLogic
import org.json.JSONObject
import java.time.Instant

data class CloudToolExecution(
    val status: ToolResultStatus,
    val result: JSONObject,
)

object CloudAgentToolPolicy {
    fun validate(tool: CloudAgentToolRequest): String? {
        if (runCatching { Instant.parse(tool.expiresAt).isAfter(Instant.now()) }.getOrDefault(false).not()) {
            return "This device request has expired."
        }
        return validateAction(
            tool.toolName,
            tool.parameters.keys().asSequence().associateWith { key -> tool.parameters.opt(key) },
            tool.permissionScope,
            tool.riskCategory,
        )
    }

    fun validateAction(
        toolName: String,
        parameters: Map<String, Any?>,
        permissionScope: String,
        riskCategory: String,
    ): String? = when (toolName) {
            "get_device_context" -> {
                if (parameters.isNotEmpty() || permissionScope != "device.context.read" ||
                    riskCategory != "low"
                ) "The device-context request is invalid." else null
            }
            "set_screen_brightness" -> {
                val percent = parameters["percent"] as? Number
                if (parameters.size != 1 || percent == null ||
                    percent.toDouble() != percent.toInt().toDouble() || percent.toInt() !in 5..100 ||
                    permissionScope != "system_settings.write" || riskCategory != "moderate"
                ) "The brightness request is invalid." else null
            }
            else -> "HOPE does not support this device action."
        }

    fun confirmationText(tool: CloudAgentToolRequest): String = when (tool.toolName) {
        "get_device_context" -> "Allow HOPE to read battery, charging, battery-saver, brightness, and network status and share those readings with the cloud task?"
        "set_screen_brightness" -> "Set screen brightness to ${tool.parameters.optInt("percent")}%? HOPE will verify the setting afterward."
        else -> "This device action is not supported."
    }
}

class AndroidCloudToolBridge(private val activity: Activity) {
    fun execute(tool: CloudAgentToolRequest): CloudToolExecution {
        val invalidReason = CloudAgentToolPolicy.validate(tool)
        if (invalidReason != null) {
            return failure(ToolResultStatus.UNSUPPORTED, invalidReason)
        }
        return when (tool.toolName) {
            "get_device_context" -> readDeviceContext()
            "set_screen_brightness" -> setBrightness(tool.parameters.getInt("percent"))
            else -> failure(ToolResultStatus.UNSUPPORTED, "HOPE does not support this device action.")
        }
    }

    private fun readDeviceContext(): CloudToolExecution {
        return try {
            val batteryManager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (batteryPercent !in 0..100) {
                return failure(ToolResultStatus.FAILED, "Android did not provide a valid battery percentage.")
            }
            val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = when (batteryStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            val brightnessRaw = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            val connectivityManager =
                activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transport = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
                else -> "unknown"
            }
            val result = JSONObject()
                .put("batteryPercent", batteryPercent)
                .put("isCharging", isCharging ?: JSONObject.NULL)
                .put("batterySaverEnabled", powerManager.isPowerSaveMode)
                .put("brightnessPercent", (brightnessRaw * 100 / 255).coerceIn(0, 100))
                .put("networkValidated", validated)
                .put("networkTransport", transport)
                .put("verified", true)
            CloudToolExecution(ToolResultStatus.SUCCESS, result)
        } catch (_: SecurityException) {
            failure(ToolResultStatus.PERMISSION_REQUIRED, "Android denied access to device status.")
        } catch (_: Exception) {
            failure(ToolResultStatus.FAILED, "Device status could not be read.")
        }
    }

    private fun setBrightness(percent: Int): CloudToolExecution {
        if (!Settings.System.canWrite(activity)) {
            return try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
                failure(
                    ToolResultStatus.PERMISSION_REQUIRED,
                    "Allow HOPE to modify system settings, then review and approve this action again.",
                )
            } catch (_: Exception) {
                failure(ToolResultStatus.PERMISSION_REQUIRED, "Android could not open the required settings page.")
            }
        }
        return try {
            val current = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            ).coerceIn(0, 255)
            val currentPercent = current * 100 / 255
            if (percent >= currentPercent) {
                return failure(
                    ToolResultStatus.UNSUPPORTED,
                    "Cloud Agent may only lower brightness through this action. Current brightness is $currentPercent%.",
                )
            }
            val target = SystemSettingsLogic.percentToBrightness(percent)
            val modeUpdated = Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val brightnessUpdated = Settings.System.putInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target,
            )
            val actual = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            val verified = modeUpdated && brightnessUpdated && actual == target
            CloudToolExecution(
                if (verified) ToolResultStatus.SUCCESS else ToolResultStatus.FAILED,
                JSONObject()
                    .put("requestedPercent", percent)
                    .put("actualPercent", actual * 100 / 255)
                    .put("verified", verified)
                    .put(
                        "message",
                        if (verified) "Brightness changed and read-back verification passed."
                        else "Android did not confirm the requested brightness.",
                    ),
            )
        } catch (_: SecurityException) {
            failure(ToolResultStatus.PERMISSION_REQUIRED, "Android denied permission to change brightness.")
        } catch (_: Exception) {
            failure(ToolResultStatus.FAILED, "Brightness could not be changed.")
        }
    }

    private fun failure(status: ToolResultStatus, message: String): CloudToolExecution =
        CloudToolExecution(
            status,
            JSONObject().put("verified", false).put("message", message.take(240)),
        )
}
