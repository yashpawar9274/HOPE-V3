package com.jarvis.assistant.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartClipboardVoiceCommandTest {

    @Test
    fun recognizesSupportedClipboardRequestsWithOptionalWakeWord() {
        assertEquals(
            SmartClipboardRequest.EXPLAIN,
            SmartClipboardVoiceCommandParser.parse("HOPE, maine jo copy kiya hai uska matlab batao.")
        )
        assertEquals(
            SmartClipboardRequest.EXPLAIN,
            SmartClipboardVoiceCommandParser.parse("What did I copy?")
        )
        assertEquals(
            SmartClipboardRequest.SUGGEST_REPLY,
            SmartClipboardVoiceCommandParser.parse("Iska reply kya dena chahiye?")
        )
        assertEquals(
            SmartClipboardRequest.TRANSLATE,
            SmartClipboardVoiceCommandParser.parse("Translate my copied content.")
        )
        assertEquals(
            SmartClipboardRequest.EXPLAIN_CODE,
            SmartClipboardVoiceCommandParser.parse("Explain this copied code.")
        )
        assertEquals(
            SmartClipboardRequest.EXPLAIN_IMAGE,
            SmartClipboardVoiceCommandParser.parse("Copied image explain karo.")
        )
    }

    @Test
    fun doesNotClassifyUnrelatedUtterancesAsClipboardRequests() {
        assertNull(SmartClipboardVoiceCommandParser.parse("What should I cook today?"))
        assertNull(SmartClipboardVoiceCommandParser.parse("Copy this text"))
    }
}
