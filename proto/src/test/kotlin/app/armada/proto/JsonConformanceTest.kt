package app.armada.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonConformanceTest {

    private val validDocuments = listOf(
        // 1. Primitive null
        "null",
        // 2. Booleans
        "true",
        "false",
        // 3. Simple integers
        "0",
        "-0",
        "1",
        "-1",
        "42",
        "-42",
        "1234567890",
        // 4. Boundary integers (Long range)
        "9223372036854775807",
        "-9223372036854775808",
        // 5. Floating-point numbers
        "0.0",
        "-0.0",
        "0.12345",
        "123.456",
        "-123.456",
        // 6. Scientific notation numbers
        "0e0",
        "1e5",
        "1E5",
        "1e+5",
        "1e-5",
        "1.5e10",
        "-1.5e-10",
        // 7. Simple strings
        "\"\"",
        "\"hello\"",
        "\"hello world\"",
        // 8. Escaped characters in strings
        "\"\\\"\"",
        "\"\\\\\"",
        "\"\\/\"",
        "\"\\b\"",
        "\"\\f\"",
        "\"\\n\"",
        "\"\\r\"",
        "\"\\t\"",
        // 9. Unicode escapes
        "\"\\u0000\"",
        "\"\\u0020\"",
        "\"\\u0041\"",
        "\"\\uFFFF\"",
        "\"\\u00e9\"",
        // 10. Surrogate pairs
        "\"\\uD83D\\uDE00\"", // 😀 emoji
        "\"\\uD834\\uDD1E\"", // 𝄞 musical symbol
        // 11. Empty containers
        "[]",
        "{}",
        // 12. Arrays
        "[1]",
        "[1, 2, 3]",
        "[\"a\", \"b\", \"c\"]",
        "[true, false, null]",
        "[[1, 2], [3, 4]]",
        // 13. Objects
        "{\"a\": 1}",
        "{\"a\": 1, \"b\": 2}",
        "{\"k\": \"v\", \"n\": null, \"b\": true}",
        "{\"nested\": {\"inner\": [1, 2, 3]}}",
        // 14. Whitespace variations (space, tab, LF, CR)
        "  123  ",
        "\t\t[1,2]\t\t",
        "\n{\n\"a\":\n1\n}\n",
        "\r\n{\"x\": 1}\r\n",
        " [ { \"a\" : [ 1 , true , null ] } ] "
    )

    private val invalidDocuments = listOf(
        // 1. Empty or whitespace only
        "",
        "   ",
        "\t\n\r",
        // 2. Trailing characters after value
        "1 2",
        "\"hello\" world",
        "{} extra",
        "[] []",
        "true false",
        "null null",
        // 3. Duplicate object keys
        "{\"a\": 1, \"a\": 2}",
        "{\"x\": 1, \"y\": 2, \"x\": 3}",
        // 4. Invalid numbers: leading zeros
        "01",
        "00",
        "-01",
        "-00",
        // 5. Invalid numbers: leading plus
        "+1",
        "+0",
        "+1.5",
        // 6. Invalid numbers: leading / trailing decimal point
        ".5",
        "-.5",
        "1.",
        "-1.",
        "0.",
        // 7. Invalid numbers: bare minus / plus
        "-",
        "+",
        // 8. Invalid numbers: hex
        "0x10",
        "0XFF",
        // 9. Invalid numbers: incomplete exponents
        "1e",
        "1e+",
        "1e-",
        "1E",
        // 10. Invalid numbers: integer outside Long range
        "9223372036854775808",
        "-9223372036854775809",
        "10000000000000000000",
        // 11. Invalid numbers: overflow to infinity
        "1e9999",
        "-1e9999",
        // 12. Invalid characters attached to number
        "1a",
        "123_456",
        // 13. Invalid strings: unescaped control characters < 0x20
        "\"\n\"",
        "\"\r\"",
        "\"\t\"",
        "\"\u0000\"",
        "\"\u001F\"",
        // 14. Invalid strings: invalid escapes
        "\"\\a\"",
        "\"\\x12\"",
        "\"\\'\"",
        "\"\\v\"",
        "\"\\?\"",
        // 15. Invalid strings: incomplete / invalid unicode escapes
        "\"\\u123\"",
        "\"\\u123Z\"",
        "\"\\u\"",
        // 16. Invalid strings: lone surrogates
        "\"\\uD800\"", // lone high surrogate
        "\"\\uD83D\"", // lone high surrogate
        "\"\\uDC00\"", // lone low surrogate
        "\"\\uDFFF\"", // lone low surrogate
        "\"\\uD83D\\u0041\"", // high surrogate followed by non-surrogate
        // 17. Invalid strings: unterminated
        "\"hello",
        "\"",
        // 18. Invalid objects
        "{",
        "}",
        "{\"a\"}",
        "{\"a\":}",
        "{\"a\": 1,}", // trailing comma
        "{, \"a\": 1}", // leading comma
        "{1: 2}", // non-string key
        "{true: 2}", // non-string key
        "{a: 1}", // unquoted key
        "{\"a\": 1 \"b\": 2}", // missing comma
        // 19. Invalid arrays
        "[",
        "]",
        "[1,]", // trailing comma
        "[,1]", // leading comma
        "[1 2]", // missing comma
        // 20. Invalid literals / case sensitivity
        "True",
        "False",
        "Null",
        "TRUE",
        "FALSE",
        "NULL",
        "truex",
        "falsey",
        "nullz"
    )

    @Test
    fun conformanceTableMeetsMinimumCounts() {
        assertTrue("Valid documents count must be >= 40 (was ${validDocuments.size})", validDocuments.size >= 40)
        assertTrue("Invalid documents count must be >= 40 (was ${invalidDocuments.size})", invalidDocuments.size >= 40)
    }

    @Test
    fun allValidDocumentsParseSuccessfully() {
        for ((index, doc) in validDocuments.withIndex()) {
            try {
                val value = JsonParser.parse(doc)
                assertNotNull("Parsed value must not be null for doc $index: $doc", value)
            } catch (e: Exception) {
                fail("Valid document $index failed to parse: '$doc'\nError: ${e.message}")
            }
        }
    }

    @Test
    fun allInvalidDocumentsFailWithJsonException() {
        for ((index, doc) in invalidDocuments.withIndex()) {
            try {
                JsonParser.parse(doc)
                fail("Invalid document $index was accepted: '$doc'")
            } catch (e: JsonException) {
                // Expected, position must be valid (>= 0)
                assertTrue(
                    "JsonException must carry a valid position for doc $index: '$doc'",
                    e.position >= 0
                )
            } catch (t: Throwable) {
                fail("Invalid document $index threw ${t.javaClass.name} instead of JsonException: '$doc'")
            }
        }
    }

    @Test
    fun depth64IsAcceptedAndDepth65IsRejected() {
        // Construct array of depth 64: 64 '[' followed by 64 ']'
        val depth64 = "[".repeat(64) + "]".repeat(64)
        val value64 = JsonParser.parse(depth64)
        assertNotNull(value64)

        // Construct array of depth 65: 65 '[' followed by 65 ']'
        val depth65 = "[".repeat(65) + "]".repeat(65)
        try {
            JsonParser.parse(depth65)
            fail("Nesting depth 65 must be rejected")
        } catch (e: JsonException) {
            assertTrue("Exception message should indicate nesting depth: ${e.message}", e.message!!.contains("depth"))
        }
    }

    @Test
    fun exactly1MiBIsAcceptedAndOneByteMoreIsRejected() {
        // 1 MiB = 1,048,576 bytes
        // Construct valid JSON string of exactly 1 MiB:
        // '"' + 1,048,574 'a's + '"' = 1,048,576 bytes
        val validContent = "a".repeat(1_048_574)
        val exact1MiB = "\"$validContent\""
        assertEquals(1_048_576, exact1MiB.length)

        val parsed = JsonParser.parse(exact1MiB)
        assertTrue(parsed is JsonString)
        assertEquals(1_048_574, (parsed as JsonString).value.length)

        // One byte more: 1,048,577 bytes
        val oneByteMore = "\"" + "a".repeat(1_048_575) + "\""
        assertEquals(1_048_577, oneByteMore.length)

        try {
            JsonParser.parse(oneByteMore)
            fail("Input of 1,048,577 bytes must be rejected")
        } catch (e: JsonException) {
            assertTrue(
                "Exception message should mention size limit: ${e.message}",
                e.message!!.contains("limit") || e.message!!.contains("exceeds")
            )
        }
    }

    @Test
    fun writerRejectsLoneHighSurrogate() {
        val badStrings = listOf(
            "\uD800",
            "\uD83D",
            "\uD800abc",
            "abc\uD800",
            "\uD83D\u0041", // high surrogate followed by non-surrogate
            "\uD800\uD800"  // two consecutive high surrogates
        )
        for (bad in badStrings) {
            try {
                JsonWriter.write(JsonString(bad))
                fail("JsonWriter.write must reject lone high surrogate: $bad")
            } catch (e: JsonException) {
                assertTrue("JsonException must have valid position", e.position >= 0)
            }
        }
    }

    @Test
    fun writerRejectsLoneLowSurrogate() {
        val badStrings = listOf(
            "\uDC00",
            "\uDFFF",
            "\uDC00abc",
            "abc\uDC00",
            "\uDC00\uD800" // low surrogate followed by high surrogate
        )
        for (bad in badStrings) {
            try {
                JsonWriter.write(JsonString(bad))
                fail("JsonWriter.write must reject lone low surrogate: $bad")
            } catch (e: JsonException) {
                assertTrue("JsonException must have valid position", e.position >= 0)
            }
        }
    }

    @Test
    fun writerAcceptsValidSurrogatePairAndRoundTrips() {
        // U+1F600 (GRINNING FACE) represented as UTF-16 surrogate pair
        val emoji = "\uD83D\uDE00"
        val written = JsonWriter.write(JsonString(emoji))
        assertEquals("\"$emoji\"", written)

        val parsed = JsonParser.parse(written)
        assertEquals(JsonString(emoji), parsed)

        val rewritten = JsonWriter.write(parsed)
        assertEquals(written, rewritten)
    }

    @Test
    fun writerKeepsU2028AndU2029EscapedAndEqualOnRoundTrip() {
        val s = "line\u2028separator and \u2029paragraph"
        val written = JsonWriter.write(JsonString(s))
        assertTrue(written.contains("\\u2028"))
        assertTrue(written.contains("\\u2029"))

        val parsed = JsonParser.parse(written)
        assertEquals(JsonString(s), parsed)

        val rewritten = JsonWriter.write(parsed)
        assertEquals(written, rewritten)
    }
}
