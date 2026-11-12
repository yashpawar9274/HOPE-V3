package com.jarvis.hope.agent

import java.net.URI

data class RuntimeConfig(
    val supabaseUrl: String,
    val serviceRoleKey: String,
    val geminiApiKey: String,
    val geminiModel: String,
    val port: Int,
    val pollIntervalSeconds: Long,
    val claimBatchSize: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RuntimeConfig {
            val supabaseUrl = environment.required("SUPABASE_URL").trimEnd('/')
            val supabaseUri = runCatching { URI.create(supabaseUrl) }.getOrNull()
                ?: throw IllegalStateException("SUPABASE_URL is invalid.")
            require(
                supabaseUri.scheme == "https" && !supabaseUri.host.isNullOrBlank() &&
                    supabaseUri.userInfo == null && supabaseUri.rawPath.isNullOrEmpty() &&
                    supabaseUri.rawQuery == null && supabaseUri.rawFragment == null,
            ) { "SUPABASE_URL must be an HTTPS project origin." }
            val serviceRoleKey = environment.required("SUPABASE_SERVICE_ROLE_KEY")
            val geminiApiKey = environment.required("GEMINI_API_KEY")
            val model = environment["GEMINI_MODEL"]?.takeIf(String::isNotBlank) ?: "gemini-2.0-flash"
            require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "GEMINI_MODEL is invalid." }

            return RuntimeConfig(
                supabaseUrl = supabaseUrl,
                serviceRoleKey = serviceRoleKey,
                geminiApiKey = geminiApiKey,
                geminiModel = model,
                port = environment.boundedInt("PORT", 8080, 1..65535),
                pollIntervalSeconds = environment.boundedLong("AGENT_POLL_INTERVAL_SECONDS", 5, 2L..60L),
                claimBatchSize = environment.boundedInt("AGENT_CLAIM_BATCH_SIZE", 3, 1..10),
            )
        }

        private fun Map<String, String>.required(key: String): String =
            get(key)?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("$key is not configured.")

        private fun Map<String, String>.boundedInt(key: String, default: Int, range: IntRange): Int {
            val raw = get(key) ?: return default
            val parsed = raw.toIntOrNull() ?: throw IllegalStateException("$key must be an integer.")
            require(parsed in range) { "$key is outside its supported range." }
            return parsed
        }

        private fun Map<String, String>.boundedLong(key: String, default: Long, range: LongRange): Long {
            val raw = get(key) ?: return default
            val parsed = raw.toLongOrNull() ?: throw IllegalStateException("$key must be an integer.")
            require(parsed in range) { "$key is outside its supported range." }
            return parsed
        }
    }
}
