package app.armada.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Server(
    private val socketName: String = SOCKET_NAME,
    private val tools: Map<String, Tool> = defaultTools(),
    private val sessionExecutor: ExecutorService = Executors.newCachedThreadPool()
) : Closeable {

    companion object {
        const val SOCKET_NAME: String = "armada.daemon"

        fun defaultTools(): Map<String, Tool> = mapOf(
            "echo" to EchoTool()
        )
    }

    private val running = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val activeSessions = ConcurrentHashMap.newKeySet<Session>()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        val server = LocalServerSocket(socketName)
        serverSocket = server

        try {
            while (running.get()) {
                val clientSocket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!running.get()) break
                    throw e
                }

                activeSockets.add(clientSocket)

                sessionExecutor.execute {
                    handleClient(clientSocket)
                }
            }
        } finally {
            shutdown()
        }
    }

    private fun handleClient(socket: LocalSocket) {
        var session: Session? = null
        try {
            val creds = socket.peerCredentials
            val peerIsRoot = (creds != null && creds.uid == 0)

            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            val reader = FrameReader(inputStream)
            val writer = FrameWriter(outputStream)

            session = Session(
                reader = reader,
                writer = writer,
                peerIsRoot = peerIsRoot,
                tools = tools
            )
            activeSessions.add(session)
            session.run()
        } catch (ignored: Exception) {
        } finally {
            if (session != null) {
                activeSessions.remove(session)
                session.close()
            }
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
            activeSockets.remove(socket)
        }
    }

    fun shutdown(
        pidFile: File = File(DaemonLifecycle.PID_PATH),
        logFile: File = File(DaemonLifecycle.LOG_PATH),
        timeoutMs: Long = DaemonLifecycle.SHUTDOWN_TIMEOUT_MS
    ) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }
        running.set(false)

        // 1. Stop accepting new connections (close the listening socket)
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null

        // 2. Cancel or complete in-flight request handling within bounded timeout (5 seconds)
        val deadline = System.currentTimeMillis() + timeoutMs
        for (session in activeSessions) {
            session.cancelInFlight()
        }
        for (session in activeSessions) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            if (remaining > 0 && session.hasInFlightRequests()) {
                session.awaitInFlight(remaining)
            }
        }

        // Close sockets so blocked reads unblock
        for (sock in activeSockets) {
            try {
                sock.close()
            } catch (ignored: Exception) {
            }
        }
        activeSockets.clear()

        // 3. Shut down all executors without leaking threads
        for (session in activeSessions) {
            session.close()
        }
        activeSessions.clear()
        DaemonLifecycle.shutdownExecutor(sessionExecutor, timeoutMs)

        // 4. Delete the pid file (daemon.pid) if the daemon created it
        DaemonLifecycle.removePidFile(pidFile)

        // 5. Append a shutdown record to the daemon log
        DaemonLifecycle.appendShutdownRecord(logFile, "daemon shutdown: clean exit")
    }

    override fun close() {
        shutdown()
    }
}
