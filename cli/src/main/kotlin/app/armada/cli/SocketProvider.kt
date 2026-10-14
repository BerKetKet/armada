package app.armada.cli

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface SocketConnection : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

interface SocketProvider {
    fun open(name: String): SocketConnection
}
