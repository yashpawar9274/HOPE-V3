package com.jarvis.assistant.license

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Plain HTTP client for the licence endpoints (OkHttp + coroutines). */
internal class LicenseApi(
    private val client: OkHttpClient = defaultClient
) {
    class HttpResult(val status: Int, val body: Map<String, Any?>?, val raw: String) {
        val ok: Boolean get() = body?.get("ok") == true
        val errorCode: String? get() = body?.get("error") as? String
        val message: String? get() = body?.get("message") as? String
    }

    /** POST with the Firebase ID token: `Authorization: Bearer <token>`. Throws IOException when offline. */
    suspend fun post(path: String, idToken: String, fields: Map<String, String>): HttpResult {
        val json = JSONObject()
        fields.forEach { (key, value) -> json.put(key, value) }
        val request = Request.Builder()
            .url(LicenseConfig.BASE_URL + path)
            .header("Authorization", "Bearer $idToken")
            .header("Accept", "application/json")
            .post(json.toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    /** Public GET (no token), used for /api/license/public-key. */
    suspend fun get(path: String): HttpResult {
        val request = Request.Builder()
            .url(LicenseConfig.BASE_URL + path)
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { it.body?.string().orEmpty() to it.code }
                    if (continuation.isActive) {
                        continuation.resume(HttpResult(text.second, MiniJson.parseObject(text.first), text.first))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Could not read the server response.", error))
                }
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(LicenseConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(LicenseConfig.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
