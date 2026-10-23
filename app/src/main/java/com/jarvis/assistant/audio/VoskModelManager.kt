package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream

object VoskModelManager {
    private const val TAG = "VoskModelManager"

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isSpeakerModelReady = MutableStateFlow(false)
    val isSpeakerModelReady: StateFlow<Boolean> = _isSpeakerModelReady.asStateFlow()

    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError: StateFlow<String?> = _initializationError.asStateFlow()

    @Volatile
    var model: Model? = null
        private set

    @Volatile
    var speakerModel: SpeakerModel? = null
        private set

    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var speakerModelRequested = false

    fun init(context: Context, scope: CoroutineScope, includeSpeakerModel: Boolean = true) {
        if (includeSpeakerModel) speakerModelRequested = true
        if (model != null && (!speakerModelRequested || speakerModel != null)) return
        if (!isInitializing.compareAndSet(false, true)) return
        _initializationError.value = null

        scope.launch(Dispatchers.IO) {
            try {
                if (model == null) {
                    val targetDir = File(context.filesDir, "vosk-model")
                    val markerFile = File(targetDir, "am/final.mdl")

                    if (!markerFile.exists() || markerFile.length() == 0L) {
                        Log.i(TAG, "Unpacking the offline English model for an explicitly requested voice feature.")
                        targetDir.deleteRecursively()
                        targetDir.mkdirs()
                        copyAssetFolder(context.assets, "model-en-us", targetDir)
                        check(markerFile.exists() && markerFile.length() > 0L) {
                            "The bundled offline English model is incomplete."
                        }
                    }

                    model = Model(targetDir.absolutePath)
                    _isModelReady.value = true
                    Log.i(TAG, "Offline English Vosk model is ready.")
                }

                if (speakerModelRequested && speakerModel == null) {
                    loadSpeakerModel(context)
                }
            } catch (e: Throwable) {
                _initializationError.value = e.message ?: "The offline speech model could not be initialized."
                Log.e(TAG, "FATAL: Failed to load Vosk model: ${e.message}", e)
            } finally {
                isInitializing.set(false)
            }
        }
    }

    private fun loadSpeakerModel(context: Context) {
        try {
            val targetDir = File(context.filesDir, "vosk-spk-model")
            val markerFile = File(targetDir, "final.ext.raw")

            if (!markerFile.exists() || markerFile.length() == 0L) {
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                copyAssetFolder(context.assets, "model-spk", targetDir)
                check(markerFile.exists() && markerFile.length() > 0L) {
                    "The bundled speaker model is incomplete."
                }
            }

            speakerModel = SpeakerModel(targetDir.absolutePath)
            _isSpeakerModelReady.value = true
            Log.i(TAG, "Optional speaker verification model is ready.")
        } catch (error: Exception) {
            _initializationError.value = error.message ?: "The optional speaker verification model could not be loaded."
            Log.e(TAG, "The optional speaker verification model could not be loaded.", error)
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toDir: File) {
        val list = assetManager.list(fromAssetPath)
        if (list.isNullOrEmpty()) {
            try {
                assetManager.open(fromAssetPath).use { input ->
                    toDir.parentFile?.mkdirs()
                    FileOutputStream(toDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy asset $fromAssetPath: ${e.message}")
            }
        } else {
            toDir.mkdirs()
            for (item in list) {
                val childAsset = if (fromAssetPath.isEmpty()) item else "$fromAssetPath/$item"
                val childTarget = File(toDir, item)
                copyAssetFolder(assetManager, childAsset, childTarget)
            }
        }
    }
}
