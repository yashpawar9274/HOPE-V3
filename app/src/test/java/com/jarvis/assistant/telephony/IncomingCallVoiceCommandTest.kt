package com.jarvis.assistant.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingCallVoiceCommandTest {
    @Test
    fun recognizesClearEnglishAndHindiAnswerCommands() {
        assertEquals(IncomingCallVoiceCommand.ANSWER, IncomingCallVoiceCommandParser.parse("Please answer"))
        assertEquals(IncomingCallVoiceCommand.ANSWER, IncomingCallVoiceCommandParser.parse("Call uthao"))
        assertEquals(IncomingCallVoiceCommand.ANSWER, IncomingCallVoiceCommandParser.parse("कॉल उठाओ"))
    }

    @Test
    fun recognizesRejectCommandsAndPrioritizesRejectWhenBothAreHeard() {
        assertEquals(IncomingCallVoiceCommand.REJECT, IncomingCallVoiceCommandParser.parse("Please reject"))
        assertEquals(IncomingCallVoiceCommand.REJECT, IncomingCallVoiceCommandParser.parse("Call kaat do"))
        assertEquals(
            IncomingCallVoiceCommand.REJECT,
            IncomingCallVoiceCommandParser.parse("Do not answer, reject the call")
        )
    }

    @Test
    fun unclearOrEmptySpeechDoesNotTriggerCallControl() {
        assertNull(IncomingCallVoiceCommandParser.parse(""))
        assertNull(IncomingCallVoiceCommandParser.parse("hello"))
        assertNull(IncomingCallVoiceCommandParser.parse("haan"))
        assertNull(IncomingCallVoiceCommandParser.parse("Don't answer"))
        assertNull(IncomingCallVoiceCommandParser.parse("मत उठाओ"))
    }
}
