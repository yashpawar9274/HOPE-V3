package com.jarvis.hope.agent

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentPlannerTest {
    @Test
    fun `runtime configuration requires secure origins and bounded settings`() {
        val valid = mapOf(
            "SUPABASE_URL" to "https://hope.example.supabase.co/",
            "SUPABASE_SERVICE_ROLE_KEY" to "server-secret",
            "GEMINI_API_KEY" to "server-secret",
        )
        val config = RuntimeConfig.fromEnvironment(valid)
        assertEquals(8080, config.port)
        assertEquals("gemini-4-carbon", config.codingModel)
        assertEquals(null, config.codingFallbackModel)
        assertEquals(90, config.codingTimeoutSeconds)
        val configuredFallback = RuntimeConfig.fromEnvironment(
            valid + mapOf(
                "GEMINI_CODING_FALLBACK_MODEL" to "fallback-model",
                "GEMINI_CODING_TIMEOUT_SECONDS" to "45",
            ),
        )
        assertEquals("fallback-model", configuredFallback.codingFallbackModel)
        assertEquals(45, configuredFallback.codingTimeoutSeconds)
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(valid + ("PORT" to "70000"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(valid + ("SUPABASE_URL" to "http://localhost:54321"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(
                valid + ("GEMINI_CODING_FALLBACK_MODEL" to "gemini-4-carbon"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(valid + ("GEMINI_CODING_TIMEOUT_SECONDS" to "10"))
        }
    }

    @Test
    fun `accepts only bounded known device actions`() {
        val plan = decodePlan(
            """{"response":"I can check the device state.","actions":[{"tool":"get_device_context","parameters":{},"risk":"low"}]}""",
        )
        assertEquals(1, plan.actions.size)
        assertEquals("device.context.read", plan.actions.single().permissionScope)
    }

    @Test
    fun `registered tools require explicit confirmation and retain least privilege metadata`() {
        val brightness = AgentToolRegistry.definitions.single { it.name == "set_screen_brightness" }
        assertEquals(true, brightness.confirmationRequired)
        assertEquals("system_settings.write", brightness.permissionScope)
        assertEquals("moderate", brightness.riskCategory)
    }

    @Test
    fun `rejects unknown tools and unsafe brightness values`() {
        assertFailsWith<IllegalArgumentException> {
            decodePlan("""{"response":"Done.","actions":[{"tool":"send_message","parameters":{},"risk":"low"}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            decodePlan("""{"response":"Done.","actions":[{"tool":"set_screen_brightness","parameters":{"percent":0},"risk":"moderate"}]}""")
        }
    }

    @Test
    fun `rejects extra text and excessive action counts`() {
        assertFailsWith<Exception> {
            decodePlan("""Plan: {"response":"Hello","actions":[]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            decodePlan(
                """{"response":"Hello","actions":[{}, {}, {}, {}, {}, {}]}""",
            )
        }
    }

    @Test
    fun `registry rejects mismatched risk and extra parameters`() {
        val mapper = ObjectMapper()
        assertFailsWith<IllegalArgumentException> {
            AgentToolRegistry.decodeAction(
                "get_device_context",
                mapper.readTree("""{"unexpected":true}"""),
                "low",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentToolRegistry.decodeAction(
                "set_screen_brightness",
                mapper.readTree("""{"percent":40}"""),
                "low",
            )
        }
    }
}
