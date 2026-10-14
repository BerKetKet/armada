package app.armada.daemon

import app.armada.proto.FrameReader
import app.armada.proto.FrameTooLongException
import app.armada.proto.FrameWriter
import app.armada.proto.JsonObject
import app.armada.proto.JsonParser
import app.armada.proto.JsonString
import app.armada.proto.MalformedFrameException
import app.armada.proto.Message
import app.armada.proto.Messages
import app.armada.proto.Protocol
import app.armada.proto.ProtocolException
import app.armada.proto.TruncatedFrameException
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Session(
    private val reader: FrameReader,
    private val writer: FrameWriter,
    private val peerIsRoot: Boolean,
    private val tools: Map<String, Tool>,
    private val executor: ExecutorService = Executors.newCachedThreadPool()
) : Closeable {
    private val writeLock = Any()
    private val dispatcher = Dispatcher(tools)
    private val inFlight = ConcurrentHashMap<String, RequestTask>()

    private class RequestTask(val id: String) {
        val cancelled = AtomicBoolean(false)
        val terminalSent = AtomicBoolean(false)
        @Volatile var thread: Thread? = null

        fun cancel() {
            cancelled.set(true)
            thread?.interrupt()
        }
    }

    fun run() {
        try {
            if (!performHandshake()) {
                return
            }
            readLoop()
        } finally {
            cleanup()
        }
    }

    private fun performHandshake(): Boolean {
        // If the peer is not root, send only the denied handshake error and close, before any other frame is read.
        if (!peerIsRoot) {
            try {
                send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.DENIED, message = "peer is not root"))
            } catch (ignored: Exception) {
            }
            return false
        }

        // On connect with a root peer, the daemon sends {"type":"hello","v":1} first.
        try {
            send(Message.Hello(Protocol.VERSION))
        } catch (e: Exception) {
            return false
        }

        val handshakeLine = try {
            reader.readFrame()
        } catch (e: FrameTooLongException) {
            return false
        } catch (e: TruncatedFrameException) {
            return false
        } catch (e: MalformedFrameException) {
            try {
                send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.BAD_REQUEST, message = "malformed frame: ${e.message}"))
            } catch (ignored: Exception) {
            }
            return false
        }

        if (handshakeLine == null) {
            return false
        }

        val handshakeMsg = try {
            Messages.decode(handshakeLine)
        } catch (e: Throwable) {
            try {
                send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.BAD_REQUEST, message = "invalid handshake: ${e.message}"))
            } catch (ignored: Exception) {
            }
            return false
        }

        if (handshakeMsg !is Message.Hello || handshakeMsg.v != Protocol.VERSION) {
            try {
                send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.BAD_REQUEST, message = "handshake failed: expected hello with v=${Protocol.VERSION}"))
            } catch (ignored: Exception) {
            }
            return false
        }

        return true
    }

    private fun readLoop() {
        while (true) {
            val frame = try {
                reader.readFrame() ?: break
            } catch (e: FrameTooLongException) {
                break
            } catch (e: TruncatedFrameException) {
                break
            } catch (e: MalformedFrameException) {
                try {
                    send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.BAD_REQUEST, message = "malformed frame: ${e.message}"))
                } catch (ignored: Exception) {
                }
                break
            }

            val message = try {
                Messages.decode(frame)
            } catch (e: ProtocolException) {
                // Frames that fail Messages.decode get an error with id "handshake" only if no id could be read; otherwise they get the id's error.
                val extractedId = extractIdFromRawJson(frame)
                val errId = extractedId ?: Protocol.HANDSHAKE_ID
                send(Message.Failure(id = errId, code = e.code, message = e.message ?: e.code))
                continue
            } catch (e: Throwable) {
                val extractedId = extractIdFromRawJson(frame)
                val errId = extractedId ?: Protocol.HANDSHAKE_ID
                send(Message.Failure(id = errId, code = Protocol.BAD_REQUEST, message = e.message ?: "decode error"))
                continue
            }

            handleMessage(message)
        }
    }

    private fun extractIdFromRawJson(frame: String): String? {
        return try {
            val root = JsonParser.parse(frame)
            if (root is JsonObject) {
                val rawId = root["id"]
                if (rawId is JsonString && rawId.value.isNotEmpty()) {
                    rawId.value
                } else null
            } else null
        } catch (ignored: Exception) {
            null
        }
    }

    private fun handleMessage(message: Message) {
        when (message) {
            is Message.Request -> handleRequest(message)
            is Message.Cancel -> handleCancel(message)
            is Message.Hello -> {
                send(Message.Failure(id = Protocol.HANDSHAKE_ID, code = Protocol.BAD_REQUEST, message = "unexpected hello after handshake"))
            }
            is Message.Progress -> {
                send(Message.Failure(id = message.id, code = Protocol.BAD_REQUEST, message = "unexpected message type: progress"))
            }
            is Message.Result -> {
                send(Message.Failure(id = message.id, code = Protocol.BAD_REQUEST, message = "unexpected message type: result"))
            }
            is Message.Failure -> {
                send(Message.Failure(id = message.id, code = Protocol.BAD_REQUEST, message = "unexpected message type: error"))
            }
        }
    }

    private fun handleRequest(message: Message.Request) {
        val task = RequestTask(message.id)
        val existing = inFlight.putIfAbsent(message.id, task)
        if (existing != null) {
            send(Message.Failure(id = message.id, code = Protocol.BAD_REQUEST, message = "duplicate in-flight id"))
            return
        }

        val tool = dispatcher.find(message.tool)
        if (tool == null) {
            inFlight.remove(message.id)
            send(Message.Failure(id = message.id, code = Protocol.UNKNOWN_TOOL, message = "unknown tool: ${message.tool}"))
            return
        }

        executor.execute {
            task.thread = Thread.currentThread()
            val ctx = object : ToolContext {
                override val isCancelled: Boolean
                    get() = task.cancelled.get() || Thread.currentThread().isInterrupted

                override fun progress(data: JsonObject) {
                    if (!task.terminalSent.get() && !task.cancelled.get()) {
                        synchronized(writeLock) {
                            if (!task.terminalSent.get() && !task.cancelled.get()) {
                                sendLocked(Message.Progress(id = message.id, data = data))
                            }
                        }
                    }
                }
            }

            try {
                val result = tool.run(message.args, ctx)
                if (task.terminalSent.compareAndSet(false, true)) {
                    synchronized(writeLock) {
                        sendLocked(Message.Result(id = message.id, data = result))
                    }
                }
            } catch (e: ProtocolException) {
                if (task.terminalSent.compareAndSet(false, true)) {
                    synchronized(writeLock) {
                        sendLocked(Message.Failure(id = message.id, code = e.code, message = e.message ?: e.code))
                    }
                }
            } catch (e: InterruptedException) {
                if (task.terminalSent.compareAndSet(false, true)) {
                    synchronized(writeLock) {
                        sendLocked(Message.Failure(id = message.id, code = Protocol.CANCELLED, message = "request cancelled"))
                    }
                }
            } catch (e: Throwable) {
                if (task.cancelled.get() || Thread.currentThread().isInterrupted) {
                    if (task.terminalSent.compareAndSet(false, true)) {
                        synchronized(writeLock) {
                            sendLocked(Message.Failure(id = message.id, code = Protocol.CANCELLED, message = "request cancelled"))
                        }
                    }
                } else {
                    if (task.terminalSent.compareAndSet(false, true)) {
                        synchronized(writeLock) {
                            sendLocked(Message.Failure(id = message.id, code = Protocol.FAILED, message = e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
            } finally {
                inFlight.remove(message.id)
                task.thread = null
            }
        }
    }

    private fun handleCancel(message: Message.Cancel) {
        val task = inFlight[message.id] ?: return
        task.cancel()
        if (task.terminalSent.compareAndSet(false, true)) {
            synchronized(writeLock) {
                sendLocked(Message.Failure(id = message.id, code = Protocol.CANCELLED, message = "request cancelled"))
            }
        }
    }

    private fun send(message: Message) {
        synchronized(writeLock) {
            sendLocked(message)
        }
    }

    private fun sendLocked(message: Message) {
        val text = Messages.encode(message)
        writer.writeFrame(text)
    }

    fun cancelInFlight() {
        for (task in inFlight.values) {
            task.cancel()
        }
    }

    fun hasInFlightRequests(): Boolean = inFlight.isNotEmpty()

    fun awaitInFlight(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlight.isNotEmpty()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            try {
                Thread.sleep(minOf(20L, remaining))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    override fun close() {
        cleanup()
    }

    private fun cleanup() {
        cancelInFlight()
        DaemonLifecycle.shutdownExecutor(executor, DaemonLifecycle.SHUTDOWN_TIMEOUT_MS)
        inFlight.clear()
    }
}
