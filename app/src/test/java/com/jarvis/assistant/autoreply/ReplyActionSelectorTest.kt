package com.jarvis.assistant.autoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyActionSelectorTest {
    @Test
    fun semanticReplyWinsOverOtherFreeFormActions() {
        val selected = ReplyActionSelector.selectIndex(
            listOf(
                ReplyActionCandidate(0, true, false, "Search"),
                ReplyActionCandidate(1, true, true, "Quick reply")
            )
        )

        assertEquals(1, selected)
    }

    @Test
    fun ambiguousReplyActionsAreRejected() {
        val selected = ReplyActionSelector.selectIndex(
            listOf(
                ReplyActionCandidate(0, true, false, "Reply privately"),
                ReplyActionCandidate(1, true, false, "Reply in group")
            )
        )

        assertNull(selected)
    }

    @Test
    fun unrelatedFreeFormActionIsUsedOnlyWhenItIsTheOnlyCandidate() {
        assertEquals(
            3,
            ReplyActionSelector.selectIndex(
                listOf(ReplyActionCandidate(3, true, false, "Other"))
            )
        )
        assertNull(
            ReplyActionSelector.selectIndex(
                listOf(
                    ReplyActionCandidate(1, true, false, "Other"),
                    ReplyActionCandidate(2, true, false, "Open")
                )
            )
        )
    }

    @Test
    fun nonFreeFormActionsAreNeverSelected() {
        assertNull(
            ReplyActionSelector.selectIndex(
                listOf(ReplyActionCandidate(0, false, true, "Reply"))
            )
        )
    }
}
