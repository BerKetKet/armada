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

    @Test
    fun shutdownTimeoutConstantIsFiveSeconds() {
        assertEquals(5000L, DaemonLifecycle.SHUTDOWN_TIMEOUT_MS)
    }

    @Test
    fun appendShutdownRecordAppendsToFileAndCreatesParentDirs() {
        val logFile = File(tmpFolder.root, "nested/dir/daemon.log")
        DaemonLifecycle.appendShutdownRecord(logFile, "daemon shutdown: clean exit")
        assertTrue(logFile.exists())
        assertEquals("daemon shutdown: clean exit\n", logFile.readText())

        DaemonLifecycle.appendShutdownRecord(logFile, "second record")
        assertEquals("daemon shutdown: clean exit\nsecond record\n", logFile.readText())
    }

    @Test
    fun shutdownExecutorTerminatesCleanly() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        exec.execute { ran.set(true) }

        val clean = DaemonLifecycle.shutdownExecutor(exec, 1000L)
        assertTrue(clean)
        assertTrue(exec.isTerminated)
        assertTrue(ran.get())
    }

    @Test
    fun shutdownExecutorForcesTerminationOnTimeout() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        exec.execute {
            started.countDown()
            try {
                Thread.sleep(10000L)
            } catch (ignored: InterruptedException) {
            }
        }

        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val clean = DaemonLifecycle.shutdownExecutor(exec, 100L)
        assertTrue(exec.isTerminated)
    }
}
