package com.vidora.app

import com.vidora.app.download.Mp4Tagger
import com.vidora.app.download.Muxer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

class DownloadPostProcessTest {

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }.toByteArray()
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeInt(body.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }
        }.toByteArray()
    }

    private fun stco(offset: Int) = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset).array())

    private fun find(bytes: ByteArray, type: String, from: Int = 0): Int {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        for (i in from until bytes.size - 3) if (bytes[i] == t[0] && bytes[i + 1] == t[1] && bytes[i + 2] == t[2] && bytes[i + 3] == t[3]) return i - 4
        return -1
    }

    @Test
    fun tagsMoovBeforeMdatAndShiftsChunkOffsets() {
        val ftyp = box("ftyp", "isom".toByteArray(), ByteArray(4))
        val payload = "SAMPLEDATA".toByteArray()
        fun moov(off: Int) = box("moov", box("mvhd", ByteArray(100)), box("trak", box("mdia", box("minf", box("stbl", stco(off))))))
        val moovSize = moov(0).size
        val dataOffset = ftyp.size + moovSize + 8
        val file = File.createTempFile("tag", ".mp4")
        file.writeBytes(ftyp + moov(dataOffset) + box("mdat", payload))

        Mp4Tagger.tag(file, Mp4Tagger.Tags(title = "Hello", artist = "Channel", cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)))
        val out = file.readBytes()
        val mdat = find(out, "mdat")
        val stcoAt = find(out, "stco")
        val newOffset = ByteBuffer.wrap(out, stcoAt + 16, 4).int
        assertEquals(mdat + 8, newOffset)
        assertArrayEquals(payload, out.copyOfRange(newOffset, newOffset + payload.size))
        assertTrue(find(out, "ilst") in 0 until mdat)
        assertTrue(String(out, Charsets.UTF_8).contains("Hello"))
        file.delete()
    }

    @Test
    fun tagsMoovAtEndWithoutTouchingData() {
        val ftyp = box("ftyp", "isom".toByteArray(), ByteArray(4))
        val mdat = box("mdat", "DATA".toByteArray())
        val moov = box("moov", box("mvhd", ByteArray(100)), box("udta", box("junk", ByteArray(3))), box("trak", box("mdia", box("minf", box("stbl", stco(ftyp.size + 8))))))
        val file = File.createTempFile("tag", ".m4a")
        file.writeBytes(ftyp + mdat + moov)
        Mp4Tagger.tag(file, Mp4Tagger.Tags(title = "T"))
        val out = file.readBytes()
        assertArrayEquals(ftyp + mdat, out.copyOfRange(0, ftyp.size + mdat.size))
        assertEquals(ftyp.size + 8, ByteBuffer.wrap(out, find(out, "stco") + 16, 4).int)
        assertEquals(-1, find(out, "junk"))
        file.delete()
    }

    @Test
    fun cutRangesMergeAndShift() {
        val r = Muxer.normalize(listOf(Muxer.Cut(10, 20), Muxer.Cut(15, 30), Muxer.Cut(50, 60), Muxer.Cut(5, 5)))
        assertEquals(listOf(Muxer.Cut(10, 30), Muxer.Cut(50, 60)), r)
        assertEquals(0L, Muxer.shiftFor(5, r))
        assertNull(Muxer.shiftFor(25, r))
        assertEquals(20L, Muxer.shiftFor(30, r))
        assertEquals(30L, Muxer.shiftFor(70, r))
    }
}
