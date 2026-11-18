package com.jarvis.assistant.automation.command

import com.jarvis.assistant.media.MediaDownloadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDownloadCommandParserTest {
    @Test
    fun explicitVideoDownloadPhrasesSelectVideoMode() {
        assertEquals(
            JarvisCommand.DownloadSharedMedia(MediaDownloadKind.VIDEO),
            CommandParser.parse("download this video").command
        )
        assertEquals(
            JarvisCommand.DownloadSharedMedia(MediaDownloadKind.VIDEO),
            CommandParser.parse("mujhe video chahiye").command
        )
    }

    @Test
    fun songRequestSelectsAudioExtractionMode() {
        assertEquals(
            JarvisCommand.DownloadSharedMedia(MediaDownloadKind.AUDIO),
            CommandParser.parse("iska song chahiye").command
        )
        assertEquals(
            JarvisCommand.DownloadSharedMedia(MediaDownloadKind.AUDIO),
            CommandParser.parse("extract audio from this video").command
        )
    }

    @Test
    fun ordinaryPlaybackRequestsAreNotTreatedAsDownloads() {
        assertNull(CommandParser.parse("play a song").command)
        assertNull(CommandParser.parse("I like this video").command)
    }
}
