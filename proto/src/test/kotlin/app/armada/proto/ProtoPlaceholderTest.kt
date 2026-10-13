package app.armada.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoPlaceholderTest {
    @Test
    fun placeholderBuildsAndTestsOnDesktop() {
        val placeholder = ProtoPlaceholder()
        assertEquals("proto", placeholder.name)
    }
}
