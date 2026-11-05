package com.jarvis.assistant.license

/** Settings for the HOPE AI licence server. See hope_license_api.md. */
object LicenseConfig {
    /** Live backend. For testing use https://hope-ai-staging.sites.tab.bot */
    const val BASE_URL = "https://hope-ai.sites.tab.bot"

    /** Where the "Buy HOPE AI" button goes. */
    const val BUY_URL = "https://hope-ai.sites.tab.bot"

    /** Firebase project shared with the website (informational; the real config is google-services.json). */
    const val FIREBASE_PROJECT_ID = "hopev3-c0ff4"

    /** Optional bundled copy of GET /api/license/public-key (a JWK set) in app/src/main/assets/. */
    const val PUBLIC_KEY_ASSET = "license_public_key.json"

    /** Re-verify at least this often while the phone is online. */
    const val REVERIFY_INTERVAL_MS = 24L * 60L * 60L * 1000L

    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 30L
}
