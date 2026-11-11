package com.jarvis.assistant.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardContentDetectorTest {

    @Test
    fun classifiesCopiedText() {
        assertEquals(
            ClipboardTextCategory.QUESTION,
            ClipboardContentDetector.classifyText("How does this work?")
        )
        assertEquals(
            ClipboardTextCategory.CONVERSATION,
            ClipboardContentDetector.classifyText("Hey, thanks for checking in.")
        )
        assertEquals(
            ClipboardTextCategory.CODE,
            ClipboardContentDetector.classifyText("fun main() { println(\"Hi\") }")
        )
        assertEquals(
            ClipboardTextCategory.DOCUMENT,
            ClipboardContentDetector.classifyText("First line\nSecond line\nThird line\nFourth line\nFifth line")
        )
        assertEquals(
            ClipboardTextCategory.GENERAL,
            ClipboardContentDetector.classifyText("The sky is blue.")
        )
    }

    @Test
    fun blocksSensitiveClipboardTextWithoutOverblockingOrdinaryMessages() {
        assertTrue(ClipboardPrivacyGuard.isSensitive("OTP: 314159"))
        assertTrue(ClipboardPrivacyGuard.isSensitive("password=correct-horse-battery"))
        assertTrue(ClipboardPrivacyGuard.isSensitive("password is secret-value"))
        assertTrue(ClipboardPrivacyGuard.isSensitive("api_key=1234567890abcdef"))
        assertTrue(ClipboardPrivacyGuard.isSensitive("4111 1111 1111 1111"))
        assertFalse(ClipboardPrivacyGuard.isSensitive("Let's pin this for later."))
        assertFalse(ClipboardPrivacyGuard.isSensitive("The answer is 42."))
    }
}
