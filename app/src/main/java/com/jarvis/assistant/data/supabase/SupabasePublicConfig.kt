package com.jarvis.assistant.data.supabase

import com.jarvis.assistant.BuildConfig

data class SupabasePublicConfig(
    val projectUrl: String,
    val publishableKey: String
) {
    val isConfigured: Boolean
        get() {
            val uri = runCatching { java.net.URI(projectUrl) }.getOrNull() ?: return false
            return uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                publishableKey.isNotBlank()
        }

    fun requireConfigured() {
        check(isConfigured) {
            "Supabase is not configured. Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in the build environment."
        }
    }
}

object SupabaseConfiguration {
    val publicClient = SupabasePublicConfig(
        projectUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    )
}
