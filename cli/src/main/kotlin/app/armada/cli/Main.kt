package app.armada.cli

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

class LocalSocketProvider : SocketProvider {
    override fun open(name: String): SocketConnection {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(name))
        return object : SocketConnection {
            override val inputStream: InputStream get() = socket.inputStream
            override val outputStream: OutputStream get() = socket.outputStream
            override fun close() = socket.close()
        }
    }
}

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val cli = Cli(
                socketProvider = LocalSocketProvider(),
                out = System.out,
                err = System.err
            )
            val exitCode = cli.run(args)
            if (exitCode != 0) {
                exitProcess(exitCode)
            }
        }
    }
}
