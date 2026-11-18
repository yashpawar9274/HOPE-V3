package com.jarvis.assistant.media

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MediaDownloadPolicyTest {
    @Test
    fun extractorFlagsAreTranslatedToCodecFlagsAndEncryptedSamplesFailClosed() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            MediaSampleFlagPolicy.toCodecFlags(MediaExtractor.SAMPLE_FLAG_SYNC)
        )
        assertEquals(
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            MediaSampleFlagPolicy.toCodecFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        )
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            MediaSampleFlagPolicy.toCodecFlags(
                MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
            )
        )
    }

    @Test(expected = MediaDownloadException::class)
    fun encryptedExtractorSamplesAreNotWrittenToTheMuxer() {
        MediaSampleFlagPolicy.toCodecFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
    }

    @Test
    fun extractsOnlyValidHttpsLinksFromSharedText() {
        assertEquals(
            "https://media.example/song.mp3",
            MediaDownloadPolicy.extractHttpsUrl("Listen: https://media.example/song.mp3.")
        )
        assertNull(MediaDownloadPolicy.extractHttpsUrl("http://media.example/song.mp3"))
        assertNull(MediaDownloadPolicy.extractHttpsUrl("No link here"))
    }

    @Test
    fun rejectsCredentialsLocalHostsAndPrivateIpAddresses() {
        assertNull(MediaDownloadPolicy.validateHttpsUrl("https://user:pass@media.example/video.mp4"))
        assertNull(MediaDownloadPolicy.validateHttpsUrl("https://localhost/video.mp4"))
        assertNull(MediaDownloadPolicy.validateHttpsUrl("https://192.168.1.10/video.mp4"))
        assertNull(MediaDownloadPolicy.validateHttpsUrl("http://media.example/video.mp4"))
    }

    @Test
    fun allowsPublicHttpsLinkAndDropsFragment() {
        assertEquals(
            "https://media.example/video.mp4?token=redacted",
            MediaDownloadPolicy.validateHttpsUrl("https://media.example/video.mp4?token=redacted#player")
        )
    }

    @Test
    fun blocksNonPublicNetworkAddresses() {
        assertFalse(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("10.2.3.4")))
        assertFalse(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("169.254.1.2")))
        assertFalse(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("fd00::1")))
        assertFalse(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("2001:db8::1")))
        assertTrue(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(MediaDownloadPolicy.isPublicInternetAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun sharedLinkIsValidatedAndAuthenticationHandoffIsOneShot() {
        SharedMediaDownloadSession.setSharedUrl(
            "https://media.example/video.mp4",
            shouldOpenAfterAuthentication = true
        )
        assertEquals("https://media.example/video.mp4", SharedMediaDownloadSession.getSharedUrl())
        assertEquals(
            "https://media.example/video.mp4",
            SharedMediaDownloadSession.consumeOpenAfterAuthentication()
        )
        assertNull(SharedMediaDownloadSession.consumeOpenAfterAuthentication())

        SharedMediaDownloadSession.setSharedUrl("http://media.example/video.mp4")
        assertNull(SharedMediaDownloadSession.getSharedUrl())
        SharedMediaDownloadSession.clearSharedUrl()
    }

    @Test
    fun sanitizesNamesAndKeepsTheRequiredExtension() {
        assertEquals(
            "video name.mp4",
            MediaDownloadPolicy.safeDisplayName("../../video name.mp4?token=x", "HOPE_video", "mp4")
        )
        assertEquals(
            "recording.m4a",
            MediaDownloadPolicy.safeDisplayName("recording.mp3", "HOPE_audio", "m4a")
        )
    }

    @Test
    fun infersOnlyKnownMediaTypesFromFileExtensions() {
        assertEquals("video/mp4", MediaDownloadPolicy.inferMediaMimeType("shared_video.mp4"))
        assertEquals("audio/mpeg", MediaDownloadPolicy.inferMediaMimeType("shared_song.mp3"))
        assertNull(MediaDownloadPolicy.inferMediaMimeType("playlist.m3u8"))
        assertNull(MediaDownloadPolicy.inferMediaMimeType("unknown.bin"))
    }
}
