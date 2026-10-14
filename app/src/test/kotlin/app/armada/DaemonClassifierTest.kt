package app.armada

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class DaemonClassifierTest {

    @Test
    fun classifyReturnsConnectedWhenGreetingSucceeds() {
        val serverInput = "{\"type\":\"hello\",\"v\":1}\n".toByteArray(StandardCharsets.UTF_8)
        val inStream = ByteArrayInputStream(serverInput)
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertTrue(status is DaemonStatus.Connected)
        assertEquals(1, (status as DaemonStatus.Connected).version)

        val clientOutput = outStream.toString("UTF-8")
        assertTrue(clientOutput.contains("\"type\":\"hello\""))
    }

    @Test
    fun classifyReturnsDeniedWhenHandshakeDeniedAfterHello() {
        val serverInput = ("{\"type\":\"hello\",\"v\":1}\n" +
                "{\"type\":\"error\",\"id\":\"handshake\",\"code\":\"denied\",\"message\":\"peer is not root\"}\n")
            .toByteArray(StandardCharsets.UTF_8)
        val inStream = ByteArrayInputStream(serverInput)
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertEquals(DaemonStatus.Denied, status)
    }

    @Test
    fun classifyReturnsDeniedWhenHandshakeDeniedImmediately() {
        val serverInput = "{\"type\":\"error\",\"id\":\"handshake\",\"code\":\"denied\",\"message\":\"peer is not root\"}\n"
            .toByteArray(StandardCharsets.UTF_8)
        val inStream = ByteArrayInputStream(serverInput)
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertEquals(DaemonStatus.Denied, status)
    }

    @Test
    fun classifyReturnsUnreachableOnEmptyStream() {
        val inStream = ByteArrayInputStream(ByteArray(0))
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertTrue(status is DaemonStatus.Unreachable)
        assertEquals("EOFException", (status as DaemonStatus.Unreachable).exceptionClass)
    }

    @Test
    fun classifyReturnsUnreachableOnMalformedGreeting() {
        val serverInput = "not valid json\n".toByteArray(StandardCharsets.UTF_8)
        val inStream = ByteArrayInputStream(serverInput)
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertTrue(status is DaemonStatus.Unreachable)
        assertEquals("ProtocolException", (status as DaemonStatus.Unreachable).exceptionClass)
    }

    @Test
    fun classifyReturnsUnreachableOnOtherErrorCode() {
        val serverInput = "{\"type\":\"error\",\"id\":\"handshake\",\"code\":\"bad_request\",\"message\":\"error\"}\n"
            .toByteArray(StandardCharsets.UTF_8)
        val inStream = ByteArrayInputStream(serverInput)
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(inStream, outStream)
        assertTrue(status is DaemonStatus.Unreachable)
        assertEquals("ProtocolException", (status as DaemonStatus.Unreachable).exceptionClass)
    }

    @Test
    fun classifyReturnsUnreachableOnStreamFailure() {
        val failingStream = object : InputStream() {
            override fun read(): Int = throw IOException("simulated read failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("simulated read failure")
        }
        val outStream = ByteArrayOutputStream()

        val status = DaemonClassifier.classifyStreams(failingStream, outStream)
        assertTrue(status is DaemonStatus.Unreachable)
        assertEquals("IOException", (status as DaemonStatus.Unreachable).exceptionClass)
    }
}
