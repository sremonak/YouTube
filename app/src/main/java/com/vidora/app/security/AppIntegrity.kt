package com.vidora.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.vidora.app.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Defence in depth, NOT DRM or an extraction-proof boundary. A patched client can remove checks.
 * In particular, the repository's public fallback key cannot establish exclusive authorship.
 * Source rebuilds remain permitted under GPLv3; release trust identifies this binary distribution.
 */
object AppIntegrity {
    @Suppress("DEPRECATION")
    private val flags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certificates.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun trusted(info: PackageInfo): Boolean = IntegrityPolicy.accepts(
        info.packageName,
        ((info.applicationInfo?.flags ?: ApplicationInfo.FLAG_DEBUGGABLE) and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        signers(info), BuildConfig.TRUSTED_SIGNER_SHA256,
    )

    /** Runs before Graph/account/player initialization, for every process entry (not just login). */
    @Suppress("DEPRECATION")
    fun enforce(context: Context) {
        if (BuildConfig.DEBUG) return // Local development/emulator builds are deliberately separate.
        val valid = runCatching {
            context.packageName == "com.vidora.app" &&
                trusted(context.packageManager.getPackageInfo(context.packageName, flags))
        }.getOrDefault(false)
        check(valid) { "This installation could not be verified. Install an original Vidora release." }
    }

    /** Validate before ANY installer path: package, signer, non-debuggable flag and upgrade version. */
    @Suppress("DEPRECATION")
    fun verifyUpdate(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Update package is invalid")
        check(trusted(info)) { "Update authenticity verification failed" }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(code > BuildConfig.VERSION_CODE) { "Update version is not newer" }
    }
}
