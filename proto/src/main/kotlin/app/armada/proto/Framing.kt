package app.armada.proto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class FrameReader(
    private val inputStream: InputStream,
    val maxBytes: Int = 1_048_576 // 1 MiB
) {
    private val readBuf = ByteArray(8192)
    private var buffer = ByteArray(8192)
    private var bufferStart = 0
    private var bufferEnd = 0
    private var isEof = false

    private val utf8Decoder = Charset.forName("UTF-8").newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun readFrame(): String? {
        while (true) {
            val newlineIndex = findNewline()
            if (newlineIndex != -1) {
                var lineEnd = newlineIndex
                val lineByteLength = lineEnd - bufferStart
                if (lineByteLength > maxBytes) {
                    throw FrameTooLongException("Frame exceeds maximum length of $maxBytes bytes ($lineByteLength bytes)")
                }

                // Check for optional carriage return preceding newline
                if (lineEnd > bufferStart && buffer[lineEnd - 1] == '\r'.code.toByte()) {
                    lineEnd--
                }

                val lineLen = lineEnd - bufferStart
                val line = decodeUtf8(buffer, bufferStart, lineLen)
                bufferStart = newlineIndex + 1

                if (bufferStart == bufferEnd) {
                    bufferStart = 0
                    bufferEnd = 0
                }

                if (line.isNotBlank()) {
                    return line
                }
                // Skip blank lines
                continue
            }

            val pendingBytes = bufferEnd - bufferStart
            if (pendingBytes > maxBytes) {
                throw FrameTooLongException("Frame exceeds maximum length of $maxBytes bytes without newline ($pendingBytes bytes)")
            }

            if (isEof) {
                if (pendingBytes == 0) {
                    return null
                } else {
                    throw TruncatedFrameException("Stream terminated without newline delimiter ($pendingBytes pending bytes)")
                }
            }

            compactBufferIfNeeded()
            val bytesRead = inputStream.read(readBuf)
            if (bytesRead == -1) {
                isEof = true
                if (bufferEnd == bufferStart) {
                    return null
                } else {
                    throw TruncatedFrameException("Stream terminated without newline delimiter (${bufferEnd - bufferStart} pending bytes)")
                }
            } else if (bytesRead > 0) {
                appendBytes(readBuf, 0, bytesRead)
                val newPending = bufferEnd - bufferStart
                if (newPending > maxBytes) {
                    val nl = findNewline()
                    if (nl == -1 || nl - bufferStart > maxBytes) {
                        throw FrameTooLongException("Frame exceeds maximum length of $maxBytes bytes without newline ($newPending bytes)")
                    }
                }
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String {
        return try {
            utf8Decoder.reset()
            val inBuf = ByteBuffer.wrap(bytes, offset, length)
            utf8Decoder.decode(inBuf).toString()
        } catch (e: CharacterCodingException) {
            throw MalformedFrameException("Malformed UTF-8 sequence in frame", e)
        }
    }

    private fun findNewline(): Int {
        for (i in bufferStart until bufferEnd) {
            if (buffer[i] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun compactBufferIfNeeded() {
        if (bufferStart > 0) {
            val remaining = bufferEnd - bufferStart
            if (remaining > 0) {
                System.arraycopy(buffer, bufferStart, buffer, 0, remaining)
            }
            bufferStart = 0
            bufferEnd = remaining
        }
    }

    private fun appendBytes(src: ByteArray, offset: Int, len: Int) {
        if (bufferEnd + len > buffer.size) {
            val newCapacity = maxOf(buffer.size * 2, bufferEnd + len)
            val newBuf = ByteArray(newCapacity)
            System.arraycopy(buffer, 0, newBuf, 0, bufferEnd)
            buffer = newBuf
        }
        System.arraycopy(src, offset, buffer, bufferEnd, len)
        bufferEnd += len
    }
}

class FrameWriter(
    private val outputStream: OutputStream
) {
    fun writeFrame(payload: String) {
        if (payload.contains('\n') || payload.contains('\r')) {
            throw IllegalArgumentException("Payload must not contain raw newline")
        }
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        outputStream.write(bytes)
        outputStream.write('\n'.code)
        outputStream.flush()
    }
}
