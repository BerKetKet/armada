package app.armada

import java.util.concurrent.atomic.AtomicInteger

class StatusCheckGate {
    private val currentToken = AtomicInteger(0)

    fun begin(): Int {
        return currentToken.incrementAndGet()
    }

    fun isCurrent(token: Int): Boolean {
        return token == currentToken.get()
    }
}
