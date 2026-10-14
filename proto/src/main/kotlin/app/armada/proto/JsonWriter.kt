package app.armada.proto

object JsonWriter {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun write(value: JsonValue): String {
        val sb = StringBuilder()
        writeValue(value, sb)
        return sb.toString()
    }

    private fun writeValue(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> {
                if (value.isLong) {
                    sb.append(value.longValue)
                } else {
                    val d = value.doubleValue!!
                    if (d.isNaN() || d.isInfinite()) {
                        throw JsonException("NaN and Infinity cannot be encoded in JSON")
                    }
                    sb.append(d.toString())
                }
            }
            is JsonString -> writeString(value.value, sb)
            is JsonArray -> {
                sb.append('[')
                var first = true
                for (elem in value.elements) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(elem, sb)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k, sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
        }
    }

    fun writeString(str: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < str.length) {
            val ch = str[i]
            when {
                ch in '\uD800'..'\uDBFF' -> {
                    if (i + 1 < str.length && str[i + 1] in '\uDC00'..'\uDFFF') {
                        sb.append(ch)
                        sb.append(str[i + 1])
                        i++
                    } else {
                        throw JsonException("Lone high surrogate", position = i)
                    }
                }
                ch in '\uDC00'..'\uDFFF' -> {
                    throw JsonException("Lone low surrogate", position = i)
                }
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000c' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> appendHex4(ch.code, sb)
                ch == '\u2028' -> sb.append("\\u2028")
                ch == '\u2029' -> sb.append("\\u2029")
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    private fun appendHex4(code: Int, sb: StringBuilder) {
        sb.append("\\u")
        sb.append(HEX_CHARS[(code shr 12) and 0xF])
        sb.append(HEX_CHARS[(code shr 8) and 0xF])
        sb.append(HEX_CHARS[(code shr 4) and 0xF])
        sb.append(HEX_CHARS[code and 0xF])
    }
}
