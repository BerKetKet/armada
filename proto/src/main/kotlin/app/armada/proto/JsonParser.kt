package app.armada.proto

object JsonParser {
    const val MAX_INPUT_BYTES: Int = 1_048_576 // 1 MiB
    const val MAX_DEPTH: Int = 64

    fun parse(input: String): JsonValue {
        try {
            return Parser(input).parse()
        } catch (e: JsonException) {
            throw e
        } catch (e: StackOverflowError) {
            throw JsonException("Stack overflow", -1, e)
        } catch (e: IndexOutOfBoundsException) {
            throw JsonException("Unexpected end of input", input.length, e)
        } catch (e: NumberFormatException) {
            throw JsonException("Number format error: ${e.message}", -1, e)
        } catch (t: Throwable) {
            throw JsonException("Parse error: ${t.message}", -1, t)
        }
    }

    private class Parser(private val input: String) {
        private val len = input.length
        private var pos = 0
        private var depth = 0

        fun parse(): JsonValue {
            // 1. Verify byte size limit (1 MiB)
            var byteCount = 0
            for (i in 0 until len) {
                val c = input[i].code
                val b = when {
                    c <= 0x7F -> 1
                    c <= 0x7FF -> 2
                    c in 0xD800..0xDFFF -> 2 // Surrogate pair halves
                    else -> 3
                }
                byteCount += b
                if (byteCount > MAX_INPUT_BYTES) {
                    throw JsonException("Input size exceeds maximum limit of $MAX_INPUT_BYTES bytes", i)
                }
            }

            skipWhitespace()
            if (pos >= len) {
                throw JsonException("Empty JSON input", 0)
            }

            val value = parseValue()
            skipWhitespace()
            if (pos < len) {
                throw JsonException("Trailing characters after JSON value", pos)
            }
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= len) {
                throw JsonException("Unexpected end of input", pos)
            }
            return when (val ch = input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonException("Unexpected character '$ch'", pos)
            }
        }

