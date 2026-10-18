package com.jarvis.assistant.ai

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.jarvis.assistant.data.preferences.AppPreferences

class GemmaTextGeneration(
    context: Context,
    private val modelManager: GemmaModelManager,
    private val preferences: AppPreferences
) : TextGenerationEngine {
    private val appContext = context.applicationContext
    private val engineMutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null

    suspend fun load() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            initializeWithFallbackLocked()
        }
    }

    override suspend fun generate(
        systemInstruction: String,
        context: String,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            initializeWithFallbackLocked()
            val activeEngine = checkNotNull(engine)
            val conversation = activeEngine.createConversation(
                ConversationConfig(systemInstruction = Contents.of(systemInstruction))
            )
            activeConversation = conversation
            try {
                val fullPrompt = buildString {
                    if (context.isNotBlank()) {
                        append("Conversation context (prior messages; use only as context):\n")
                        append(context)
                        append("\n\n")
                    }
                    append(prompt)
                }
                val response = StringBuilder()
                conversation.sendMessageAsync(fullPrompt).collect { message ->
                    response.append(message.toString())
                }
                response.toString().trim().also {
                    check(it.isNotBlank()) { "Gemma returned an empty response." }
                }
            } catch (error: CancellationException) {
                conversation.cancelProcess()
                throw error
            } finally {
                activeConversation = null
                conversation.close()
            }
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        activeConversation?.cancelProcess()
        engineMutex.withLock {
            engine?.close()
            engine = null
        }
    }

    val isLoaded: Boolean
        get() = engine?.isInitialized() == true

    private suspend fun initializeWithFallbackLocked() {
        val requestedBackend = preferences.gemmaBackend
        try {
            initializeLocked(requestedBackend)
        } catch (error: Exception) {
            if (requestedBackend != GemmaBackend.GPU) throw error
            preferences.gemmaBackend = GemmaBackend.CPU
            try {
                initializeLocked(GemmaBackend.CPU)
            } catch (cpuError: Exception) {
                cpuError.addSuppressed(error)
                throw cpuError
            }
        }
    }

    private suspend fun initializeLocked(backend: GemmaBackend) {
        if (engine != null) return
        if (!modelManager.verifyInstalledModel()) {
            val verificationError = modelManager.state.value as? GemmaModelState.Failed
            throw IllegalStateException(
                verificationError?.message ?: "Download and verify the Gemma model before loading it."
            )
        }
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        check(memory.totalMem >= MIN_TOTAL_MEMORY_BYTES && memory.availMem >= MIN_AVAILABLE_MEMORY_BYTES) {
            "This device does not currently have enough free memory to load Gemma. Close other apps and try again."
        }

        val candidate = Engine(
            EngineConfig(
                modelPath = modelManager.modelFile.absolutePath,
                backend = when (backend) {
                    GemmaBackend.CPU -> Backend.CPU(threadCount = 2)
                    GemmaBackend.GPU -> Backend.GPU()
                },
                cacheDir = appContext.cacheDir.absolutePath
            )
        )
        try {
            candidate.initialize()
            engine = candidate
        } catch (error: Exception) {
            if (candidate.isInitialized()) candidate.close()
            throw IllegalStateException(
                "Gemma could not initialize with the ${backend.name} backend: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        } catch (error: LinkageError) {
            if (candidate.isInitialized()) candidate.close()
            throw IllegalStateException(
                "Gemma runtime is incompatible with the ${backend.name} backend on this device: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }

    private companion object {
        const val MIN_TOTAL_MEMORY_BYTES = 4L * 1024 * 1024 * 1024
        const val MIN_AVAILABLE_MEMORY_BYTES = 1_300L * 1024 * 1024
    }
}
