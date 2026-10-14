package app.armada.daemon

import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import app.armada.proto.JsonNumber
import app.armada.proto.JsonObject
import app.armada.proto.JsonParser
import app.armada.proto.JsonString
import app.armada.proto.Message
import app.armada.proto.Messages
import app.armada.proto.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SessionTest {

    private class Connection {
        val clientToServerIn = PipedInputStream(1024 * 1024)
        val clientToServerOut = PipedOutputStream(clientToServerIn)
        val serverToClientIn = PipedInputStream(1024 * 1024)
        val serverToClientOut = PipedOutputStream(serverToClientIn)

        val serverReader = FrameReader(clientToServerIn)
        val serverWriter = FrameWriter(serverToClientOut)
        val clientReader = FrameReader(serverToClientIn)
        val clientWriter = FrameWriter(clientToServerOut)

        fun sendFromClient(line: String) {
            clientWriter.writeFrame(line)
        }

        fun readFromServer(): String? {
            return clientReader.readFrame()
        }

        fun close() {
            try { clientToServerOut.close() } catch (ignored: Exception) {}
            try { serverToClientOut.close() } catch (ignored: Exception) {}
        }
    }

    private fun startSession(
        peerIsRoot: Boolean = true,
        tools: Map<String, Tool> = mapOf("echo" to EchoTool())
    ): Pair<Connection, Thread> {
        val conn = Connection()
        val session = Session(
            reader = conn.serverReader,
            writer = conn.serverWriter,
            peerIsRoot = peerIsRoot,
            tools = tools
        )
        val thread = Thread({
            try {
                session.run()
            } finally {
                try { conn.serverToClientOut.close() } catch (ignored: Exception) {}
            }
        }, "SessionTest-Server")
        thread.start()
        return Pair(conn, thread)
    }

    @Test
    fun handshakeSucceedsWithMatchingV() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            val serverHello = conn.readFromServer()
            assertNotNull(serverHello)
            val msg = Messages.decode(serverHello!!)
            assertTrue(msg is Message.Hello && msg.v == Protocol.VERSION)

            conn.sendFromClient("""{"type":"hello","v":${Protocol.VERSION}}""")

            // Send a request to prove session remains open and active
            conn.sendFromClient("""{"type":"request","id":"r1","tool":"echo","args":{"text":"ok"}}""")
            val reply = conn.readFromServer()
            assertNotNull(reply)
            val res = Messages.decode(reply!!)
            assertTrue(res is Message.Result)
            assertEquals("r1", (res as Message.Result).id)
            assertEquals("ok", res.data.requireString("text"))
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun handshakeClosesOnAMismatchedV() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            val serverHello = conn.readFromServer()
            assertNotNull(serverHello)

            // Client replies with version 2 (mismatch)
            conn.sendFromClient("""{"type":"hello","v":2}""")

            val err = conn.readFromServer()
            assertNotNull(err)
            val res = Messages.decode(err!!)
            assertTrue(res is Message.Failure)
            assertEquals(Protocol.HANDSHAKE_ID, (res as Message.Failure).id)
            assertEquals(Protocol.BAD_REQUEST, res.code)

            // Server closes stream on handshake failure
            thread.join(2000)
            assertTrue(!thread.isAlive)
            assertNull(conn.readFromServer())
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun nonRootPeerGetsDeniedOnTheHandshakeIdThenTheStreamIsClosedAndNoRequestIsProcessed() {
        val (conn, thread) = startSession(peerIsRoot = false)
        try {
            val serverHello = conn.readFromServer()
            assertNotNull(serverHello)

            // Client attempts to send request right away
            conn.sendFromClient("""{"type":"request","id":"r1","tool":"echo","args":{"text":"root?"}}""")

            val err = conn.readFromServer()
            assertNotNull(err)
            val res = Messages.decode(err!!)
            assertTrue(res is Message.Failure)
            assertEquals(Protocol.HANDSHAKE_ID, (res as Message.Failure).id)
            assertEquals(Protocol.DENIED, res.code)

            thread.join(2000)
            assertTrue(!thread.isAlive)
            assertNull(conn.readFromServer())
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun echoRoundTripReturnsTheText() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            conn.sendFromClient("""{"type":"request","id":"r1","tool":"echo","args":{"text":"round trip"}}""")
            val reply = conn.readFromServer()
            assertNotNull(reply)
            val res = Messages.decode(reply!!)
            assertTrue(res is Message.Result)
            assertEquals("r1", (res as Message.Result).id)
            assertEquals("round trip", res.data.requireString("text"))
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun echoWithDelayMsEmitsOneProgressMessageThenOneResult() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            conn.sendFromClient("""{"type":"request","id":"r2","tool":"echo","args":{"text":"delayed","delayMs":30}}""")

            val first = conn.readFromServer()
            assertNotNull(first)
            val prog = Messages.decode(first!!)
            assertTrue("Expected progress message, got $first", prog is Message.Progress)
            assertEquals("r2", (prog as Message.Progress).id)
            assertEquals(1L, prog.data.requireLong("seq"))
            assertTrue(prog.data.requireLong("elapsedMs") >= 0L)

            val second = conn.readFromServer()
            assertNotNull(second)
            val res = Messages.decode(second!!)
            assertTrue("Expected result message, got $second", res is Message.Result)
            assertEquals("r2", (res as Message.Result).id)
            assertEquals("delayed", res.data.requireString("text"))
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun echoWithTextLongerThan4096CharsReturnsBadArgs() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            val longText = "a".repeat(4097)
            conn.sendFromClient("""{"type":"request","id":"r3","tool":"echo","args":{"text":"$longText"}}""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val err = Messages.decode(reply!!)
            assertTrue(err is Message.Failure)
            assertEquals("r3", (err as Message.Failure).id)
            assertEquals(Protocol.BAD_ARGS, err.code)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun echoWithDelayMsAbove10000ReturnsBadArgs() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            conn.sendFromClient("""{"type":"request","id":"r4","tool":"echo","args":{"text":"test","delayMs":10001}}""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val err = Messages.decode(reply!!)
            assertTrue(err is Message.Failure)
            assertEquals("r4", (err as Message.Failure).id)
            assertEquals(Protocol.BAD_ARGS, err.code)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun unknownToolReturnsUnknownTool() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            conn.sendFromClient("""{"type":"request","id":"r5","tool":"nonexistent","args":{}}""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val err = Messages.decode(reply!!)
            assertTrue(err is Message.Failure)
            assertEquals("r5", (err as Message.Failure).id)
            assertEquals(Protocol.UNKNOWN_TOOL, err.code)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun duplicateInFlightIdReturnsBadRequestAndTheOriginalRequestStillCompletes() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            // First request with delay so it remains in flight
            conn.sendFromClient("""{"type":"request","id":"rdup","tool":"echo","args":{"text":"original","delayMs":60}}""")

            // Small pause to guarantee first request is registered in-flight
            Thread.sleep(15)

            // Second request with duplicate ID
            conn.sendFromClient("""{"type":"request","id":"rdup","tool":"echo","args":{"text":"duplicate","delayMs":0}}""")

            // We expect duplicate rejection first or progress from first
            val messages = mutableListOf<Message>()
            while (messages.size < 3) {
                val line = conn.readFromServer() ?: break
                messages.add(Messages.decode(line))
            }

            val duplicateError = messages.filterIsInstance<Message.Failure>().firstOrNull { it.id == "rdup" }
            assertNotNull("Expected duplicate in-flight error", duplicateError)
            assertEquals(Protocol.BAD_REQUEST, duplicateError!!.code)
            assertTrue(duplicateError.message.contains("duplicate in-flight id"))

            val originalResult = messages.filterIsInstance<Message.Result>().firstOrNull { it.id == "rdup" }
            assertNotNull("Expected original request to complete successfully", originalResult)
            assertEquals("original", originalResult!!.data.requireString("text"))
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun cancelDuringALongEchoReturnsCancelledAndNoResultFollows() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            // Request with long delay
            conn.sendFromClient("""{"type":"request","id":"rcan","tool":"echo","args":{"text":"cancelme","delayMs":1000}}""")

            // Wait briefly to ensure thread starts waiting
            Thread.sleep(20)

            // Send cancel
            conn.sendFromClient("""{"type":"cancel","id":"rcan"}""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val err = Messages.decode(reply!!)
            assertTrue(err is Message.Failure)
            assertEquals("rcan", (err as Message.Failure).id)
            assertEquals(Protocol.CANCELLED, err.code)

            // Wait a short time to verify no result message is emitted afterwards
            Thread.sleep(50)
            conn.sendFromClient("""{"type":"request","id":"next","tool":"echo","args":{"text":"after-cancel"}}""")
            val nextReply = conn.readFromServer()
            assertNotNull(nextReply)
            val nextMsg = Messages.decode(nextReply!!)
            assertTrue("Expected next result, got $nextMsg", nextMsg is Message.Result)
            assertEquals("next", (nextMsg as Message.Result).id)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun cancelForAnIdNotInFlightIsIgnored() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            // Cancel an unknown ID
            conn.sendFromClient("""{"type":"cancel","id":"not_in_flight"}""")

            // Valid request immediately after
            conn.sendFromClient("""{"type":"request","id":"r_ok","tool":"echo","args":{"text":"alive"}}""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val res = Messages.decode(reply!!)
            assertTrue(res is Message.Result)
            assertEquals("r_ok", (res as Message.Result).id)
            assertEquals("alive", res.data.requireString("text"))
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun exactlyOneTerminalMessagePerRequest() {
        val seed = 20261014L
        val rng = Random(seed)
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            val batchSize = 50
            val ids = (0 until batchSize).map { "batch-$it" }
            val terminalCounts = ConcurrentHashMap<String, Int>()
            val progressCounts = ConcurrentHashMap<String, Int>()
            val terminalSeenBeforeProgress = ConcurrentHashMap<String, Boolean>()

            val readerThread = Thread {
                while (true) {
                    val line = conn.readFromServer() ?: break
                    val msg = Messages.decode(line)
                    when (msg) {
                        is Message.Progress -> {
                            if (terminalCounts.containsKey(msg.id)) {
                                terminalSeenBeforeProgress[msg.id] = true
                            }
                            progressCounts.merge(msg.id, 1, Int::plus)
                        }
                        is Message.Result -> {
                            terminalCounts.merge(msg.id, 1, Int::plus)
                        }
                        is Message.Failure -> {
                            terminalCounts.merge(msg.id, 1, Int::plus)
                        }
                        else -> {}
                    }
                }
            }
            readerThread.start()

            for (id in ids) {
                val delay = rng.nextLong(0, 30)
                conn.sendFromClient("""{"type":"request","id":"$id","tool":"echo","args":{"text":"val-$id","delayMs":$delay}}""")
                if (rng.nextInt(10) == 0) {
                    // Random cancel
                    conn.sendFromClient("""{"type":"cancel","id":"$id"}""")
                }
            }

            // Wait until all 50 have terminal message or timeout
            val startWait = System.currentTimeMillis()
            while (terminalCounts.size < batchSize && System.currentTimeMillis() - startWait < 5000) {
                Thread.sleep(10)
            }

            conn.close()
            readerThread.join(2000)

            for (id in ids) {
                val count = terminalCounts[id] ?: 0
                if (count != 1) {
                    fail("Failed on seed $seed: request $id expected exactly 1 terminal message, got $count")
                }
                if (terminalSeenBeforeProgress[id] == true) {
                    fail("Failed on seed $seed: request $id received progress after terminal message")
                }
            }
            assertEquals("All 50 requests must have exactly one terminal message", batchSize, terminalCounts.size)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun noProgressAfterTheTerminalMessage() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            conn.sendFromClient("""{"type":"request","id":"p1","tool":"echo","args":{"text":"check","delayMs":20}}""")

            val messages = mutableListOf<Message>()
            while (messages.size < 2) {
                val line = conn.readFromServer() ?: break
                messages.add(Messages.decode(line))
            }

            assertEquals(2, messages.size)
            assertTrue(messages[0] is Message.Progress)
            assertTrue(messages[1] is Message.Result)

            // Further reads must not yield any progress for p1
            Thread.sleep(30)
            conn.sendFromClient("""{"type":"request","id":"p2","tool":"echo","args":{"text":"check2"}}""")
            val nextLine = conn.readFromServer()
            assertNotNull(nextLine)
            val nextMsg = Messages.decode(nextLine!!)
            assertTrue(nextMsg is Message.Result)
            assertEquals("p2", (nextMsg as Message.Result).id)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }

    @Test
    fun malformedFrameReturnsBadRequest() {
        val (conn, thread) = startSession(peerIsRoot = true)
        try {
            conn.readFromServer() // Server Hello
            conn.sendFromClient("""{"type":"hello","v":1}""")

            // Send malformed JSON
            conn.sendFromClient("""not valid json {""")

            val reply = conn.readFromServer()
            assertNotNull(reply)
            val err = Messages.decode(reply!!)
            assertTrue(err is Message.Failure)
            assertEquals(Protocol.HANDSHAKE_ID, (err as Message.Failure).id)
            assertEquals(Protocol.BAD_REQUEST, err.code)
        } finally {
            conn.close()
            thread.join(2000)
        }
    }
}
