package com.jarvis.assistant.clipboard

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException

data class GeminiImageInput(val mimeType: String, val base64Data: String)

class ClipboardImageProcessor(private val contentResolver: ContentResolver) {

    fun loadPreview(uri: Uri, declaredMimeType: String?): Bitmap {
        val image = readImage(uri, declaredMimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The clipboard URI does not contain a readable image." }
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_EDGE)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
            ?: throw IOException("The clipboard image preview could not be decoded.")
    }

    fun toGeminiInput(uri: Uri, declaredMimeType: String?): GeminiImageInput {
        val image = readImage(uri, declaredMimeType)
        return GeminiImageInput(image.mimeType, Base64.encodeToString(image.bytes, Base64.NO_WRAP))
    }

    private fun readImage(uri: Uri, declaredMimeType: String?): ImageBytes {
        val resolvedMime = try {
            contentResolver.getType(uri)
        } catch (_: SecurityException) {
            null
        }?.let(::normalizeMimeType)
        val declaredMime = declaredMimeType?.let(::normalizeMimeType)
        val mimeType = resolvedMime?.takeIf { it in SUPPORTED_MIME_TYPES }
            ?: declaredMime?.takeIf { it in SUPPORTED_MIME_TYPES }
            ?: resolvedMime
            ?: declaredMime
            ?: throw IOException("The copied image type could not be determined.")
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
        if (normalizedMime !in SUPPORTED_MIME_TYPES) {
            throw IOException("Unsupported clipboard image type: $normalizedMime. Use JPEG, PNG, or WebP.")
        }

        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream(32 * 1024)
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) {
                        throw IOException("The copied image is larger than the 8 MB limit.")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: throw IOException("Android could not open the copied image.")

            if (!matchesFormat(bytes, normalizedMime)) {
                throw IOException("The copied image data does not match its declared format.")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS
            ) {
                throw IOException("The copied image is invalid or exceeds the supported dimensions.")
            }
            return ImageBytes(normalizedMime, bytes)
        } catch (error: SecurityException) {
            throw IOException("HOPE cannot access the copied image. Copy it again and try once more.", error)
        }
    }

    private fun matchesFormat(bytes: ByteArray, mimeType: String): Boolean = when (mimeType) {
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/png" -> bytes.size >= 8 &&
            bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "image/webp" -> bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxEdge || height / sampleSize > maxEdge) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun normalizeMimeType(value: String): String = when (
        value.substringBefore(';').trim().lowercase()
    ) {
        "image/jpg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> value.substringBefore(';').trim().lowercase()
    }

    private data class ImageBytes(val mimeType: String, val bytes: ByteArray)

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 40_000_000L
        const val PREVIEW_MAX_EDGE = 720
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
