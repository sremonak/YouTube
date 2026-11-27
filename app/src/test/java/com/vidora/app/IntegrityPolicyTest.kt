package com.vidora.app

import com.vidora.app.security.IntegrityPolicy
import org.junit.Assert.*
import org.junit.Test

class IntegrityPolicyTest {
    private val certificate = "a".repeat(64)

    @Test fun originalReleaseAccepted() {
        assertTrue(IntegrityPolicy.accepts("com.vidora.app", false, listOf(certificate), certificate))
    }

    @Test fun resignedOrClonedPackageRejected() {
        assertFalse(IntegrityPolicy.accepts("com.vidora.app", false, listOf("b".repeat(64)), certificate))
        assertFalse(IntegrityPolicy.accepts("com.clone.app", false, listOf(certificate), certificate))
    }

    @Test fun missingOrMultipleSignersRejected() {
        assertFalse(IntegrityPolicy.accepts("com.vidora.app", false, emptyList(), certificate))
        assertFalse(IntegrityPolicy.accepts("com.vidora.app", false, listOf(certificate, certificate), certificate))
    }

    @Test fun debuggableOrMalformedPinRejected() {
        assertFalse(IntegrityPolicy.accepts("com.vidora.app", true, listOf(certificate), certificate))
        assertFalse(IntegrityPolicy.accepts("com.vidora.app", false, listOf(""), ""))
    }

    @Test fun samePublicKeyCannotDistinguishAnAttacker() {
        // Explicitly documents the fallback key's limitation instead of claiming anti-clone DRM.
        assertTrue(IntegrityPolicy.accepts("com.vidora.app", false, listOf(certificate), certificate))
    }
}
