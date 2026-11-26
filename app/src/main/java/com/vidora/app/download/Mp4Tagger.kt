package com.vidora.app.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes iTunes-style metadata (title, artist, comment, date, cover art) into an MP4 / M4A file,
 * the way yt-dlp's `--embed-metadata --embed-thumbnail` does — without FFmpeg.
 *
 * Works whether `moov` sits before `mdat` (chunk offsets in stco/co64 are shifted) or after it
 * (the file is only rewritten from `moov` onwards).
 */
object Mp4Tagger {

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val comment: String? = null,
        val date: String? = null,
        val description: String? = null,
        val cover: ByteArray? = null,
    )

    private class Box(val type: String, val offset: Long, val size: Long, val header: Int)

    fun tag(file: File, tags: Tags) {
        val boxes = RandomAccessFile(file, "r").use { topLevel(it) }
        val moov = boxes.firstOrNull { it.type == "moov" } ?: error("Not an MP4 file (no moov)")
        require(moov.size < 64L * 1024 * 1024) { "moov box too large" }
        val oldMoov = ByteArray(moov.size.toInt())
        RandomAccessFile(file, "r").use { it.seek(moov.offset); it.readFully(oldMoov) }
        val moovBeforeData = boxes.any { it.type == "mdat" && it.offset > moov.offset }
        val newMoovBody = rebuildMoov(oldMoov, moov.header, tags)
        val delta = newMoovBody.size.toLong() - oldMoov.size
        if (moovBeforeData && delta != 0L) shiftChunkOffsets(newMoovBody, 8, newMoovBody.size, delta)

        if (!moovBeforeData && boxes.last() === moov) {
            // Common MediaMuxer layout: moov is the last box → rewrite just the tail.
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(moov.offset)
                raf.seek(moov.offset)
                raf.write(newMoovBody)
            }
            return
        }
        // General case: copy everything into a temp file with the new moov in place.
        val tmp = File(file.parentFile, file.name + ".tag")
        RandomAccessFile(file, "r").use { src ->
            tmp.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                for (b in boxes) {
                    if (b === moov) { out.write(newMoovBody); continue }
                    src.seek(b.offset)
                    var left = b.size
                    while (left > 0) {
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true); tmp.delete()
        }
    }

    private fun topLevel(raf: RandomAccessFile): List<Box> {
        val out = mutableListOf<Box>()
        var pos = 0L
        val len = raf.length()
        while (pos + 8 <= len) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = raf.readLong(); header = 16 }
            if (size == 0L) size = len - pos
            if (size < header) break
            out += Box(type, pos, size, header)
            pos += size
        }
        return out
    }

    /** moov with any existing `udta` dropped and a fresh one (meta/hdlr/ilst) appended. */
    private fun rebuildMoov(moov: ByteArray, header: Int, tags: Tags): ByteArray {
        val body = ByteArrayOutputStream()
        var p = header
        while (p + 8 <= moov.size) {
            val size = readU32(moov, p).toInt().let { if (it == 0) moov.size - p else it }
            if (size < 8 || p + size > moov.size) break
            val type = String(moov, p + 4, 4, Charsets.ISO_8859_1)
            if (type != "udta") body.write(moov, p, size)
            p += size
        }
        body.write(udta(tags))
        val bytes = body.toByteArray()
        return box("moov", bytes)
    }

    private fun udta(t: Tags): ByteArray {
        val items = ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (value.isNullOrBlank()) return
            items.write(box(type, dataAtom(1, value.toByteArray(Charsets.UTF_8))))
        }
        text("\u00A9nam", t.title)
        text("\u00A9ART", t.artist)
        text("aART", t.artist)
        text("\u00A9cmt", t.comment)
        text("\u00A9day", t.date)
        text("desc", t.description?.take(255))
        text("ldes", t.description)
        text("\u00A9too", "Vidora")
        t.cover?.let { c ->
            val png = c.size > 4 && c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte()
            items.write(box("covr", dataAtom(if (png) 14 else 13, c)))
        }
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdir".toByteArray(Charsets.ISO_8859_1) +
            "appl".toByteArray(Charsets.ISO_8859_1) + ByteArray(8) + ByteArray(1))
        val meta = box("meta", ByteArray(4) + hdlr + box("ilst", items.toByteArray()))
        return box("udta", meta)
    }

    private fun dataAtom(typeIndicator: Int, value: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply { writeInt(typeIndicator); writeInt(0); write(value) }
        return box("data", b.toByteArray())
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(payload.size + 8)
        DataOutputStream(b).apply {
            writeInt(payload.size + 8)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return b.toByteArray()
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

    /** Adds [delta] to every chunk offset in stco / co64 boxes below [from]..[to]. */
    internal fun shiftChunkOffsets(buf: ByteArray, from: Int, to: Int, delta: Long) {
        var p = from
        while (p + 8 <= to) {
            val size = readU32(buf, p).toInt()
            if (size < 8 || p + size > to) return
            val type = String(buf, p + 4, 4, Charsets.ISO_8859_1)
            when (type) {
                in containers -> shiftChunkOffsets(buf, p + 8, p + size, delta)
                "stco" -> {
                    val count = readU32(buf, p + 12).toInt()
                    for (i in 0 until count) {
                        val at = p + 16 + i * 4
                        writeU32(buf, at, readU32(buf, at) + delta)
                    }
                }
                "co64" -> {
                    val count = readU32(buf, p + 12).toInt()
                    for (i in 0 until count) {
                        val at = p + 16 + i * 8
                        val v = (readU32(buf, at) shl 32) or readU32(buf, at + 4)
                        val nv = v + delta
                        writeU32(buf, at, nv ushr 32); writeU32(buf, at + 4, nv and 0xffffffffL)
                    }
                }
            }
            p += size
        }
    }

    private fun readU32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun writeU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte(); b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte()
    }
}
