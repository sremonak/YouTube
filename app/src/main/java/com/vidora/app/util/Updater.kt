package com.vidora.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vidora.app.BuildConfig
import com.vidora.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
    val sizeBytes: Long,
)

/** Checks this project's GitHub Releases for a newer APK and installs it. */
object Updater {
    const val REPO = "sremonak/YouTube"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        Graph.http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null // no releases published yet
            if (!r.isSuccessful) throw IOException("GitHub HTTP ${r.code}")
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            if (!Versions.isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
            val asset = (o["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            UpdateInfo(
                version = tag,
                notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4000),
                apkUrl = asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
                pageUrl = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$REPO/releases",
                sizeBytes = (asset as? JsonObject)?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
            )
        }
    }

    /** Downloads the APK into the cache, reporting progress 0..1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: throw IOException("This release has no APK attached")
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "Vidora-${info.version}.apk")
        Graph.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        out
    }

    /** True if Android will let us open the package installer (Android 8+ needs a per-app grant). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Installs [apk] through a PackageInstaller session. On Android 12+ the update is applied
     * silently once Vidora itself installed the current version (it is then the "installer of
     * record"); otherwise Android shows its one-tap confirmation. Falls back to the classic
     * installer intent if the session API fails.
     */
    fun install(context: Context, apk: File) {
        runCatching { installSession(context, apk) }.onFailure {
            android.util.Log.w("Updater", "session install failed, using installer intent", it)
            installWithIntent(context, apk)
        }
    }

    fun installWithIntent(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun installSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("vidora.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val pi = android.app.PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION).putExtra("apk", apk.absolutePath),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }

    /** Last check result, shared by the About screen, the startup dialog and the worker. */
    val state = kotlinx.coroutines.flow.MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Check → (optionally) download → install, reporting progress through [state]. */
    suspend fun checkAndMaybeInstall(context: Context, install: Boolean): UpdateInfo? {
        state.value = UpdateState.Checking
        val info = runCatching { check() }.getOrElse { state.value = UpdateState.Failed(it.message ?: "Update check failed"); return null }
        if (info == null) { state.value = UpdateState.UpToDate(System.currentTimeMillis()); return null }
        state.value = UpdateState.Available(info)
        if (install) downloadAndInstall(context, info)
        return info
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (!canInstall(context)) { state.value = UpdateState.NeedsPermission(info); return }
        state.value = UpdateState.Downloading(info, 0f)
        runCatching { download(context, info) { p -> state.value = UpdateState.Downloading(info, p) } }
            .onSuccess { apk ->
                state.value = UpdateState.Installing(info)
                withContext(Dispatchers.Main) { install(context, apk) }
            }
            .onFailure { state.value = UpdateState.Failed(it.message ?: "Download failed", info) }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val at: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}