        private fun parseObject(): JsonObject {
            val startPos = pos
            expect('{')
            depth++
            if (depth > MAX_DEPTH) {
                throw JsonException("Nesting depth exceeds maximum of $MAX_DEPTH", pos - 1)
            }

            skipWhitespace()
            if (pos < len && input[pos] == '}') {
                pos++
                depth--
                return JsonObject(emptyMap())
            }

            val map = LinkedHashMap<String, JsonValue>()
            while (pos < len) {
                skipWhitespace()
                if (pos >= len || input[pos] != '"') {
                    throw JsonException("Expected string key in object", pos)
                }
                val keyPos = pos
                val key = parseString()
                if (map.containsKey(key)) {
                    throw JsonException("Duplicate object key '$key'", keyPos)
                }

                skipWhitespace()
                if (pos >= len || input[pos] != ':') {
                    throw JsonException("Expected ':' after object key", pos)
                }
                pos++ // consume ':'

                skipWhitespace()
                val value = parseValue()
                map[key] = value

                skipWhitespace()
                if (pos >= len) {
                    throw JsonException("Unterminated object starting at position $startPos", startPos)
                }

                val ch = input[pos]
                if (ch == '}') {
                    pos++
                    depth--
                    return JsonObject(map)
                } else if (ch == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < len && input[pos] == '}') {
                        throw JsonException("Trailing comma in object", pos - 1)
                    }
                } else {
                    throw JsonException("Expected ',' or '}' in object, found '$ch'", pos)
                }
            }
            throw JsonException("Unterminated object starting at position $startPos", startPos)
        }

        private fun parseArray(): JsonArray {
            val startPos = pos
            expect('[')
            depth++
            if (depth > MAX_DEPTH) {
                throw JsonException("Nesting depth exceeds maximum of $MAX_DEPTH", pos - 1)
            }

            skipWhitespace()
            if (pos < len && input[pos] == ']') {
                pos++
                depth--
                return JsonArray(emptyList())
            }

            val list = ArrayList<JsonValue>()
            while (pos < len) {
                skipWhitespace()
                val value = parseValue()
                list.add(value)

                skipWhitespace()
                if (pos >= len) {
                    throw JsonException("Unterminated array starting at position $startPos", startPos)
                }

                val ch = input[pos]
                if (ch == ']') {
                    pos++
                    depth--
                    return JsonArray(list)
                } else if (ch == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < len && input[pos] == ']') {
                        throw JsonException("Trailing comma in array", pos - 1)
                    }
                } else {
                    throw JsonException("Expected ',' or ']' in array, found '$ch'", pos)
                }
            }
            throw JsonException("Unterminated array starting at position $startPos", startPos)
        }

        private fun parseString(): String {
            val startPos = pos
            expect('"')
            val sb = StringBuilder()

            while (pos < len) {
                val ch = input[pos]
                if (ch == '"') {
                    pos++
                    return sb.toString()
                }
                if (ch < ' ') {
                    throw JsonException("Raw control character 0x${Integer.toHexString(ch.code)} in string", pos)
                }
                if (ch == '\\') {
                    pos++
                    if (pos >= len) {
                        throw JsonException("Unterminated escape sequence in string", pos)
                    }
                    val esc = input[pos++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val uStart = pos - 2
                            val code = parseHex4()
                            val char = code.toChar()
                            if (char in '\uD800'..'\uDBFF') {
                                // High surrogate: must be immediately followed by \uXXXX low surrogate
                                if (pos + 2 <= len && input[pos] == '\\' && input[pos + 1] == 'u') {
                                    pos += 2
                                    val lowCode = parseHex4()
                                    val lowChar = lowCode.toChar()
                                    if (lowChar in '\uDC00'..'\uDFFF') {
                                        sb.append(char)
                                        sb.append(lowChar)
                                    } else {
                                        throw JsonException("Expected low surrogate after high surrogate", pos - 6)
                                    }
                                } else {
                                    throw JsonException("Lone high surrogate in unicode escape", uStart)
                                }
                            } else if (char in '\uDC00'..'\uDFFF') {
                                throw JsonException("Lone low surrogate in unicode escape", uStart)
                            } else {
                                sb.append(char)
                            }
                        }
                        else -> throw JsonException("Invalid escape sequence '\\$esc' in string", pos - 2)
                    }
                } else if (ch in '\uD800'..'\uDBFF') {
                    // Raw UTF-16 surrogate in string
                    if (pos + 1 < len && input[pos + 1] in '\uDC00'..'\uDFFF') {
                        sb.append(ch)
                        sb.append(input[pos + 1])
                        pos += 2
                    } else {
                        throw JsonException("Lone high surrogate character in string", pos)
                    }
                } else if (ch in '\uDC00'..'\uDFFF') {
                    throw JsonException("Lone low surrogate character in string", pos)
                } else {
                    sb.append(ch)
                    pos++
                }
            }
            throw JsonException("Unterminated string starting at position $startPos", startPos)
        }

        private fun parseHex4(): Int {
            if (pos + 4 > len) {
                throw JsonException("Incomplete \\uXXXX escape", pos)
            }
            var value = 0
            for (i in 0 until 4) {
                val ch = input[pos++]
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> throw JsonException("Invalid hex digit '$ch' in \\uXXXX escape", pos - 1)
                }
                value = (value shl 4) or digit
            }
            return value
        }

        private fun parseNumber(): JsonNumber {
            val startPos = pos
            if (input[pos] == '-') {
                pos++
            }
            if (pos >= len) {
                throw JsonException("Bare '-' is not a valid number", startPos)
            }

            val firstDigit = input[pos++]
            if (firstDigit !in '0'..'9') {
                throw JsonException("Expected digit in number", pos - 1)
            }

            if (firstDigit == '0') {
                if (pos < len && input[pos] in '0'..'9') {
                    throw JsonException("Leading zeros are not allowed in numbers", pos)
                }
            } else {
                while (pos < len && input[pos] in '0'..'9') {
                    pos++
                }
            }

            var isDouble = false

            // Fraction
            if (pos < len && input[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= len || input[pos] !in '0'..'9') {
                    throw JsonException("Expected digit after decimal point", pos)
                }
                while (pos < len && input[pos] in '0'..'9') {
                    pos++
                }
            }

            // Exponent
            if (pos < len && (input[pos] == 'e' || input[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < len && (input[pos] == '+' || input[pos] == '-')) {
                    pos++
                }
                if (pos >= len || input[pos] !in '0'..'9') {
                    throw JsonException("Expected digit in exponent", pos)
                }
                while (pos < len && input[pos] in '0'..'9') {
                    pos++
                }
            }

            // Verify trailing character is a valid delimiter
            if (pos < len) {
                val next = input[pos]
                if (next !in " \t\r\n,]}" ) {
                    throw JsonException("Unexpected character '$next' after number", pos)
                }
            }

            val numStr = input.substring(startPos, pos)

            if (isDouble) {
                val d = try {
                    java.lang.Double.parseDouble(numStr)
                } catch (e: Exception) {
                    throw JsonException("Invalid double format: $numStr", startPos, e)
                }
                if (d.isNaN() || d.isInfinite()) {
                    throw JsonException("Number out of range (NaN/Infinity): $numStr", startPos)
                }
                return JsonNumber(d)
            } else {
                val l = numStr.toLongOrNull()
                    ?: throw JsonException("Integer outside Long range: $numStr", startPos)
                return JsonNumber(l)
            }
        }

        private fun parseBool(): JsonBool {
            return if (input.startsWith("true", pos)) {
                verifyLiteralBoundary("true")
                pos += 4
                JsonBool(true)
            } else if (input.startsWith("false", pos)) {
                verifyLiteralBoundary("false")
                pos += 5
                JsonBool(false)
            } else {
                throw JsonException("Unexpected token at position $pos", pos)
            }
        }

        private fun parseNull(): JsonNull {
            if (input.startsWith("null", pos)) {
                verifyLiteralBoundary("null")
                pos += 4
                return JsonNull
            }
            throw JsonException("Unexpected token at position $pos", pos)
        }

        private fun verifyLiteralBoundary(literal: String) {
            val endPos = pos + literal.length
            if (endPos < len) {
                val ch = input[endPos]
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_') {
                    throw JsonException("Unexpected character '$ch' after literal '$literal'", endPos)
                }
            }
        }

        private fun skipWhitespace() {
            while (pos < len) {
                val ch = input[pos]
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                    pos++
                } else {
                    break
                }
            }
        }

        private fun expect(expected: Char) {
            if (pos >= len || input[pos] != expected) {
                throw JsonException("Expected '$expected'", pos)
            }
            pos++
        }
    }
}
