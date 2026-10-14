package app.armada.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

class Main {
    companion object {
        val PID_FILE: File = File(DaemonLifecycle.PID_PATH)

        @JvmStatic
        fun main(args: Array<String>) {
            // Refuse to start if effective uid is not 0 (root check via android.system.Os.getuid())
            if (!DaemonLifecycle.checkRoot { Os.getuid() }) {
                System.err.println("Fatal: Armada daemon requires root (uid 0). Current uid is ${Os.getuid()}.")
                exitProcess(1)
            }

            // Refuse to start if another live daemon holds the socket
            if (isLiveDaemonRunning()) {
                System.err.println("Fatal: Another live daemon already holds the socket ${Server.SOCKET_NAME}.")
                exitProcess(1)
            }

            try {
                DaemonLifecycle.writePidFile(PID_FILE, Os.getpid())
            } catch (e: Exception) {
                System.err.println("Warning: Failed to write pid file: ${e.message}")
            }

            Runtime.getRuntime().addShutdownHook(Thread {
                DaemonLifecycle.removePidFile(PID_FILE)
            })

            val server = Server()
            try {
                server.start()
            } finally {
                DaemonLifecycle.removePidFile(PID_FILE)
            }
        }

        private fun isLiveDaemonRunning(): Boolean {
            return try {
                val probeSocket = LocalSocket()
                probeSocket.connect(LocalSocketAddress(Server.SOCKET_NAME))
                probeSocket.close()
                true
            } catch (e: IOException) {
                false
            }
        }
    }
}
