package com.jarvis.hope.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiCodingProviderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `requests stateless structured proposals and decodes the response`() {
        var requestBody: String? = null
        var apiKey: String? = null
        withServer { server ->
            server.createContext("/v1beta/interactions") { exchange ->
                requestBody = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
                apiKey = exchange.requestHeaders.getFirst("x-goog-api-key")
                respond(exchange, 200, successfulResponse())
            }
            server.start()

            val plan = provider(server).generate("Add a Kotlin settings screen")

            assertEquals("gemini-4-carbon", plan.modelUsed)
            assertFalse(plan.usedFallback)
            assertEquals("src/Settings.kt", plan.files.single().path)
            assertEquals("val ready = true", plan.files.single().content)
            val request = mapper.readTree(requestBody)
            assertEquals("gemini-4-carbon", request.path("model").asText())
            assertFalse(request.path("store").asBoolean())
            assertEquals("test-key", apiKey)
            assertTrue(request.path("response_format").path("schema").isObject)
        }
    }

    @Test
    fun `uses only the explicitly configured fallback after primary model returns 404`() {
        val requests = mutableListOf<String>()
        withServer { server ->
            server.createContext("/v1beta/interactions") { exchange ->
                val request = mapper.readTree(exchange.requestBody)
                requests += request.path("model").asText()
                if (requests.size == 1) {
                    respond(exchange, 404, """{"error":"model not found"}""")
                } else {
                    respond(exchange, 200, successfulResponse())
                }
            }
            server.start()

            val plan = provider(server, fallbackModel = "configured-fallback").generate("Create a Kotlin helper")

            assertEquals(listOf("gemini-4-carbon", "configured-fallback"), requests)
            assertEquals("configured-fallback", plan.modelUsed)
            assertTrue(plan.usedFallback)
        }
    }

    @Test
    fun `rejects unsafe and oversized generated file proposals`() {
        assertFailsWith<IllegalArgumentException> {
            decodeCodingPlan(
                output(path = "../secrets.txt"),
                "gemini-4-carbon",
                usedFallback = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodeCodingPlan(
                output(content = ""),
                "gemini-4-carbon",
                usedFallback = false,
            )
        }
    }

    @Test
    fun `does not retry unauthorized or rate-limited provider responses`() {
        val callCount = AtomicInteger()
        withServer { server ->
            server.createContext("/v1beta/interactions") { exchange ->
                callCount.incrementAndGet()
                respond(exchange, 429, """{"error":"rate limited"}""")
            }
            server.start()

            val error = assertFailsWith<GeminiCodingProviderException> {
                provider(server, fallbackModel = "configured-fallback").generate("Create a Kotlin helper")
            }

            assertEquals("gemini_coding_rate_limited", error.failureCode)
            assertEquals(1, callCount.get())
        }
    }

    @Test
    fun `publishes coding proposals without creating executable tool calls`() {
        val requestBody = AtomicReference<String>()
        withServer { server ->
            server.createContext("/rest/v1/rpc/publish_hope_agent_plan") { exchange ->
                requestBody.set(String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
                respond(exchange, 200, "\"published\"")
            }
            server.start()
            val store = SupabaseAgentStore(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                serviceRoleKey = "test-service-key",
            )
            val task = ClaimedTask(
                userId = "firebase-user",
                taskId = "f1352cda-52c4-4a63-b85e-5f6c68b75465",
                sessionId = "fc035365-82c1-456d-a69d-e0f5725f98b2",
                objective = "Add a settings helper",
                executionAttempts = 1,
            )

            assertEquals(
                "published",
                store.publishCodingPlan(
                    task,
                    CodingPlan(
                        summary = "Added a settings helper.",
                        needsClarification = false,
                        questions = emptyList(),
                        files = listOf(
                            CodingFileProposal(
                                path = "src/Settings.kt",
                                purpose = "Adds a settings helper.",
                                content = "val ready = true",
                            ),
                        ),
                        modelUsed = "gemini-4-carbon",
                        usedFallback = false,
                    ),
                ),
            )
        }

        val published = mapper.readTree(requestBody.get())
        assertEquals(2, published.path("new_plan").size())
        assertEquals(
            "src/Settings.kt",
            published.path("new_plan").path(1).path("path").asText(),
        )
        assertTrue(published.path("new_tool_calls").isEmpty)
    }

    private fun provider(server: HttpServer, fallbackModel: String? = null) = GeminiCodingProvider(
        apiKey = "test-key",
        primaryModel = "gemini-4-carbon",
        fallbackModel = fallbackModel,
        timeoutSeconds = 15,
        endpoint = URI.create("http://127.0.0.1:${server.address.port}/v1beta/interactions"),
    )

    private fun successfulResponse(): String = mapper.writeValueAsString(
        mapper.createObjectNode()
            .put("status", "completed")
            .put(
                "output_text",
                output(),
            ),
    )

    private fun output(
        path: String = "src/Settings.kt",
        content: String = "val ready = true",
    ): String = mapper.writeValueAsString(mapper.createObjectNode().apply {
        put("summary", "Added a settings helper.")
        put("needs_clarification", false)
        set<ArrayNode>("questions", mapper.createArrayNode())
        set<ArrayNode>(
            "files",
            mapper.createArrayNode().add(
                mapper.createObjectNode()
                    .put("path", path)
                    .put("purpose", "Adds a settings helper.")
                    .put("content", content),
            ),
        )
    })

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        statusCode: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            block(server)
        } finally {
            server.stop(0)
        }
    }
}
