package com.jarvis.hope.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val config = RuntimeConfig.fromEnvironment()
    val store = SupabaseAgentStore(config.supabaseUrl, config.serviceRoleKey)
    val planner = AgentPlanner(config.geminiApiKey, config.geminiModel)
    val codingProvider = GeminiCodingProvider(
        apiKey = config.geminiApiKey,
        primaryModel = config.codingModel,
        fallbackModel = config.codingFallbackModel,
        timeoutSeconds = config.codingTimeoutSeconds,
    )
    val worker = AgentWorker(store, planner, codingProvider, config.claimBatchSize)
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", config.port), 0)
    val serverExecutor = Executors.newFixedThreadPool(2)
    server.executor = serverExecutor
    server.createContext("/healthz") { exchange ->
        val body = "ok".toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    println("HOPE Cloud Agent runtime is listening on port ${config.port}.")

    val executor = Executors.newSingleThreadScheduledExecutor()
    executor.scheduleWithFixedDelay(
        {
            try {
                worker.runOnce()
            } catch (_: Exception) {
                System.err.println("Cloud Agent polling cycle failed.")
            }
        },
        0,
        config.pollIntervalSeconds,
        TimeUnit.SECONDS,
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        executor.shutdownNow()
        server.stop(1)
        serverExecutor.shutdownNow()
        planner.close()
    })
}
