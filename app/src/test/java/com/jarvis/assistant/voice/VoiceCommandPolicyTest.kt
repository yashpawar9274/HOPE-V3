package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandPolicyTest {

    @Test
    fun parsesExplicitClipboardCommandsWithoutInventingContent() {
        assertEquals(
            VoiceClipboardCommand(VoiceClipboardAction.COPY_SELECTED_TEXT),
            VoiceClipboardCommandParser.parse("HOPE, copy the selected text")
        )
        assertEquals(
            VoiceClipboardCommand(VoiceClipboardAction.PASTE),
            VoiceClipboardCommandParser.parse("Hey HOPE, paste the copied link")
        )
        assertEquals(
            VoiceClipboardCommand(VoiceClipboardAction.SELECT_ALL),
            VoiceClipboardCommandParser.parse("Hope select all")
        )
        assertNull(VoiceClipboardCommandParser.parse("copy something"))
    }

    @Test
    fun preservesExplicitTextAndCompleteUrls() {
        val textCommand = VoiceClipboardCommandParser.parse(
            "HOPE copy this text: Keep every word, punctuation, and ending."
        )
        assertEquals(VoiceClipboardAction.COPY_TEXT, textCommand?.action)
        assertEquals(
            "Keep every word, punctuation, and ending.",
            textCommand?.value
        )

        val url = "https://example.com/a/long/path?first=1&second=two#complete"
        val linkCommand = VoiceClipboardCommandParser.parse("Hope copy this link $url")
        assertEquals(VoiceClipboardAction.COPY_LINK, linkCommand?.action)
        assertEquals(url, linkCommand?.value)
        assertTrue(VoiceClipboardCommandParser.isValidHttpUrl(url))
        assertEquals(
            "$url.",
            VoiceClipboardCommandParser.parse("Hope copy this link $url.")?.value
        )
        assertFalse(VoiceClipboardCommandParser.isValidHttpUrl("javascript:alert(1)"))
    }

    @Test
    fun detectsSensitiveClipboardContent() {
        assertTrue(VoiceClipboardCommandParser.isSensitive("123456"))
        assertTrue(VoiceClipboardCommandParser.isSensitive("Use this code: 482901"))
        assertTrue(VoiceClipboardCommandParser.isSensitive("4111 1111 1111 1111"))
        assertTrue(VoiceClipboardCommandParser.isSensitive("ordinary text", "password input"))
        assertFalse(VoiceClipboardCommandParser.isSensitive("The link is https://example.com/path"))
    }

    @Test
    fun wakeRequiresExactHeyHopeAndConfiguredConfidence() {
        assertTrue(
            WakeWordConfidence.isConfirmed(
                listOf("hey", "hope"),
                listOf(0.84, 0.91),
                0.80f
            )
        )
        assertFalse(
            WakeWordConfidence.isConfirmed(
                listOf("hope"),
                listOf(0.99),
                0.80f
            )
        )
        assertFalse(
            WakeWordConfidence.isConfirmed(
                listOf("hello", "hope"),
                listOf(0.98, 0.98),
                0.80f
            )
        )
        assertFalse(
            WakeWordConfidence.isConfirmed(
                listOf("hey", "hope"),
                listOf(0.79, 0.99),
                0.80f
            )
        )
    }

    @Test
    fun standbyRequiresAnExplicitStandbyRequest() {
        assertTrue(StandbyCommandPolicy.isExplicitCommand("HOPE, activate standby mode"))
        assertTrue(StandbyCommandPolicy.isExplicitCommand("Hey HOPE, go to standby"))
        assertFalse(StandbyCommandPolicy.isExplicitCommand("What does standby mode mean?"))
        assertFalse(StandbyCommandPolicy.isExplicitCommand("Don't go to standby"))
        assertFalse(StandbyCommandPolicy.isExplicitCommand("Minimize the app"))
    }

    @Test
    fun deviceUnlockRequiresAnExplicitPhoneUnlockRequest() {
        assertTrue(DeviceUnlockCommandPolicy.isExplicitCommand("Hey HOPE, unlock my phone"))
        assertTrue(DeviceUnlockCommandPolicy.isExplicitCommand("unlock the device"))
        assertFalse(DeviceUnlockCommandPolicy.isExplicitCommand("How do I unlock my phone?"))
        assertFalse(DeviceUnlockCommandPolicy.isExplicitCommand("unlock the HOPE app"))
    }
}
