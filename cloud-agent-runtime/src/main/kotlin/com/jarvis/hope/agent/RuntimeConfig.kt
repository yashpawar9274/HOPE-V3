package com.jarvis.hope.agent

import java.net.URI

data class RuntimeConfig(
    val supabaseUrl: String,
    val serviceRoleKey: String,
    val geminiApiKey: String,
    val geminiModel: String,
    val codingModel: String,
    val codingFallbackModel: String?,
    val codingTimeoutSeconds: Int,
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
            val codingModel = environment["GEMINI_CODING_MODEL"]
                ?.takeIf(String::isNotBlank) ?: DEFAULT_CODING_MODEL
            val codingFallbackModel = environment["GEMINI_CODING_FALLBACK_MODEL"]
                ?.takeIf(String::isNotBlank)
            require(isValidModel(model)) { "GEMINI_MODEL is invalid." }
            require(isValidModel(codingModel)) { "GEMINI_CODING_MODEL is invalid." }
            require(codingFallbackModel == null || isValidModel(codingFallbackModel)) {
                "GEMINI_CODING_FALLBACK_MODEL is invalid."
            }
            require(codingFallbackModel != codingModel) {
                "GEMINI_CODING_FALLBACK_MODEL must differ from GEMINI_CODING_MODEL."
            }

            return RuntimeConfig(
                supabaseUrl = supabaseUrl,
                serviceRoleKey = serviceRoleKey,
                geminiApiKey = geminiApiKey,
                geminiModel = model,
                codingModel = codingModel,
                codingFallbackModel = codingFallbackModel,
                codingTimeoutSeconds = environment.boundedInt(
                    "GEMINI_CODING_TIMEOUT_SECONDS",
                    DEFAULT_CODING_TIMEOUT_SECONDS,
                    MIN_CODING_TIMEOUT_SECONDS..MAX_CODING_TIMEOUT_SECONDS,
                ),
                port = environment.boundedInt("PORT", 8080, 1..65535),
                pollIntervalSeconds = environment.boundedLong("AGENT_POLL_INTERVAL_SECONDS", 5, 2L..60L),
                claimBatchSize = environment.boundedInt("AGENT_CLAIM_BATCH_SIZE", 3, 1..10),
            )
        }

        private fun Map<String, String>.required(key: String): String =
            get(key)?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("$key is not configured.")

        private fun isValidModel(value: String): Boolean =
            value.matches(Regex("[A-Za-z0-9._-]{1,100}"))

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

        private const val DEFAULT_CODING_MODEL = "gemini-4-carbon"
        private const val DEFAULT_CODING_TIMEOUT_SECONDS = 90
        private const val MIN_CODING_TIMEOUT_SECONDS = 15
        private const val MAX_CODING_TIMEOUT_SECONDS = 180
    }
}
