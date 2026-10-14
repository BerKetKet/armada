package app.armada.daemon

import java.io.File

object DaemonLifecycle {
    const val PID_PATH: String = "/data/local/tmp/armada/daemon.pid"

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
}
