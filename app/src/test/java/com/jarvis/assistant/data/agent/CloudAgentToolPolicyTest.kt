package com.jarvis.assistant.data.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        assertNotNull(
            CloudAgentToolPolicy.validateAction(
                "set_screen_brightness",
                mapOf("percent" to 25.0),
                "system_settings.write",
                "moderate",
            ),
        )
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

    @Test
    fun `tool requests are bound to task session and bounded expiry`() {
        val expiry = Instant.now().plus(5, ChronoUnit.MINUTES).toString()
        val valid = CloudAgentToolPolicy.validateEnvelope(
            taskId = TASK_ID,
            sessionId = SESSION_ID,
            toolCallId = CALL_ID,
            toolName = "get_device_context",
            parameters = emptyMap(),
            permissionScope = "device.context.read",
            riskCategory = "low",
            expiresAt = expiry,
            expectedTaskId = TASK_ID,
            expectedSessionId = SESSION_ID,
        )
        assertNull(valid)
        assertNotNull(
            CloudAgentToolPolicy.validateEnvelope(
                taskId = TASK_ID,
                sessionId = SESSION_ID,
                toolCallId = CALL_ID,
                toolName = "get_device_context",
                parameters = emptyMap(),
                permissionScope = "device.context.read",
                riskCategory = "low",
                expiresAt = expiry,
                expectedTaskId = SESSION_ID,
                expectedSessionId = SESSION_ID,
            ),
        )
        assertNotNull(
            CloudAgentToolPolicy.validateEnvelope(
                taskId = TASK_ID,
                sessionId = SESSION_ID,
                toolCallId = CALL_ID,
                toolName = "get_device_context",
                parameters = emptyMap(),
                permissionScope = "device.context.read",
                riskCategory = "low",
                expiresAt = Instant.now().plus(11, ChronoUnit.MINUTES).toString(),
                expectedTaskId = TASK_ID,
                expectedSessionId = SESSION_ID,
            ),
        )
        assertEquals(2, CloudAgentToolPolicy.contracts.size)
        assertEquals(5_000L, CloudAgentToolPolicy.contracts.first().timeoutMillis)
    }

    @Test
    fun `authorized request comparison includes the complete permission envelope`() {
        assertEquals(
            true,
            CloudAgentToolPolicy.matchesAuthorizedEnvelope(
                TASK_ID,
                SESSION_ID,
                CALL_ID,
                "set_screen_brightness",
                mapOf("percent" to 25),
                "system_settings.write",
                "moderate",
                "expires",
                TASK_ID,
                SESSION_ID,
                CALL_ID,
                "set_screen_brightness",
                mapOf("percent" to 25),
                "system_settings.write",
                "moderate",
                "expires",
            ),
        )
        assertEquals(
            false,
            CloudAgentToolPolicy.matchesAuthorizedEnvelope(
                TASK_ID,
                SESSION_ID,
                CALL_ID,
                "set_screen_brightness",
                mapOf("percent" to 25),
                "system_settings.write",
                "moderate",
                "expires",
                TASK_ID,
                SESSION_ID,
                CALL_ID,
                "set_screen_brightness",
                mapOf("percent" to 30),
                "system_settings.write",
                "moderate",
                "expires",
            ),
        )
    }

    private companion object {
        const val TASK_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val CALL_ID = "123e4567-e89b-42d3-a456-426614174002"
    }
}
