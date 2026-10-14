package app.armada.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class Server(
    private val socketName: String = SOCKET_NAME,
    private val tools: Map<String, Tool> = defaultTools()
) : Closeable {

    companion object {
        const val SOCKET_NAME: String = "armada.daemon"

        fun defaultTools(): Map<String, Tool> = mapOf(
            "echo" to EchoTool()
        )
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<LocalSocket>()

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

                Thread({
                    handleClient(clientSocket)
                }, "armada-session-${clientSocket.fileDescriptor}").start()
            }
        } finally {
            close()
        }
    }

    private fun handleClient(socket: LocalSocket) {
        try {
            val creds = socket.peerCredentials
            val peerIsRoot = (creds != null && creds.uid == 0)

            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            val reader = FrameReader(inputStream)
            val writer = FrameWriter(outputStream)

            val session = Session(
                reader = reader,
                writer = writer,
                peerIsRoot = peerIsRoot,
                tools = tools
            )
            session.run()
        } catch (ignored: Exception) {
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
            activeSockets.remove(socket)
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {
            }
            for (sock in activeSockets) {
                try {
                    sock.close()
                } catch (ignored: Exception) {
                }
            }
            activeSockets.clear()
        }
    }
}
