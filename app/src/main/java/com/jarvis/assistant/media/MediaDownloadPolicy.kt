package com.jarvis.assistant.media

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale

enum class MediaDownloadKind {
    VIDEO,
    AUDIO
}

object MediaDownloadPolicy {
    private val sharedUrlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun extractHttpsUrl(sharedText: String): String? {
        val candidate = sharedUrlPattern.find(sharedText)?.value
            ?.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
            ?: return null
        return validateHttpsUrl(candidate)
    }

    fun validateHttpsUrl(rawUrl: String): String? {
        val parsed = try {
            URI(rawUrl.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" || parsed.rawUserInfo != null || parsed.port !in listOf(-1, 443)) {
            return null
        }
        if (
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal")
        ) {
            return null
        }
        if (isIpLiteral(host)) {
            val address = try {
                InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
            } catch (_: UnknownHostException) {
                return null
            }
            if (!isPublicInternetAddress(address)) return null
        }
        return parsed.normalize().toASCIIString().substringBefore('#')
    }

    fun isPublicInternetAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0]
                val second = bytes[1]
                val third = bytes[2]
                when {
                    first == 0 || first >= 224 -> false
                    first == 100 && second in 64..127 -> false
                    first == 192 && second == 0 && third == 0 -> false
                    first == 192 && second == 0 && third == 2 -> false
                    first == 192 && second == 88 && third == 99 -> false
                    first == 198 && second in 18..19 -> false
                    first == 198 && second == 51 && third == 100 -> false
                    first == 203 && second == 0 && third == 113 -> false
                    else -> true
                }
            }
            is Inet6Address -> {
                val globalUnicast = bytes[0] in 0x20..0x3f
                val transitionRange =
                    bytes[0] == 0x20 &&
                        bytes[1] == 0x01 &&
                        bytes[2] in 0x00..0x07
                val documentationRange =
                    bytes[0] == 0x20 &&
                        bytes[1] == 0x01 &&
                        bytes[2] == 0x0d &&
                        bytes[3] == 0xb8
                val sixToFourRange = bytes[0] == 0x20 && bytes[1] == 0x02
                globalUnicast && !transitionRange && !documentationRange && !sixToFourRange
            }
            else -> false
        }
    }

    fun safeDisplayName(candidate: String?, fallback: String, extension: String): String {
        val input = candidate
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
            ?: fallback
        val cleaned = input
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ', '_')
            .take(100)
            .ifBlank { fallback }
        val expectedExtension = extension.removePrefix(".").lowercase(Locale.ROOT)
        val currentExtension = cleaned.substringAfterLast('.', "")
        return if (currentExtension.equals(expectedExtension, ignoreCase = true)) {
            cleaned
        } else {
            "${cleaned.substringBeforeLast('.', cleaned)}.$expectedExtension"
        }
    }

    fun inferMediaMimeType(fileName: String): String? = when (
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "mp4" -> "video/mp4"
        "m4v" -> "video/x-m4v"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        else -> null
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
}

object SharedMediaDownloadSession {
    @Volatile
    private var sharedUrl: String? = null
    @Volatile
    private var openAfterAuthentication = false

    fun setSharedUrl(url: String, shouldOpenAfterAuthentication: Boolean = false) {
        sharedUrl = MediaDownloadPolicy.validateHttpsUrl(url)
        openAfterAuthentication = shouldOpenAfterAuthentication && sharedUrl != null
    }

    fun getSharedUrl(): String? = sharedUrl

    fun consumeOpenAfterAuthentication(): String? {
        if (!openAfterAuthentication) return null
        openAfterAuthentication = false
        return sharedUrl
    }

    fun clearSharedUrl() {
        sharedUrl = null
        openAfterAuthentication = false
    }
}
