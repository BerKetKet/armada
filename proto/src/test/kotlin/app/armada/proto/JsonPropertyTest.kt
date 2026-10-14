package app.armada.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class JsonPropertyTest {

    private val SEED: Long = 20261014L

    @Test
    fun roundTripPropertyTest() {
        val random = Random(SEED)
        try {
            for (i in 0 until 10_000) {
                val value = generateRandomValue(depth = 0, random = random)
                val encoded = JsonWriter.write(value)
                val decoded = JsonParser.parse(encoded)
                assertEquals("Round-trip failed at iteration $i for value $value (encoded: $encoded)", value, decoded)
            }
        } catch (t: Throwable) {
            System.err.println("Round-trip test failed with seed $SEED")
            throw t
        }
    }

    @Test(timeout = 30_000)
    fun mutationRobustnessTest() {
        val random = Random(SEED + 1)
        val seedDocuments = listOf(
            "{\"id\":\"r1\",\"tool\":\"ping\",\"args\":{\"host\":\"1.1.1.1\",\"count\":4}}",
            "{\"type\":\"result\",\"id\":\"req_42\",\"data\":{\"sent\":10,\"rtt\":[12.3,14.5,11.8]}}",
            "{\"name\":\"armada\",\"tags\":[\"root\",\"network\"],\"active\":true,\"empty\":null}",
            "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]",
            "\"A simple string with some \\\"escapes\\\" and symbols @#$\"",
            "123456789012345",
            "-98765.4321e-5",
            "{\"nested\":{\"level1\":{\"level2\":{\"level3\":[true,false,null]}}}}"
        )

        val mutateChars = " \t\r\n{}[]\":,0123456789abcdefABCDEF-+.eE\\\"/\u0000\u001F\uFFFF"

        try {
            for (i in 0 until 20_000) {
                val base = seedDocuments[random.nextInt(seedDocuments.size)]
                val mutated = mutate(base, random, mutateChars)

                try {
                    JsonParser.parse(mutated)
                    // Either it parses successfully into a valid JsonValue...
                } catch (e: JsonException) {
                    // ...or it fails with a JsonException. Both are acceptable.
                } catch (t: Throwable) {
                    fail("Mutation test failed at iteration $i: threw ${t.javaClass.name} instead of JsonException for input: '$mutated'")
                }
            }
        } catch (t: Throwable) {
            System.err.println("Mutation test failed with seed ${SEED + 1}")
            throw t
        }
    }

    private fun mutate(base: String, random: Random, mutateChars: String): String {
        val type = random.nextInt(4)
        val sb = java.lang.StringBuilder(base)

        when (type) {
            0 -> {
                // Char flip/replacement
                if (sb.isNotEmpty()) {
                    val idx = random.nextInt(sb.length)
                    sb.setCharAt(idx, mutateChars[random.nextInt(mutateChars.length)])
                }
            }
            1 -> {
                // Truncation
                if (sb.isNotEmpty()) {
                    val cut = random.nextInt(sb.length)
                    sb.setLength(cut)
                }
            }
            2 -> {
                // Insertion
                val idx = if (sb.isEmpty()) 0 else random.nextInt(sb.length)
                val char = mutateChars[random.nextInt(mutateChars.length)]
                sb.insert(idx, char)
            }
            3 -> {
                // Bit flip on a random char code
                if (sb.isNotEmpty()) {
                    val idx = random.nextInt(sb.length)
                    val flipped = (sb[idx].code xor (1 shl random.nextInt(8))).toChar()
                    sb.setCharAt(idx, flipped)
                }
            }
        }
        return sb.toString()
    }

    private fun generateRandomValue(depth: Int, random: Random): JsonValue {
        val kind = if (depth >= 4) {
            random.nextInt(4) // Only primitives
        } else {
            random.nextInt(6) // Primitives + array + object
        }

        return when (kind) {
            0 -> JsonNull
            1 -> JsonBool(random.nextBoolean())
            2 -> {
                if (random.nextBoolean()) {
                    // Long
                    val l = when (random.nextInt(4)) {
                        0 -> 0L
                        1 -> random.nextInt(100).toLong()
                        2 -> random.nextLong()
                        else -> -random.nextInt(100).toLong()
                    }
                    JsonNumber(l)
                } else {
                    // Double (avoid -0.0 and Infinity/NaN)
                    var d = (random.nextDouble() - 0.5) * 1000.0
                    if (d == -0.0) d = 0.0
                    JsonNumber(d)
                }
            }
            3 -> {
                // String
                val len = random.nextInt(15)
                val sb = StringBuilder()
                for (i in 0 until len) {
                    when (random.nextInt(5)) {
                        0 -> sb.append(('a'..'z').random())
                        1 -> sb.append(('A'..'Z').random())
                        2 -> sb.append(('0'..'9').random())
                        3 -> sb.append(" _-./:")
                        else -> sb.append("\\\"")
                    }
                }
                JsonString(sb.toString())
            }
            4 -> {
                // Array
                val size = random.nextInt(4)
                val elements = ArrayList<JsonValue>(size)
                for (i in 0 until size) {
                    elements.add(generateRandomValue(depth + 1, random))
                }
                JsonArray(elements)
            }
            else -> {
                // Object
                val size = random.nextInt(4)
                val map = LinkedHashMap<String, JsonValue>(size)
                for (i in 0 until size) {
                    val key = "key_${depth}_$i"
                    map[key] = generateRandomValue(depth + 1, random)
                }
                JsonObject(map)
            }
        }
    }
}
