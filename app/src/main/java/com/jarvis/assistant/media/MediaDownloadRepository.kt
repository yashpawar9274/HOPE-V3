package com.jarvis.assistant.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DownloadedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
)

class MediaDownloadException(message: String) : IOException(message)

internal object MediaSampleFlagPolicy {
    private val supportedExtractorFlags =
        MediaExtractor.SAMPLE_FLAG_SYNC or
            MediaExtractor.SAMPLE_FLAG_ENCRYPTED or
            MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME

    fun toCodecFlags(sampleFlags: Int): Int {
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
            throw MediaDownloadException("Encrypted audio tracks are not supported.")
        }
        if (sampleFlags and supportedExtractorFlags.inv() != 0) {
            throw MediaDownloadException("The audio track uses unsupported sample flags.")
        }
        var codecFlags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }
}

class MediaDownloadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val maxDownloadBytes = 250L * 1024L * 1024L
    private val redirectLimit = 5
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = try {
                    InetAddress.getAllByName(hostname).toList()
                } catch (error: UnknownHostException) {
                    throw error
                }
                if (resolved.isEmpty() || resolved.any { !MediaDownloadPolicy.isPublicInternetAddress(it) }) {
                    throw UnknownHostException("The shared link does not resolve to a public internet host.")
                }
                return resolved
            }
        })
        .build()

    suspend fun download(
        sharedUrl: String,
        kind: MediaDownloadKind,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        val validatedUrl = MediaDownloadPolicy.validateHttpsUrl(sharedUrl)
            ?: throw MediaDownloadException("Only a valid public HTTPS media link can be downloaded.")
        var sourceFile: File? = null
        var extractedAudioFile: File? = null
        try {
            val responseFile = downloadToTemporaryFile(validatedUrl, onProgress)
            sourceFile = responseFile.file
            val tracks = inspectTracks(sourceFile)
            val sourceName = responseFile.sourceName
            val output = when (kind) {
                MediaDownloadKind.VIDEO -> {
                    if (tracks.videoTrackIndex < 0 || !responseFile.mimeType.startsWith("video/")) {
                        throw MediaDownloadException("This direct link did not provide a supported video file.")
                    }
                    val extension = extensionForVideo(responseFile.mimeType)
                    OutputMedia(sourceFile, responseFile.mimeType, extension, sourceName)
                }
                MediaDownloadKind.AUDIO -> {
                    if (tracks.audioTrackIndex < 0) {
                        throw MediaDownloadException("No audio track was found in the shared media.")
                    }
                    if (tracks.videoTrackIndex >= 0) {
                        val audioMime = tracks.audioMimeType.orEmpty()
                        if (audioMime !in muxableAudioMimeTypes) {
                            throw MediaDownloadException(
                                "This video uses an audio format HOPE cannot extract yet. Try downloading the original video."
                            )
                        }
                        extractedAudioFile = File.createTempFile("hope-audio-", ".m4a", appContext.cacheDir)
                        extractAudioTrack(sourceFile, extractedAudioFile, tracks.audioTrackIndex)
                        OutputMedia(
                            extractedAudioFile,
                            "audio/mp4",
                            "m4a",
                            "${sourceName.substringBeforeLast('.', sourceName)}.m4a"
                        )
                    } else {
                        if (!responseFile.mimeType.startsWith("audio/")) {
                            throw MediaDownloadException("This direct link did not provide a supported audio file.")
                        }
                        OutputMedia(
                            sourceFile,
                            responseFile.mimeType,
                            extensionForAudio(responseFile.mimeType),
                            sourceName
                        )
                    }
                }
            }
            saveToDevice(output, kind)
        } finally {
            sourceFile?.delete()
            extractedAudioFile?.delete()
        }
    }

    private fun downloadToTemporaryFile(
        initialUrl: String,
        onProgress: (Long, Long) -> Unit
    ): TemporaryMedia {
        var currentUrl = initialUrl
        repeat(redirectLimit + 1) { redirectCount ->
            currentUrl = MediaDownloadPolicy.validateHttpsUrl(currentUrl)
                ?: throw MediaDownloadException("A redirect led to an unsupported or unsafe link.")
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", "video/*, audio/*, application/octet-stream")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            var redirectUrl: String? = null
            response.use { result ->
                if (result.code in 300..399) {
                    val location = result.header("Location")
                        ?: throw MediaDownloadException("The media server returned an invalid redirect.")
                    redirectUrl = try {
                        URI(currentUrl).resolve(location).toString()
                    } catch (_: URISyntaxException) {
                        throw MediaDownloadException("The media server returned an invalid redirect.")
                    }
                    return@use
                }
                if (!result.isSuccessful) {
                    throw MediaDownloadException("The media server returned HTTP ${result.code}.")
                }
                val encoding = result.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw MediaDownloadException("Compressed media responses are not supported.")
                }
                val body = result.body ?: throw MediaDownloadException("The media server returned an empty response.")
                val responseMimeType = body.contentType()?.toString()
                    ?.substringBefore(';')
                    ?.lowercase(Locale.ROOT)
                    ?: result.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                    ?: ""
                val sourceName = sourceName(currentUrl, result.header("Content-Disposition"))
                val mimeType = if (
                    responseMimeType.startsWith("video/") ||
                    responseMimeType.startsWith("audio/")
                ) {
                    responseMimeType
                } else if (
                    responseMimeType.isBlank() ||
                    responseMimeType == "application/octet-stream"
                ) {
                    MediaDownloadPolicy.inferMediaMimeType(sourceName)
                        ?: throw MediaDownloadException(
                            "This link does not identify a supported audio or video file. Streaming-platform pages are not supported."
                        )
                } else {
                    throw MediaDownloadException(
                        "This is not a direct downloadable audio or video file. Streaming-platform links are not supported."
                    )
                }
                val totalBytes = body.contentLength()
                if (totalBytes > maxDownloadBytes) {
                    throw MediaDownloadException("The media file exceeds HOPE's 250 MB download limit.")
                }
                val file = File.createTempFile("hope-media-", ".download", appContext.cacheDir)
                try {
                    var downloadedBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var lastReportedBytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloadedBytes += count
                                if (downloadedBytes > maxDownloadBytes) {
                                    throw MediaDownloadException("The media file exceeds HOPE's 250 MB download limit.")
                                }
                                output.write(buffer, 0, count)
                                if (downloadedBytes - lastReportedBytes >= progressIntervalBytes) {
                                    onProgress(downloadedBytes, totalBytes)
                                    lastReportedBytes = downloadedBytes
                                }
                            }
                        }
                    }
                    if (downloadedBytes == 0L) {
                        throw MediaDownloadException("The media server returned an empty file.")
                    }
                    onProgress(downloadedBytes, totalBytes.takeIf { it > 0L } ?: downloadedBytes)
                    return TemporaryMedia(
                        file = file,
                        mimeType = mimeType,
                        sourceName = sourceName
                    )
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
            currentUrl = redirectUrl
                ?: throw MediaDownloadException("The media server returned an invalid response.")
            if (redirectCount == redirectLimit) {
                throw MediaDownloadException("The media link redirected too many times.")
            }
        }
        throw MediaDownloadException("The media link could not be downloaded.")
    }

    private fun inspectTracks(file: File): MediaTracks {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var audioMimeType: String? = null
            for (index in 0 until extractor.trackCount) {
                val mimeType = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                if (mimeType.startsWith("video/") && videoTrackIndex < 0) videoTrackIndex = index
                if (mimeType.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = index
                    audioMimeType = mimeType
                }
            }
            return MediaTracks(videoTrackIndex, audioTrackIndex, audioMimeType)
        } catch (_: IllegalArgumentException) {
            throw MediaDownloadException("The downloaded file is not a playable media file.")
        } finally {
            extractor.release()
        }
    }

    private fun extractAudioTrack(source: File, destination: File, audioTrackIndex: Int) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)
            val outputTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true
            extractor.selectTrack(audioTrackIndex)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize > buffer.capacity()) {
                    throw MediaDownloadException("An audio frame is too large to process safely.")
                }
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = MediaSampleFlagPolicy.toCodecFlags(extractor.sampleFlags)
                muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                sampleCount++
                extractor.advance()
            }
            if (sampleCount == 0) throw MediaDownloadException("No extractable audio samples were found.")
            muxer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: IllegalStateException) {
                    destination.delete()
                }
            }
            muxer.release()
            extractor.release()
        }
    }

    private fun saveToDevice(output: OutputMedia, kind: MediaDownloadKind): DownloadedMedia {
        val extension = output.extension
        val displayName = MediaDownloadPolicy.safeDisplayName(
            output.sourceName,
            if (kind == MediaDownloadKind.VIDEO) "HOPE_video" else "HOPE_audio",
            extension
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(output, kind, displayName)
        } else {
            saveInAppFiles(output, displayName)
        }
    }

    private fun saveWithMediaStore(
        output: OutputMedia,
        kind: MediaDownloadKind,
        displayName: String
    ): DownloadedMedia {
        val collection = if (kind == MediaDownloadKind.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (kind == MediaDownloadKind.VIDEO) {
            "${Environment.DIRECTORY_MOVIES}/HOPE"
        } else {
            "${Environment.DIRECTORY_MUSIC}/HOPE"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, output.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("HOPE could not create a media library entry.")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { destination ->
                output.file.inputStream().use { source -> source.copyTo(destination) }
            } ?: throw IOException("HOPE could not open the media library entry.")
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (contentResolver.update(uri, published, null, null) != 1) {
                throw IOException("HOPE could not finish saving the media file.")
            }
            return DownloadedMedia(uri, displayName, output.mimeType)
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveInAppFiles(
        output: OutputMedia,
        displayName: String
    ): DownloadedMedia {
        val directory = File(appContext.filesDir, "media_downloads").apply {
            if (!exists() && !mkdirs()) throw IOException("HOPE could not create its media folder.")
        }
        val destination = File(directory, displayName)
        output.file.inputStream().use { source ->
            FileOutputStream(destination).use { target -> source.copyTo(target) }
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            destination
        )
        return DownloadedMedia(uri, displayName, output.mimeType)
    }

    private fun sourceName(url: String, contentDisposition: String?): String {
        val headerName = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
        val urlName = try {
            URI(url).rawPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        } catch (_: URISyntaxException) {
            null
        }
        return (headerName ?: urlName ?: "HOPE_media")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ', '_')
            .take(100)
            .ifBlank { "HOPE_media" }
    }

    private fun extensionForVideo(mimeType: String): String = when (mimeType) {
        "video/mp4", "video/x-m4v" -> "mp4"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> throw MediaDownloadException("HOPE does not support this video container yet.")
    }

    private fun extensionForAudio(mimeType: String): String = when (mimeType) {
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        "audio/3gpp" -> "3gp"
        "audio/amr" -> "amr"
        "audio/webm" -> "webm"
        else -> throw MediaDownloadException("HOPE does not support this audio format yet.")
    }

    private data class TemporaryMedia(
        val file: File,
        val mimeType: String,
        val sourceName: String
    )

    private data class MediaTracks(
        val videoTrackIndex: Int,
        val audioTrackIndex: Int,
        val audioMimeType: String?
    )

    private data class OutputMedia(
        val file: File,
        val mimeType: String,
        val extension: String,
        val sourceName: String
    )

    private companion object {
        const val progressIntervalBytes = 512L * 1024L
        val muxableAudioMimeTypes = setOf(
            "audio/mp4a-latm",
            "audio/3gpp",
            "audio/amr-nb",
            "audio/amr-wb"
        )
    }
}
