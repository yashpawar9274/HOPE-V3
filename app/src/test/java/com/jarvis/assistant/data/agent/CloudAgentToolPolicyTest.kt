package com.jarvis.assistant.data.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudAgentToolPolicyTest {
    @Test
    fun `device context must be a read-only request with no parameters`() {
        assertNull(
            CloudAgentToolPolicy.validateAction(
                "get_device_context",
                emptyMap(),
                "device.context.read",
                "low",
            ),
        )
        assertNotNull(
            CloudAgentToolPolicy.validateAction(
                "get_device_context",
                mapOf("extra" to true),
                "device.context.read",
                "low",
            ),
        )
    }

    @Test
    fun `brightness proposals are restricted to bounded integral percentages`() {
        assertNull(
            CloudAgentToolPolicy.validateAction(
                "set_screen_brightness",
                mapOf("percent" to 25),
                "system_settings.write",
                "moderate",
            ),
        )
        listOf(4, 101, 25.5, "25").forEach { percent ->
            assertNotNull(
                CloudAgentToolPolicy.validateAction(
                    "set_screen_brightness",
                    mapOf("percent" to percent),
                    "system_settings.write",
                    "moderate",
                ),
            )
        }
    }

    @Test
    fun `unregistered tools and mismatched permissions are rejected`() {
        assertNotNull(
            CloudAgentToolPolicy.validateAction(
                "send_message",
                emptyMap(),
                "messages.send",
                "high",
            ),
        )
        assertNotNull(
            CloudAgentToolPolicy.validateAction(
                "set_screen_brightness",
                mapOf("percent" to 25),
                "device.context.read",
                "low",
            ),
        )
    }
}
