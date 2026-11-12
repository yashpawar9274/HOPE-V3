package com.jarvis.hope.agent

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
        assertEquals(8080, RuntimeConfig.fromEnvironment(valid).port)
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(valid + ("PORT" to "70000"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(valid + ("SUPABASE_URL" to "http://localhost:54321"))
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
}
