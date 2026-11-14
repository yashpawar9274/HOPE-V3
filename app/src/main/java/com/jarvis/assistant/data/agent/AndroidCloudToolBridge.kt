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
import java.time.temporal.ChronoUnit

data class CloudToolExecution(
    val status: ToolResultStatus,
    val result: JSONObject,
)

data class CloudAgentToolContract(
    val name: String,
    val permissionScope: String,
    val riskCategory: String,
    val timeoutMillis: Long,
    val confirmationRequired: Boolean,
    val parameterSchema: String,
    val resultSchema: String,
)

object CloudAgentToolPolicy {
    val contracts = listOf(
        CloudAgentToolContract(
            name = "get_device_context",
            permissionScope = "device.context.read",
            riskCategory = "low",
            timeoutMillis = 5_000,
            confirmationRequired = true,
            parameterSchema = "{}",
            resultSchema = "batteryPercent,isCharging,batterySaverEnabled,brightnessPercent,networkValidated,networkTransport,verified",
        ),
        CloudAgentToolContract(
            name = "set_screen_brightness",
            permissionScope = "system_settings.write",
            riskCategory = "moderate",
            timeoutMillis = 5_000,
            confirmationRequired = true,
            parameterSchema = """{"percent":"integer 5..100"}""",
            resultSchema = "requestedPercent,actualPercent,verified,message",
        ),
    )

    fun validate(tool: CloudAgentToolRequest): String? {
        return validate(
            tool = tool,
            expectedTaskId = tool.taskId,
            expectedSessionId = tool.sessionId,
        )
    }

    fun validate(
        tool: CloudAgentToolRequest,
        expectedTaskId: String,
        expectedSessionId: String,
    ): String? = validateEnvelope(
        taskId = tool.taskId,
        sessionId = tool.sessionId,
        toolCallId = tool.toolCallId,
        toolName = tool.toolName,
        parameters = tool.parameters.keys().asSequence().associateWith { key -> tool.parameters.opt(key) },
        permissionScope = tool.permissionScope,
        riskCategory = tool.riskCategory,
        expiresAt = tool.expiresAt,
        expectedTaskId = expectedTaskId,
        expectedSessionId = expectedSessionId,
    )

    fun validateEnvelope(
        taskId: String,
        sessionId: String,
        toolCallId: String,
        toolName: String,
        parameters: Map<String, Any?>,
        permissionScope: String,
        riskCategory: String,
        expiresAt: String,
        expectedTaskId: String,
        expectedSessionId: String,
    ): String? {
        if (!isUuid(taskId) || !isUuid(sessionId) || !isUuid(toolCallId) ||
            taskId != expectedTaskId || sessionId != expectedSessionId
        ) {
            return "The device request is not bound to this task and session."
        }
        val expiry = runCatching { Instant.parse(expiresAt) }.getOrNull()
        val now = Instant.now()
        if (expiry == null || !expiry.isAfter(now) ||
            expiry.isAfter(now.plus(MAX_REQUEST_LIFETIME_MINUTES, ChronoUnit.MINUTES))
        ) {
            return "This device request has expired."
        }
        return validateAction(
            toolName,
            parameters,
            permissionScope,
            riskCategory,
        )
    }

    fun matchesAuthorizedRequest(expected: CloudAgentToolRequest, current: CloudAgentToolRequest): Boolean =
        matchesAuthorizedEnvelope(
            expected.taskId,
            expected.sessionId,
            expected.toolCallId,
            expected.toolName,
            expected.parameters.keys().asSequence().associateWith { expected.parameters.opt(it) },
            expected.permissionScope,
            expected.riskCategory,
            expected.expiresAt,
            current.taskId,
            current.sessionId,
            current.toolCallId,
            current.toolName,
            current.parameters.keys().asSequence().associateWith { current.parameters.opt(it) },
            current.permissionScope,
            current.riskCategory,
            current.expiresAt,
        )

    fun matchesAuthorizedEnvelope(
        expectedTaskId: String,
        expectedSessionId: String,
        expectedCallId: String,
        expectedToolName: String,
        expectedParameters: Map<String, Any?>,
        expectedPermissionScope: String,
        expectedRiskCategory: String,
        expectedExpiry: String,
        currentTaskId: String,
        currentSessionId: String,
        currentCallId: String,
        currentToolName: String,
        currentParameters: Map<String, Any?>,
        currentPermissionScope: String,
        currentRiskCategory: String,
        currentExpiry: String,
    ): Boolean =
        expectedTaskId == currentTaskId &&
            expectedSessionId == currentSessionId &&
            expectedCallId == currentCallId &&
            expectedToolName == currentToolName &&
            expectedParameters == currentParameters &&
            expectedPermissionScope == currentPermissionScope &&
            expectedRiskCategory == currentRiskCategory &&
            expectedExpiry == currentExpiry

    fun validateAction(
        toolName: String,
        parameters: Map<String, Any?>,
        permissionScope: String,
        riskCategory: String,
    ): String? {
        val contract = contracts.firstOrNull { it.name == toolName }
            ?: return "HOPE does not support this device action."
        if (contract.permissionScope != permissionScope || contract.riskCategory != riskCategory) {
            return "The device request has an invalid permission scope or risk category."
        }
        return when (toolName) {
            "get_device_context" -> {
                if (parameters.isNotEmpty()) "The device-context request is invalid." else null
            }
            "set_screen_brightness" -> {
                val percent = parameters["percent"]
                val isInteger = percent is Int || percent is Long || percent is Short || percent is Byte
                val value = (percent as? Number)?.toLong()
                if (parameters.size != 1 || !isInteger || value == null || value !in 5L..100L) {
                    "The brightness request is invalid."
                } else {
                    null
                }
            }
            else -> "HOPE does not support this device action."
        }
    }

    fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    fun confirmationText(tool: CloudAgentToolRequest): String = when (tool.toolName) {
        "get_device_context" -> "Allow HOPE to read battery, charging, battery-saver, brightness, and network status and share those readings with the cloud task?"
        "set_screen_brightness" -> "Set screen brightness to ${tool.parameters.optInt("percent")}%? HOPE will verify the setting afterward."
        else -> "This device action is not supported."
    }

    private const val MAX_REQUEST_LIFETIME_MINUTES = 10L
    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
}

class AndroidCloudToolBridge(private val activity: Activity) {
    fun execute(
        tool: CloudAgentToolRequest,
        expectedTaskId: String,
        expectedSessionId: String,
        userApproved: Boolean,
    ): CloudToolExecution {
        val invalidReason = CloudAgentToolPolicy.validate(tool, expectedTaskId, expectedSessionId)
        if (invalidReason != null) {
            return failure(ToolResultStatus.UNSUPPORTED, invalidReason)
        }
        if (!userApproved) {
            return failure(
                ToolResultStatus.USER_CONFIRMATION_REQUIRED,
                "The user must approve this device action before execution.",
            )
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
