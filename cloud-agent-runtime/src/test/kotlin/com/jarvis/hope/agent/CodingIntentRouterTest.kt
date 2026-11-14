package com.jarvis.hope.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CodingIntentRouterTest {
    @Test
    fun `routes programming requests in English and Hinglish to coding proposals`() {
        assertEquals(
            AgentRequestRoute.CODING,
            CodingIntentRouter.route("Please fix this Kotlin app bug"),
        )
        assertEquals(
            AgentRequestRoute.CODING,
            CodingIntentRouter.route("Mere Android app me login issue theek karo"),
        )
        assertEquals(
            AgentRequestRoute.CODING,
            CodingIntentRouter.route("Build an Android app"),
        )
    }

    @Test
    fun `keeps ordinary conversation on the existing planner route`() {
        assertEquals(
            AgentRequestRoute.CONVERSATION,
            CodingIntentRouter.route("What is the weather today?"),
        )
        assertEquals(
            AgentRequestRoute.CONVERSATION,
            CodingIntentRouter.route("Explain how Kotlin coroutines work"),
        )
        assertEquals(
            AgentRequestRoute.CONVERSATION,
            CodingIntentRouter.route("Help me use my Android phone"),
        )
    }
}
