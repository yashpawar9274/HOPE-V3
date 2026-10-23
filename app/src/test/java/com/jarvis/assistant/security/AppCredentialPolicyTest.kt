package com.jarvis.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCredentialPolicyTest {

    @Test
    fun validatesCredentialFormatsAndStrength() {
        assertNull(AppCredentialPolicy.validate(AppCredentialType.PIN, "4826".toCharArray()))
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PIN, "12".toCharArray()) != null)
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PIN, "12a4".toCharArray()) != null)
        assertNull(AppCredentialPolicy.validate(AppCredentialType.PATTERN, "013578".toCharArray()))
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PATTERN, "012".toCharArray()) != null)
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PATTERN, "012230".toCharArray()) != null)
        assertNull(AppCredentialPolicy.validate(AppCredentialType.PASSWORD, "Hope2026".toCharArray()))
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PASSWORD, "onlyletters".toCharArray()) != null)
        assertTrue(AppCredentialPolicy.validate(AppCredentialType.PASSWORD, "A1".toCharArray()) != null)
    }

    @Test
    fun hashesWithSaltAndComparesWithoutStoringCredential() {
        val credential = "Hope2026".toCharArray()
        val salt = AppCredentialPolicy.createSalt()
        val verifier = AppCredentialPolicy.createVerifier(credential, salt)
        val otherSaltVerifier = AppCredentialPolicy.createVerifier(
            credential,
            AppCredentialPolicy.createSalt()
        )
        assertTrue(AppCredentialPolicy.matches(credential, salt, verifier))
        assertFalse(AppCredentialPolicy.matches("Wrong2026".toCharArray(), salt, verifier))
        assertFalse(verifier.contentEquals(otherSaltVerifier))
        assertFalse(verifier.contentEquals(credential.concatToString().toByteArray()))
        credential.fill('\u0000')
        salt.fill(0)
        verifier.fill(0)
        otherSaltVerifier.fill(0)
    }

    @Test
    fun lockoutBackoffIsBoundedAndStartsAfterFiveFailures() {
        assertEquals(0L, AppCredentialPolicy.lockoutDurationMillis(4))
        assertEquals(30_000L, AppCredentialPolicy.lockoutDurationMillis(5))
        assertEquals(60_000L, AppCredentialPolicy.lockoutDurationMillis(6))
        assertEquals(15 * 60 * 1000L, AppCredentialPolicy.lockoutDurationMillis(20))
    }

    @Test
    fun appliesBackgroundTimeoutAndLocksFreshProcesses() {
        assertTrue(AppCredentialPolicy.hasTimedOut(0, 0L, 100L, true))
        assertTrue(AppCredentialPolicy.hasTimedOut(5, 0L, 100L, false))
        assertFalse(AppCredentialPolicy.hasTimedOut(5, 10_000L, 309_999L, true))
        assertTrue(AppCredentialPolicy.hasTimedOut(5, 10_000L, 310_000L, true))
    }
}
