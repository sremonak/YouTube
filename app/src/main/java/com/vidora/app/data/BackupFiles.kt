package com.vidora.app.data

import java.io.InputStream

object BackupFiles {
    const val MAX_BYTES = 8 * 1024 * 1024
    /** Reject before parsing or mutating the library, including providers with no reported size. */
    fun readText(input: InputStream, limit: Int = MAX_BYTES): String {
        require(limit in 1..MAX_BYTES)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Import cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size() + count <= limit) { "File is too large (maximum ${limit / 1024} KiB)" }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
