package app.armada.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessagesTest {

    @Test
    fun roundTripAllMessageTypes() {
        val hello = Message.Hello(Protocol.VERSION)
        val helloEncoded = Messages.encode(hello)
        assertEquals(hello, Messages.decode(helloEncoded))

        val request = Message.Request(
            id = "req_1",
            tool = "ping",
            args = JsonObject(
                "host" to JsonString("1.1.1.1"),
                "count" to JsonNumber(4L)
            )
        )
        val requestEncoded = Messages.encode(request)
        assertEquals(request, Messages.decode(requestEncoded))

        val cancel = Message.Cancel(id = "req_1")
        val cancelEncoded = Messages.encode(cancel)
        assertEquals(cancel, Messages.decode(cancelEncoded))

        val progress = Message.Progress(
            id = "req_1",
            data = JsonObject(
                "seq" to JsonNumber(1L),
                "rttMs" to JsonNumber(12.4)
            )
        )
        val progressEncoded = Messages.encode(progress)
        assertEquals(progress, Messages.decode(progressEncoded))

        val result = Message.Result(
            id = "req_1",
            data = JsonObject(
                "sent" to JsonNumber(4L),
                "received" to JsonNumber(4L)
            )
        )
        val resultEncoded = Messages.encode(result)
        assertEquals(result, Messages.decode(resultEncoded))

        val failure = Message.Failure(
            id = "req_1",
            code = Protocol.BAD_ARGS,
            message = "count must be 1..100"
        )
        val failureEncoded = Messages.encode(failure)
        assertEquals(failure, Messages.decode(failureEncoded))
    }

    @Test
    fun invalidIdsAreRejected() {
        val invalidIds = listOf(
            "",
            "a".repeat(65),
            "id with spaces",
            "id@symbol",
            "id#bad",
            "id/slash",
            "id:colon"
        )

        for (badId in invalidIds) {
            try {
                Messages.encode(Message.Cancel(badId))
                fail("Encode must reject invalid id '$badId'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }

            try {
                Messages.decode("{\"type\":\"cancel\",\"id\":\"$badId\"}")
                fail("Decode must reject invalid id '$badId'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun invalidToolsAreRejected() {
        val invalidTools = listOf(
            "",
            "Tool",       // uppercase start
            "1tool",      // digit start
            "_tool",      // underscore start
            ".tool",      // dot start
            "tool-dash",  // dash not allowed in tool regex [a-z][a-z0-9_.]*
            "tool with space",
            "a".repeat(65)
        )

        for (badTool in invalidTools) {
            try {
                Messages.encode(Message.Request("r1", badTool, JsonObject(emptyMap())))
                fail("Encode must reject invalid tool '$badTool'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }

            try {
                Messages.decode("{\"type\":\"request\",\"id\":\"r1\",\"tool\":\"$badTool\",\"args\":{}}")
                fail("Decode must reject invalid tool '$badTool'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun invalidErrorCodesAreRejected() {
        val invalidCodes = listOf(
            "",
            "unknown",
            "BAD_REQUEST", // uppercase
            "custom_error",
            "denied_access"
        )

        for (badCode in invalidCodes) {
            try {
                Messages.encode(Message.Failure("r1", badCode, "msg"))
                fail("Encode must reject invalid error code '$badCode'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }

            try {
                Messages.decode("{\"type\":\"error\",\"id\":\"r1\",\"code\":\"$badCode\",\"message\":\"msg\"}")
                fail("Decode must reject invalid error code '$badCode'")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun allDefinedErrorCodesAreAccepted() {
        for (code in Protocol.ERROR_CODES) {
            val failure = Message.Failure("r1", code, "some message")
            val encoded = Messages.encode(failure)
            val decoded = Messages.decode(encoded)
            assertEquals(failure, decoded)
        }
    }

    @Test
    fun unknownTypeIsAnError() {
        try {
            Messages.decode("{\"type\":\"mystery\",\"id\":\"r1\"}")
            fail("Unknown message type must be rejected")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_REQUEST, e.code)
            assertTrue(e.message!!.contains("mystery"))
        }
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        val missingFieldCases = listOf(
            // Hello missing v
            "{\"type\":\"hello\"}",
            // Request missing id
            "{\"type\":\"request\",\"tool\":\"ping\",\"args\":{}}",
            // Request missing tool
            "{\"type\":\"request\",\"id\":\"r1\",\"args\":{}}",
            // Request missing args
            "{\"type\":\"request\",\"id\":\"r1\",\"tool\":\"ping\"}",
            // Cancel missing id
            "{\"type\":\"cancel\"}",
            // Progress missing id
            "{\"type\":\"progress\",\"data\":{}}",
            // Progress missing data
            "{\"type\":\"progress\",\"id\":\"r1\"}",
            // Result missing id
            "{\"type\":\"result\",\"data\":{}}",
            // Result missing data
            "{\"type\":\"result\",\"id\":\"r1\"}",
            // Failure missing id
            "{\"type\":\"error\",\"code\":\"failed\",\"message\":\"err\"}",
            // Failure missing code
            "{\"type\":\"error\",\"id\":\"r1\",\"message\":\"err\"}",
            // Failure missing message
            "{\"type\":\"error\",\"id\":\"r1\",\"code\":\"failed\"}"
        )

        for (json in missingFieldCases) {
            try {
                Messages.decode(json)
                fail("Missing field document must be rejected: $json")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun wrongFieldTypesAreRejected() {
        val wrongTypeCases = listOf(
            // Hello v is string
            "{\"type\":\"hello\",\"v\":\"1\"}",
            // Request id is number
            "{\"type\":\"request\",\"id\":123,\"tool\":\"ping\",\"args\":{}}",
            // Request tool is number
            "{\"type\":\"request\",\"id\":\"r1\",\"tool\":123,\"args\":{}}",
            // Request args is array instead of object
            "{\"type\":\"request\",\"id\":\"r1\",\"tool\":\"ping\",\"args\":[]}",
            // Cancel id is boolean
            "{\"type\":\"cancel\",\"id\":true}",
            // Progress data is string
            "{\"type\":\"progress\",\"id\":\"r1\",\"data\":\"string\"}",
            // Result data is number
            "{\"type\":\"result\",\"id\":\"r1\",\"data\":42}",
            // Error code is number
            "{\"type\":\"error\",\"id\":\"r1\",\"code\":500,\"message\":\"bad\"}",
            // Error message is object
            "{\"type\":\"error\",\"id\":\"r1\",\"code\":\"failed\",\"message\":{}}"
        )

        for (json in wrongTypeCases) {
            try {
                Messages.decode(json)
                fail("Wrong field type document must be rejected: $json")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        val json = "{\"type\":\"hello\",\"v\":1,\"client\":\"desktop\",\"extra\":[1,2,3]}"
        val msg = Messages.decode(json)
        assertEquals(Message.Hello(1), msg)

        val reqJson = "{\"type\":\"request\",\"id\":\"r1\",\"tool\":\"ping\",\"args\":{},\"traceId\":\"xyz\"}"
        val req = Messages.decode(reqJson)
        assertEquals(Message.Request("r1", "ping", JsonObject(emptyMap())), req)
    }

    @Test
    fun helloVersionRangeEnforcement() {
        // v = 1 is accepted
        val hello1 = Messages.decode("{\"type\":\"hello\",\"v\":1}")
        assertEquals(Message.Hello(1), hello1)

        // v = 2147483647 is accepted (Int.MAX_VALUE)
        val helloMax = Messages.decode("{\"type\":\"hello\",\"v\":2147483647}")
        assertEquals(Message.Hello(Int.MAX_VALUE), helloMax)

        // Rejected values
        val rejectedVersions = listOf(
            0L,
            -1L,
            2147483648L, // Int.MAX_VALUE + 1
            4294967296L, // 2^32
            4294967297L  // 2^32 + 1
        )

        for (badV in rejectedVersions) {
            try {
                Messages.decode("{\"type\":\"hello\",\"v\":$badV}")
                fail("Version $badV must be rejected")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun nonObjectMessageIsRejected() {
        val nonObjects = listOf(
            "\"string\"",
            "[1, 2, 3]",
            "123",
            "true",
            "null"
        )
        for (item in nonObjects) {
            try {
                Messages.decode(item)
                fail("Non-object message must be rejected: $item")
            } catch (e: ProtocolException) {
                assertEquals(Protocol.BAD_REQUEST, e.code)
            }
        }
    }

    @Test
    fun jsonObjectArgumentHelpersBehaveCorrectly() {
        val obj = JsonObject(
            "str" to JsonString("hello"),
            "num" to JsonNumber(42L),
            "bool" to JsonBool(true),
            "obj" to JsonObject("k" to JsonString("v")),
            "nil" to JsonNull
        )

        // requireString
        assertEquals("hello", obj.requireString("str"))
        try {
            obj.requireString("missing")
            fail("requireString on missing key must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("missing"))
        }
        try {
            obj.requireString("num")
            fail("requireString on non-string must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("num"))
        }

        // optString
        assertEquals("hello", obj.optString("str"))
        assertNull(obj.optString("missing"))
        assertEquals("default", obj.optString("missing", "default"))
        assertNull(obj.optString("nil"))
        assertEquals("default", obj.optString("nil", "default"))
        try {
            obj.optString("num")
            fail("optString on non-string must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
        }

        // requireLong
        assertEquals(42L, obj.requireLong("num"))
        assertEquals(42L, obj.requireLong("num", min = 10, max = 50))
        try {
            obj.requireLong("missing")
            fail("requireLong on missing key must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("missing"))
        }
        try {
            obj.requireLong("str")
            fail("requireLong on non-number must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
        }
        try {
            obj.requireLong("num", min = 50)
            fail("requireLong below min must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("num"))
        }
        try {
            obj.requireLong("num", max = 30)
            fail("requireLong above max must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("num"))
        }

        // optLong
        assertEquals(42L, obj.optLong("num"))
        assertNull(obj.optLong("missing"))
        assertEquals(100L, obj.optLong("missing", 100L))
        assertNull(obj.optLong("nil"))
        assertEquals(100L, obj.optLong("nil", 100L))
        try {
            obj.optLong("num", min = 50)
            fail("optLong below min must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
        }
        try {
            obj.optLong("num", max = 30)
            fail("optLong above max must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
        }

        // optBoolean
        assertTrue(obj.optBoolean("bool"))
        assertFalse(obj.optBoolean("missing"))
        assertTrue(obj.optBoolean("missing", default = true))
        assertFalse(obj.optBoolean("nil"))
        assertTrue(obj.optBoolean("nil", default = true))
        try {
            obj.optBoolean("num")
            fail("optBoolean on non-boolean must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("num"))
        }

        // requireObject
        assertEquals("v", obj.requireObject("obj").requireString("k"))
        try {
            obj.requireObject("missing")
            fail("requireObject on missing key must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("missing"))
        }
        try {
            obj.requireObject("str")
            fail("requireObject on non-object must throw")
        } catch (e: ProtocolException) {
            assertEquals(Protocol.BAD_ARGS, e.code)
            assertTrue(e.message!!.contains("str"))
        }
    }
}
