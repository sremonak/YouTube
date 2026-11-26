package com.vidora.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/** Publishes finished downloads to the shared Movies/Vidora and Music/Vidora folders. */
object Storage {
    private const val FOLDER = "Vidora"

    fun publish(context: Context, source: File, record: DownloadRecord): Uri {
        val isAudio = record.kind == DownloadKind.AUDIO
        val mime = record.mime ?: if (isAudio) "audio/mp4" else "video/mp4"
        // User-chosen folder (e.g. SD card) via the Storage Access Framework
        val tree = com.vidora.app.Graph.settingsState.value.downloadFolder
        if (tree.isNotBlank()) {
            runCatching { return publishToTree(context, source, record, mime, Uri.parse(tree)) }
            // fall through to the default location if the folder is gone / permission revoked
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, record.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + FOLDER
                )
                put(MediaStore.MediaColumns.TITLE, record.video.title)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: error("Could not create media entry")
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: error("Could not open output")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                ), FOLDER
            ).apply { mkdirs() }
            var target = File(dir, record.fileName)
            var n = 1
            while (target.exists()) target = File(dir, record.fileName.substringBeforeLast('.') + " ($n)." + record.fileName.substringAfterLast('.')).also { n++ }
            source.copyTo(target, overwrite = false)
            source.delete()
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            target.toUri()
        }
    }

    /** Saves a small companion file (subtitles) into Download/Vidora — any MIME type is allowed there. */
    fun publishSidecar(context: Context, bytes: ByteArray, name: String, mime: String): Uri {
        val tree = com.vidora.app.Graph.settingsState.value.downloadFolder
        if (tree.isNotBlank()) runCatching {
            val t = Uri.parse(tree)
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("create failed")
            resolver.openOutputStream(doc)?.use { it.write(bytes) }
            return doc
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create file")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            val f = File(dir, name).apply { writeBytes(bytes) }
            f.toUri()
        }
    }

    private fun publishToTree(context: Context, source: File, record: DownloadRecord, mime: String, tree: Uri): Uri {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, record.fileName)
            ?: error("Could not create file in the chosen folder")
        try {
            resolver.openOutputStream(doc)?.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: error("Could not open output")
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        source.delete()
        return doc
    }

    /** Human-readable name of a chosen SAF folder, e.g. "SD card › Vidora". */
    fun folderLabel(tree: String): String {
        if (tree.isBlank()) return "Movies/Vidora · Music/Vidora"
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return tree
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val vol = if (volume == "primary") "Internal storage" else "SD card"
        return if (path.isBlank()) vol else "$vol › ${path.replace("/", " › ")}"
    }

    /** A uri that other apps can open (content:// on every API level). */
    fun shareableUri(context: Context, record: DownloadRecord): Uri? {
        val uri = record.uri?.toUri() ?: return null
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            return FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
        return uri
    }

    fun delete(context: Context, record: DownloadRecord) {
        val uri = record.uri?.toUri() ?: return
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete()
            else if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
            else context.contentResolver.delete(uri, null, null)
        }
    }

    fun exists(context: Context, record: DownloadRecord): Boolean {
        val uri = record.uri?.toUri() ?: return false
        return runCatching {
            if (uri.scheme == "file") File(uri.path!!).exists()
            else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
