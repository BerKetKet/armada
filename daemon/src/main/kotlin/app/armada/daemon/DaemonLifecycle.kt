package app.armada.daemon

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

object DaemonLifecycle {
    const val PID_PATH: String = "/data/local/tmp/armada/daemon.pid"
    const val LOG_PATH: String = "/data/local/tmp/armada/daemon.log"
    const val SHUTDOWN_TIMEOUT_MS: Long = 5000L

    fun checkRoot(uidProvider: () -> Int): Boolean {
        return uidProvider() == 0
    }

    fun writePidFile(file: File, pid: Int) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        file.writeText("$pid\n")
    }

    fun removePidFile(file: File): Boolean {
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    fun isSocketAlive(probe: () -> Boolean): Boolean {
        return probe()
    }

    fun appendShutdownRecord(file: File, record: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        file.appendText("$record\n")
    }

    fun shutdownExecutor(executor: ExecutorService, timeoutMs: Long = SHUTDOWN_TIMEOUT_MS): Boolean {
        executor.shutdown()
        return try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
                executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                true
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            false
        }
    }
}
