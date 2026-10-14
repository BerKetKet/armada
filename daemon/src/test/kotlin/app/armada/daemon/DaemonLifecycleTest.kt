package app.armada.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DaemonLifecycleTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun rootCheckSucceedsForUidZero() {
        assertTrue(DaemonLifecycle.checkRoot { 0 })
    }

    @Test
    fun rootCheckFailsForNonZeroUid() {
        assertFalse(DaemonLifecycle.checkRoot { 1000 })
        assertFalse(DaemonLifecycle.checkRoot { 10000 })
        assertFalse(DaemonLifecycle.checkRoot { -1 })
    }

    @Test
    fun pidFileIsWrittenAndRead() {
        val pidFile = File(tmpFolder.root, "sub/dir/daemon.pid")
        DaemonLifecycle.writePidFile(pidFile, 12345)

        assertTrue(pidFile.exists())
        assertEquals("12345", pidFile.readText().trim())
    }

    @Test
    fun pidFileIsCleanedUpOnRemoval() {
        val pidFile = File(tmpFolder.root, "daemon.pid")
        DaemonLifecycle.writePidFile(pidFile, 9999)
        assertTrue(pidFile.exists())

        val deleted = DaemonLifecycle.removePidFile(pidFile)
        assertTrue(deleted)
        assertFalse(pidFile.exists())
    }

    @Test
    fun socketAliveProbeReturnsProbeValue() {
        assertTrue(DaemonLifecycle.isSocketAlive { true })
        assertFalse(DaemonLifecycle.isSocketAlive { false })
    }
}
