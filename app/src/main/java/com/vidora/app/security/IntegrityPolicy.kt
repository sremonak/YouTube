package com.vidora.app.security

/** Pure policy, shared by startup and downloaded-APK verification. No network dependency. */
internal object IntegrityPolicy {
    fun accepts(packageName: String, debuggable: Boolean, signers: List<String>, expected: String): Boolean =
        packageName == "com.vidora.app" && !debuggable &&
            expected.matches(Regex("[0-9a-f]{64}")) && signers.size == 1 && signers.single() == expected
}
