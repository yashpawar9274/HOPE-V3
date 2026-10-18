package com.jarvis.assistant.util

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.voice.HopeVoiceInteractionService

object AssistantRoleHelper {
    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
                ?: return false
            return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }

        val configuredAssistant = Settings.Secure.getString(
            context.contentResolver,
            "assistant"
        ) ?: return false
        val expected = ComponentName(context, HopeVoiceInteractionService::class.java)
        return configuredAssistant == expected.flattenToString() ||
            configuredAssistant.startsWith("${context.packageName}/")
    }

    fun requestOrOpenSettings(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }

        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
