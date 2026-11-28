package com.vidora.app

import com.vidora.app.util.UpdatePromptPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class UpdatePromptPolicyTest {
    @Test fun freshProcessChecksWithoutOldPersistedThrottle() {
        assertTrue(Policy.shouldCheck(100, null, null))
    }

    @Test fun failureRetriesInThirtySecondsNotThirtyMinutes() {
        assertFalse(Policy.shouldCheck(29_999, null, 0))
        assertTrue(Policy.shouldCheck(30_000, null, 0))
    }

    @Test fun successThrottlesNetworkButNotAvailablePrompt() {
        assertFalse(Policy.shouldCheck(60_000, 0, 0))
        assertTrue(Policy.shouldCheck(300_000, 0, 0))
        // A known update is presented independently of the network throttle.
        assertTrue(Policy.shouldPrompt("v1.0.102", null, 0, 60_000))
    }

    @Test fun laterOnlySnoozesThatVersion() {
        assertFalse(Policy.shouldPrompt("v1.0.102", "v1.0.102", 900_000, 100))
        assertTrue(Policy.shouldPrompt("v1.0.104", "v1.0.102", 900_000, 100))
    }

    @Test fun snoozeExpiresAtBoundary() {
        assertFalse(Policy.shouldPrompt("v1.0.102", "v1.0.102", 900_000, 899_999))
        assertTrue(Policy.shouldPrompt("v1.0.102", "v1.0.102", 900_000, 900_000))
    }

    @Test fun monotonicClockResetDoesNotSuppressChecks() {
        assertTrue(Policy.shouldCheck(10, 900_000, 900_000))
    }
}
