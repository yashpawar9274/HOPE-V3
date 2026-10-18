package com.jarvis.assistant.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface GemmaModelState {
    data object Missing : GemmaModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : GemmaModelState
    data object Paused : GemmaModelState
    data object Ready : GemmaModelState
    data class Failed(val message: String) : GemmaModelState
}

val GemmaModelState.Downloading.progressPermille: Int
    get() = if (totalBytes <= 0L) 0 else (downloadedBytes * 1000 / totalBytes).toInt().coerceIn(0, 1000)

class GemmaModelManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val MODEL_REPOSITORY = "litert-community/Gemma3-1B-IT"
        const val MODEL_REVISION = "d448835d84411a08f577e25f3afebe528402d73b"
        const val MODEL_FILE = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm"
        const val MODEL_BYTES = 584_417_280L
        private const val MODEL_GIT_BLOB_OID = "fc3b47f8da81c0a6d4747425655ea51ea4166980"
        private const val MODEL_SHA256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"
        private const val MODEL_SIZE_LIMIT_BYTES = MODEL_BYTES
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "gemma")
    val modelFile = File(directory, MODEL_FILE)
    private val partialFile = File(directory, "$MODEL_FILE.part")
    private val integrityFile = File(directory, "$MODEL_FILE.sha256")
    private val paused = AtomicBoolean(false)
    private val downloadMutex = Mutex()
    @Volatile private var activeCall: Call? = null
    private val _state = MutableStateFlow<GemmaModelState>(initialState())
    val state: StateFlow<GemmaModelState> = _state.asStateFlow()
    val hasModelFiles: Boolean
        get() = modelFile.exists() || partialFile.exists() || integrityFile.exists()

    fun pause() {
        paused.set(true)
        activeCall?.cancel()
    }

    suspend fun download(token: String) = downloadMutex.withLock {
        downloadInternal(token)
    }

    suspend fun importModel(uri: Uri) = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            paused.set(false)
            try {
                directory.mkdirs().also { created ->
                    check(created || directory.isDirectory) { "Could not create app-private model storage." }
                }
                check(!partialFile.exists() || partialFile.delete()) {
                    "Could not prepare a temporary file for importing the model."
                }
                val input = checkNotNull(appContext.contentResolver.openInputStream(uri)) {
                    "The selected model file could not be opened."
                }
                input.use { source ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                        var total = 0L
                        _state.value = GemmaModelState.Downloading(0L, MODEL_SIZE_LIMIT_BYTES)
                        while (true) {
                            if (paused.get()) throw IOException("Model import paused.")
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MODEL_SIZE_LIMIT_BYTES) {
                                "Selected file is larger than the expected Gemma model."
                            }
                            output.write(buffer, 0, count)
                            _state.value = GemmaModelState.Downloading(total, MODEL_SIZE_LIMIT_BYTES)
                        }
                        output.fd.sync()
                        check(total == MODEL_SIZE_LIMIT_BYTES) {
                            "Selected file size does not match the Gemma model. Choose the .litertlm file, not a Git LFS pointer or archive."
                        }
                    }
                }
                check(sha256(partialFile) == MODEL_SHA256) {
                    "Selected file failed SHA-256 verification. Download the exact pinned .litertlm model file."
                }
                finalizeVerifiedModel(MODEL_SHA256)
                _state.value = GemmaModelState.Ready
            } catch (error: CancellationException) {
                _state.value = GemmaModelState.Paused
                throw error
            } catch (error: Exception) {
                _state.value = GemmaModelState.Failed(error.message ?: "Could not import the Gemma model.")
                throw error
            }
        }
    }

    private suspend fun downloadInternal(token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Enter a Hugging Face read token after accepting the Gemma model terms." }
        paused.set(false)
        try {
            if (modelFile.isFile && verifyInstalledModel()) {
                _state.value = GemmaModelState.Ready
                return@withContext
            }
            val metadata = fetchModelMetadata(token)
            var partialLength = partialFile.takeIf { it.exists() }?.length() ?: 0L
            require(partialLength <= metadata.size) { "Partial model file is larger than the verified model." }
            if (partialLength == metadata.size) {
                if (sha256(partialFile) == metadata.sha256) {
                    finalizeVerifiedModel(metadata.sha256)
                    _state.value = GemmaModelState.Ready
                    return@withContext
                }
                check(partialFile.delete()) { "Could not remove the incomplete model file after integrity verification failed." }
                partialLength = 0L
            }
            directory.mkdirs().also { created ->
                check(created || directory.isDirectory) { "Could not create app-private model storage." }
            }

            val requestBuilder = Request.Builder()
                .url(
                    "https://huggingface.co/$MODEL_REPOSITORY/resolve/$MODEL_REVISION/$MODEL_FILE"
                )
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/octet-stream")
            if (partialLength > 0L) requestBuilder.header("Range", "bytes=$partialLength-")
            val call = client.newCall(requestBuilder.build())
            activeCall = call
            _state.value = GemmaModelState.Downloading(partialLength, metadata.size)
            call.execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "Hugging Face denied model access. Accept the Gemma terms and use a valid read token."
                        else -> "Model download failed with HTTP ${response.code}."
                    }
                }
                val body = checkNotNull(response.body) { "Model download response had no body." }
                val append = partialLength > 0L && response.code == 206
                if (append) {
                    check(response.header("Content-Range")?.startsWith("bytes $partialLength-") == true) {
                        "Server resumed the download from an unexpected byte offset."
                    }
                }
                if (partialLength > 0L && !append) partialFile.delete()
                val initialBytes = if (append) partialLength else 0L
                require(initialBytes + body.contentLength().coerceAtLeast(0L) <= metadata.size) {
                    "Model download response exceeds the verified model size."
                }

                body.byteStream().use { input ->
                    FileOutputStream(partialFile, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                        var total = initialBytes
                        while (true) {
                            if (paused.get()) throw IOException("Download paused.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= metadata.size) { "Downloaded model exceeded the verified size." }
                            output.write(buffer, 0, count)
                            _state.value = GemmaModelState.Downloading(total, metadata.size)
                        }
                        output.fd.sync()
                        check(total == metadata.size) { "Downloaded model size does not match the published metadata." }
                    }
                }
            }
            activeCall = null
            check(sha256(partialFile) == metadata.sha256) { "Downloaded model failed SHA-256 verification." }
            finalizeVerifiedModel(metadata.sha256)
            _state.value = GemmaModelState.Ready
        } catch (e: CancellationException) {
            activeCall = null
            _state.value = GemmaModelState.Paused
            throw e
        } catch (e: IOException) {
            activeCall = null
            if (paused.get()) {
                _state.value = GemmaModelState.Paused
            } else {
                _state.value = GemmaModelState.Failed(e.message ?: "Model download was interrupted.")
                throw e
            }
        } catch (e: Exception) {
            activeCall = null
            _state.value = GemmaModelState.Failed(e.message ?: "Could not download or verify the model.")
            throw e
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        pause()
        downloadMutex.withLock {
            check(!modelFile.exists() || modelFile.delete()) { "Could not delete the downloaded model." }
            check(!partialFile.exists() || partialFile.delete()) { "Could not delete the incomplete model download." }
            check(!integrityFile.exists() || integrityFile.delete()) { "Could not delete model integrity metadata." }
            _state.value = GemmaModelState.Missing
        }
    }

    suspend fun verifyInstalledModel(): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.isFile || modelFile.length() != MODEL_SIZE_LIMIT_BYTES) {
            return@withContext false
        }
        if (!integrityFile.isFile) {
            _state.value = GemmaModelState.Failed("The installed model has no trusted integrity record. Download it again.")
            return@withContext false
        }
        val expectedDigest = integrityFile.readText().trim()
        if (!expectedDigest.matches(Regex("[0-9a-f]{64}"))) {
            _state.value = GemmaModelState.Failed("The installed model integrity record is invalid. Download it again.")
            return@withContext false
        }
        val valid = sha256(modelFile) == expectedDigest
        if (!valid) {
            _state.value = GemmaModelState.Failed("The installed model failed SHA-256 verification. Delete and download it again.")
        }
        valid
    }

    private fun initialState(): GemmaModelState =
        if (modelFile.isFile && modelFile.length() == MODEL_BYTES && integrityFile.isFile) GemmaModelState.Ready
        else GemmaModelState.Missing

    private fun fetchModelMetadata(token: String): VerifiedModelMetadata {
        val url = "https://huggingface.co/api/models/$MODEL_REPOSITORY/tree/$MODEL_REVISION?recursive=true&expand=true"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) {
                    "Could not verify the official model metadata (HTTP ${response.code}). Check model access and token."
                }
                val entries = JSONArray(response.body?.string().orEmpty())
                val item = (0 until entries.length())
                    .mapNotNull { entries.optJSONObject(it) }
                    .firstOrNull { it.optString("path") == MODEL_FILE }
                    ?: error("The pinned Gemma model file is missing from its published revision.")
                val blobOid = item.optString("oid").lowercase(Locale.ROOT)
                require(blobOid == MODEL_GIT_BLOB_OID) {
                    "The pinned model file metadata does not match the verified Gemma artifact."
                }
                val size = item.optLong("size", -1L)
                require(size == MODEL_SIZE_LIMIT_BYTES) { "The pinned model file size does not match the approved model details." }
                return VerifiedModelMetadata(size, MODEL_SHA256)
            }

        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finalizeVerifiedModel(expectedDigest: String) {
        integrityFile.writeText("$expectedDigest\n")
        try {
            java.nio.file.Files.move(
                partialFile.toPath(),
                modelFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            check(partialFile.renameTo(modelFile)) { "Could not finalize the verified model file." }
        }
    }

    private data class VerifiedModelMetadata(val size: Long, val sha256: String)
}
