package com.jarvis.assistant.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.assistant.ui.clipboard.SmartClipboardActivity

sealed interface ClipboardReadResult {
    data class Content(val value: ClipboardContent) : ClipboardReadResult
    object Empty : ClipboardReadResult
    object BackgroundRestricted : ClipboardReadResult
    data class Unavailable(val reason: String) : ClipboardReadResult
}

class ClipboardAnalysisManager(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val detector = ClipboardContentDetector(appContext.contentResolver)

    fun readCurrentClip(activity: Activity?): ClipboardReadResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (activity == null || activity.isFinishing || activity.isDestroyed)
        ) {
            return ClipboardReadResult.BackgroundRestricted
        }
        return try {
            val clip: ClipData? = clipboard.primaryClip
            val content = detector.detect(clip) ?: return ClipboardReadResult.Empty
            ClipboardReadResult.Content(content)
        } catch (_: SecurityException) {
            ClipboardReadResult.BackgroundRestricted
        } catch (error: RuntimeException) {
            ClipboardReadResult.Unavailable(
                error.message ?: "Clipboard content could not be accessed."
            )
        }
    }

    companion object {
        fun intentFor(content: ClipboardContent): Intent =
            Intent().setClassName(
                "com.jarvis.assistant",
                SmartClipboardActivity::class.java.name
            ).apply {
                putExtra(SmartClipboardActivity.EXTRA_CONTENT_TYPE, content.type.name)
                putExtra(SmartClipboardActivity.EXTRA_FINGERPRINT, content.fingerprint)
                putExtra(SmartClipboardActivity.EXTRA_TEXT, content.text)
                putExtra(SmartClipboardActivity.EXTRA_IMAGE_URI, content.imageUri?.toString())
                putExtra(SmartClipboardActivity.EXTRA_MIME_TYPE, content.mimeType)
                putExtra(SmartClipboardActivity.EXTRA_CATEGORY, content.category?.name)
                putExtra(SmartClipboardActivity.EXTRA_IS_SENSITIVE, content.isSensitive)
                putExtra(SmartClipboardActivity.EXTRA_IS_TOO_LARGE, content.isTooLarge)
            }
    }
}
