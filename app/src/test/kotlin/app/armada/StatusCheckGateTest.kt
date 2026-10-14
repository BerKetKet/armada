package app.armada

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCheckGateTest {

    @Test
    fun afterTwoBeginCallsFirstTokenIsNotCurrentAndSecondIs() {
        val gate = StatusCheckGate()
        val token1 = gate.begin()
        val token2 = gate.begin()

        assertFalse(gate.isCurrent(token1))
        assertTrue(gate.isCurrent(token2))
    }
}
