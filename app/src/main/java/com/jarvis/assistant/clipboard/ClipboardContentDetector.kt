package com.jarvis.assistant.clipboard

import android.content.ClipData
import android.content.ContentResolver
import android.net.Uri
import java.security.MessageDigest

private const val MAX_CLIPBOARD_TEXT_CHARS = 30_000
private const val CLIPBOARD_TEXT_PREVIEW_CHARS = 4_000

private fun fingerprintValue(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        var start = 0
        while (start < value.length) {
            val end = (start + 2_048).coerceAtMost(value.length)
            digest.update(value.substring(start, end).toByteArray(Charsets.UTF_8))
            start = end
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

class ClipboardContentDetector(private val contentResolver: ContentResolver) {

    fun detect(clip: ClipData?): ClipboardContent? {
        if (clip == null || clip.itemCount == 0) return null

        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri ?: continue
            val declaredMimeTypes = (0 until clip.description.mimeTypeCount)
                .map(clip.description::getMimeType)
            val declaredMime = declaredMimeTypes.firstOrNull {
                it.startsWith("image/", ignoreCase = true)
            } ?: declaredMimeTypes.firstOrNull().orEmpty()
            val resolvedMime = try {
                contentResolver.getType(uri)
            } catch (_: SecurityException) {
                null
            }
            val mimeType = resolvedMime?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: declaredMimeTypes.firstOrNull { it.startsWith("image/", ignoreCase = true) }
                ?: resolvedMime
                ?: declaredMime
            if (mimeType.startsWith("image/", ignoreCase = true) ||
                clip.description.hasMimeType("image/*")
            ) {
                val fingerprint = fingerprint("image:${uri.normalizeScheme()}:$mimeType")
                return ClipboardContent(
                    type = ClipboardContentType.IMAGE,
                    fingerprint = fingerprint,
                    imageUri = uri,
                    mimeType = mimeType
                )
            }
        }

        for (index in 0 until clip.itemCount) {
            val text = clip.getItemAt(index).text?.toString()?.takeIf(String::isNotBlank) ?: continue
            val tooLarge = text.length > MAX_CLIPBOARD_TEXT_CHARS
            val preview = if (tooLarge) text.take(CLIPBOARD_TEXT_PREVIEW_CHARS) else text
            return ClipboardContent(
                type = ClipboardContentType.TEXT,
                fingerprint = fingerprintValue("text:", text),
                text = preview,
                category = classifyText(preview),
                isSensitive = ClipboardPrivacyGuard.isSensitive(
                    if (tooLarge) text.take(MAX_CLIPBOARD_TEXT_CHARS) else text
                ),
                isTooLarge = tooLarge
            )
        }
        return null
    }

    private fun fingerprint(value: String): String = fingerprintValue(value)

    companion object {
        fun fingerprintForText(text: String): String = fingerprintValue("text:", text)

        fun classifyText(text: String): ClipboardTextCategory {
            val value = text.trim()
            val questionStarters = listOf(
                "what ", "why ", "how ", "when ", "where ", "who ", "which ",
                "can ", "could ", "would ", "is ", "are ", "do ", "does ", "should "
            )
            if (value.endsWith("?") ||
                questionStarters.any { value.startsWith(it, ignoreCase = true) }
            ) return ClipboardTextCategory.QUESTION

            if (value.contains("```") || value.contains(Regex("""(?m)^\s*(fun|class|def|import|package|public|private|const|let|var)\s+""")) ||
                value.contains(Regex("""[{};]\s*$"""))
            ) return ClipboardTextCategory.CODE

            if (value.contains(Regex("""(?im)^\s*(you:|me:|them:|he:|she:|[a-z][\w ]{0,20}:)\s""")) ||
                value.contains(Regex("""(?i)\b(hey|hello|hi|thanks|thank you|good morning|good night|how are you)\b""")) &&
                value.length < 500
            ) return ClipboardTextCategory.CONVERSATION

            if (value.length > 700 || value.count { it == '\n' } >= 4) {
                return ClipboardTextCategory.DOCUMENT
            }
            return ClipboardTextCategory.GENERAL
        }
    }
}

object ClipboardPrivacyGuard {
    private val sensitivePatterns = listOf(
        Regex("""(?i)\b(?:password|passcode|passphrase|private key|recovery phrase|seed phrase)\b\s*(?::|=|is)\s*\S{3,}"""),
        Regex("""(?i)\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret[_ -]?key|client[_ -]?secret|bearer)\b\s*[:=]\s*\S{8,}"""),
        Regex("""(?i)\b(?:pin|otp|one[- ]time password|verification code|security code|cvv|cvc)\b\s*(?::|=|is)?\s*\d{3,8}\b"""),
        Regex("""(?i)\b(?:card number|iban)\b\s*[:=]?\s*[\d -]{8,}\d"""),
        Regex("""\b(?:\d[ -]*?){13,19}\b"""),
        Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
        Regex("""\b[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}\b""")
    )

    fun isSensitive(text: String): Boolean = sensitivePatterns.any { it.containsMatchIn(text) }
}
