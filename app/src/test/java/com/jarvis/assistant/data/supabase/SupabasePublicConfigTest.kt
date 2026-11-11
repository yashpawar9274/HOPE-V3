package com.jarvis.assistant.data.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabasePublicConfigTest {

    @Test
    fun requiresHttpsProjectUrlAndPublishableKey() {
        assertTrue(
            SupabasePublicConfig(
                "https://example.supabase.co",
                "sb_publishable_public-client-value"
            ).isConfigured
        )
        assertFalse(SupabasePublicConfig("", "public-key").isConfigured)
        assertFalse(SupabasePublicConfig("http://example.supabase.co", "public-key").isConfigured)
        assertFalse(SupabasePublicConfig("https://example.supabase.co", "").isConfigured)
    }
}
