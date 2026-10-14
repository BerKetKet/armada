package app.armada.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class FramingTest {

    @Test
    fun streamReturningOneBytePerRead() {
        val input = "{\"type\":\"hello\",\"v\":1}\n{\"type\":\"cancel\",\"id\":\"r1\"}\n"
        val rawStream = ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8))
        val oneByteStream = object : InputStream() {
            override fun read(): Int = rawStream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val ch = rawStream.read()
                if (ch == -1) return -1
                b[off] = ch.toByte()
                return 1
            }
        }

        val reader = FrameReader(oneByteStream)
        assertEquals("{\"type\":\"hello\",\"v\":1}", reader.readFrame())
        assertEquals("{\"type\":\"cancel\",\"id\":\"r1\"}", reader.readFrame())
        assertNull(reader.readFrame())
    }

    @Test
    fun severalFramesPerRead() {
        val input = "frame1\nframe2\nframe3\n"
        val stream = ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8))
        val reader = FrameReader(stream)

        assertEquals("frame1", reader.readFrame())
        assertEquals("frame2", reader.readFrame())
        assertEquals("frame3", reader.readFrame())
        assertNull(reader.readFrame())
    }

    @Test
    fun blankLinesAreSkipped() {
        val input = "\n\n   \n\r\n\t \nframe\n\n\r\n"
        val stream = ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8))
        val reader = FrameReader(stream)

        assertEquals("frame", reader.readFrame())
        assertNull(reader.readFrame())
    }

    @Test
    fun oversizeLineThrowsFrameTooLongException() {
        val line = "a".repeat(100) + "\n"
        val stream = ByteArrayInputStream(line.toByteArray(StandardCharsets.UTF_8))
        val reader = FrameReader(stream, maxBytes = 50)

        try {
            reader.readFrame()
            fail("Oversize frame must throw FrameTooLongException")
        } catch (e: FrameTooLongException) {
            // Expected
        }
    }

    @Test
    fun truncatedLineThrowsTruncatedFrameException() {
        val input = "truncated frame without newline"
        val stream = ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8))
        val reader = FrameReader(stream)

        try {
            reader.readFrame()
            fail("Truncated stream must throw TruncatedFrameException")
        } catch (e: TruncatedFrameException) {
            // Expected
        }
    }

    @Test
    fun invalidUtf8ThrowsMalformedFrameException() {
        // 0xFF 0xFE are invalid leading bytes in UTF-8
        val invalidBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '\n'.code.toByte())
        val stream = ByteArrayInputStream(invalidBytes)
        val reader = FrameReader(stream)

        try {
            reader.readFrame()
            fail("Invalid UTF-8 sequence must throw MalformedFrameException")
        } catch (e: MalformedFrameException) {
            // Expected
        } catch (e: IOException) {
            // CharacterCodingException / IOException is also valid
        }
    }

    @Test
    fun frameWriterAppendsNewlineAndFlushes() {
        val out = ByteArrayOutputStream()
        val writer = FrameWriter(out)

        writer.writeFrame("{\"type\":\"hello\",\"v\":1}")
        assertEquals("{\"type\":\"hello\",\"v\":1}\n", out.toString("UTF-8"))

        writer.writeFrame("{\"type\":\"cancel\",\"id\":\"r1\"}")
        assertEquals("{\"type\":\"hello\",\"v\":1}\n{\"type\":\"cancel\",\"id\":\"r1\"}\n", out.toString("UTF-8"))
    }

    @Test
    fun frameWriterRejectsPayloadContainingNewline() {
        val out = ByteArrayOutputStream()
        val writer = FrameWriter(out)

        try {
            writer.writeFrame("line1\nline2")
            fail("writeFrame must reject payload containing newline")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            writer.writeFrame("line1\rline2")
            fail("writeFrame must reject payload containing carriage return")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
