package com.jarvis.assistant.ai

import com.jarvis.assistant.data.preferences.AppPreferences
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

class GeminiTextGeneration(
    private val preferences: AppPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()
) : TextGenerationEngine {

    override suspend fun generate(
        systemInstruction: String,
        context: String,
        prompt: String
    ): String {
        val apiKey = preferences.apiKey
        check(apiKey.isNotBlank()) { "Configure the Gemini API key in Settings to use online AI." }

        val contents = JSONArray()
        if (context.isNotBlank()) {
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", context)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )

        val bodyJson = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", systemInstruction))
            ))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 2048))
            .toString()

        // Try the last model that worked first, then the other current models. Model IDs get retired,
        // so a single hard-coded name must never be able to silently break every reply.
        val order = (listOfNotNull(workingModel) + TEXT_MODELS).distinct()
        var lastError: Exception? = null
        for (model in order) {
            try {
                val text = callModel(model, apiKey, bodyJson)
                workingModel = model
                return text
            } catch (error: GeminiHttpException) {
                lastError = error
                // Wrong/blocked API key will fail on every model, so stop and say so clearly.
                if (error.code == 401 || error.code == 403) throw error
                // 404 (unknown model), 400, 429 (quota) and 5xx: try the next model.
                if (workingModel == model) workingModel = null
            }
        }
        throw lastError ?: IllegalStateException("Gemini did not return a reply.")
    }

    private class GeminiHttpException(val code: Int, message: String) : IllegalStateException(message)

    private suspend fun callModel(model: String, apiKey: String, bodyJson: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                val reason = try {
                                    JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                                } catch (_: Exception) {
                                    ""
                                }
                                throw GeminiHttpException(
                                    it.code,
                                    "Gemini ($model) returned HTTP ${it.code}" +
                                        if (reason.isNotBlank()) ": ${reason.take(140)}" else "."
                                )
                            }
                            JSONObject(body)
                                .optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.let { parts ->
                                    (0 until parts.length()).joinToString("") {
                                        parts.optJSONObject(it)?.optString("text").orEmpty()
                                    }
                                }
                                .orEmpty()
                                .trim()
                                .also { result -> check(result.isNotBlank()) { "Gemini returned an empty response." } }
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private companion object {
        /** Current Gemini text model IDs (checked against ai.google.dev, Oct 2026). */
        val TEXT_MODELS = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-2.5-flash")

        @Volatile
        var workingModel: String? = null
    }
}
