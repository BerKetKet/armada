package app.armada.cli

import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import app.armada.proto.Message
import app.armada.proto.Messages
import app.armada.proto.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream

class CliTest {

    private class MockSocketProvider(
        private val onConnect: (FrameReader, FrameWriter) -> Unit
    ) : SocketProvider {
        override fun open(name: String): SocketConnection {
            val clientToServerIn = PipedInputStream(64 * 1024)
            val clientToServerOut = PipedOutputStream(clientToServerIn)
            val serverToClientIn = PipedInputStream(64 * 1024)
            val serverToClientOut = PipedOutputStream(serverToClientIn)

            val serverReader = FrameReader(clientToServerIn)
            val serverWriter = FrameWriter(serverToClientOut)

            val serverThread = Thread {
                try {
                    onConnect(serverReader, serverWriter)
                } finally {
                    try { serverToClientOut.close() } catch (ignored: Exception) {}
                }
            }
            serverThread.start()

            return object : SocketConnection {
                override val inputStream: InputStream get() = serverToClientIn
                override val outputStream: OutputStream get() = clientToServerOut
                override fun close() {
                    try { clientToServerOut.close() } catch (ignored: Exception) {}
                    try { serverToClientIn.close() } catch (ignored: Exception) {}
                    serverThread.join(1000)
                }
            }
        }
    }

    private class FailingSocketProvider : SocketProvider {
        override fun open(name: String): SocketConnection {
            throw IOException("Connection refused to $name")
        }
    }

    @Test
    fun helloCommandSucceedsAndPrintsVersion() {
        val provider = MockSocketProvider { reader, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            val clientHello = reader.readFrame()
            assertNotNull(clientHello)
            val msg = Messages.decode(clientHello!!)
            assertTrue(msg is Message.Hello && msg.v == Protocol.VERSION)
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("hello"))
        assertEquals(Cli.EXIT_OK, exitCode)
        assertEquals("""{"v":1}""", outBytes.toString("UTF-8").trim())
        assertEquals("", errBytes.toString("UTF-8").trim())
    }

    @Test
    fun helloWithExtraArgsReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("hello", "extra"))
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("Usage:"))
    }

    @Test
    fun echoCommandSucceedsAndPrintsResult() {
        val provider = MockSocketProvider { reader, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            val clientHello = reader.readFrame()
            assertNotNull(clientHello)

            val reqLine = reader.readFrame()
            assertNotNull(reqLine)
            val req = Messages.decode(reqLine!!)
            assertTrue(req is Message.Request)
            val reqMsg = req as Message.Request
            assertEquals("echo", reqMsg.tool)
            assertEquals("hello world", reqMsg.args.requireString("text"))

            writer.writeFrame("""{"type":"result","id":"${reqMsg.id}","data":{"text":"hello world"}}""")
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "hello world"))
        assertEquals(Cli.EXIT_OK, exitCode)
        assertEquals("""{"text":"hello world"}""", outBytes.toString("UTF-8").trim())
        assertEquals("", errBytes.toString("UTF-8").trim())
    }

    @Test
    fun echoWithDelayMsFlagSucceeds() {
        val provider = MockSocketProvider { reader, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            reader.readFrame() // Client Hello

            val reqLine = reader.readFrame()
            assertNotNull(reqLine)
            val reqMsg = Messages.decode(reqLine!!) as Message.Request
            assertEquals(150L, reqMsg.args.requireLong("delayMs"))

            writer.writeFrame("""{"type":"result","id":"${reqMsg.id}","data":{"text":"with-delay"}}""")
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "with-delay", "--delay-ms", "150"))
        assertEquals(Cli.EXIT_OK, exitCode)
        assertEquals("""{"text":"with-delay"}""", outBytes.toString("UTF-8").trim())
    }

    @Test
    fun echoWithDelayMsEqualsFlagSucceeds() {
        val provider = MockSocketProvider { reader, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            reader.readFrame() // Client Hello

            val reqLine = reader.readFrame()
            assertNotNull(reqLine)
            val reqMsg = Messages.decode(reqLine!!) as Message.Request
            assertEquals(200L, reqMsg.args.requireLong("delayMs"))

            writer.writeFrame("""{"type":"result","id":"${reqMsg.id}","data":{"text":"with-equals"}}""")
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "with-equals", "--delay-ms=200"))
        assertEquals(Cli.EXIT_OK, exitCode)
        assertEquals("""{"text":"with-equals"}""", outBytes.toString("UTF-8").trim())
    }

    @Test
    fun echoWithMissingTextReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo"))
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("Usage:"))
    }

    @Test
    fun echoWithInvalidDelayMsReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "text", "--delay-ms", "not_a_number"))
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("Invalid value for --delay-ms"))
    }

    @Test
    fun echoWithUnknownFlagReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "text", "--invalid-flag"))
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("Unknown option"))
    }

    @Test
    fun unknownCommandReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("unknown_cmd"))
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("Unknown command 'unknown_cmd'"))
    }

    @Test
    fun emptyArgsReturnsUsageError() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(emptyArray())
        assertEquals(Cli.EXIT_USAGE, exitCode)
        assertTrue(errBytes.toString("UTF-8").contains("No command specified"))
    }

    @Test
    fun daemonErrorReturnsExitCode1AndPrintsErrorJsonToStderr() {
        val provider = MockSocketProvider { reader, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            reader.readFrame() // Client Hello

            val reqLine = reader.readFrame()
            assertNotNull(reqLine)
            val reqMsg = Messages.decode(reqLine!!) as Message.Request

            writer.writeFrame("""{"type":"error","id":"${reqMsg.id}","code":"bad_args","message":"text length exceeds 4096 characters"}""")
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "toolarge"))
        assertEquals(Cli.EXIT_ERROR, exitCode)
        val errOutput = errBytes.toString("UTF-8").trim()
        val failureMsg = Messages.decode(errOutput)
        assertTrue(failureMsg is Message.Failure)
        assertEquals(Protocol.BAD_ARGS, (failureMsg as Message.Failure).code)
        assertEquals("text length exceeds 4096 characters", failureMsg.message)
    }

    @Test
    fun daemonHandshakeDeniedReturnsExitCode1AndPrintsErrorJsonToStderr() {
        val provider = MockSocketProvider { _, writer ->
            writer.writeFrame("""{"type":"hello","v":1}""")
            writer.writeFrame("""{"type":"error","id":"handshake","code":"denied","message":"peer is not root"}""")
        }

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(provider, PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("echo", "hello"))
        assertEquals(Cli.EXIT_ERROR, exitCode)
        val errOutput = errBytes.toString("UTF-8").trim()
        val failureMsg = Messages.decode(errOutput)
        assertTrue(failureMsg is Message.Failure)
        assertEquals(Protocol.DENIED, (failureMsg as Message.Failure).code)
        assertEquals("peer is not root", failureMsg.message)
    }

    @Test
    fun connectionFailureReturnsExitCode1() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val cli = Cli(FailingSocketProvider(), PrintStream(outBytes), PrintStream(errBytes))

        val exitCode = cli.run(arrayOf("hello"))
        assertEquals(Cli.EXIT_ERROR, exitCode)
        val errOutput = errBytes.toString("UTF-8").trim()
        val failureMsg = Messages.decode(errOutput)
        assertTrue(failureMsg is Message.Failure)
        assertEquals(Protocol.FAILED, (failureMsg as Message.Failure).code)
    }
}
