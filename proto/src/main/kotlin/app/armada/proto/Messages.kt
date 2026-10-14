package app.armada.proto

sealed class Message {
    data class Hello(val v: Int) : Message()
    data class Request(val id: String, val tool: String, val args: JsonObject) : Message()
    data class Cancel(val id: String) : Message()
    data class Progress(val id: String, val data: JsonObject) : Message()
    data class Result(val id: String, val data: JsonObject) : Message()
    data class Failure(val id: String, val code: String, val message: String) : Message()
}

object Messages {
    private val ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val TOOL_REGEX = Regex("^[a-z][a-z0-9_.]{0,63}$")

    fun encode(message: Message): String {
        val obj = when (message) {
            is Message.Hello -> {
                if (message.v < 1) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Protocol version must be >= 1")
                }
                JsonObject(
                    "type" to JsonString("hello"),
                    "v" to JsonNumber(message.v.toLong())
                )
            }
            is Message.Request -> {
                validateId(message.id)
                validateTool(message.tool)
                JsonObject(
                    "type" to JsonString("request"),
                    "id" to JsonString(message.id),
                    "tool" to JsonString(message.tool),
                    "args" to message.args
                )
            }
            is Message.Cancel -> {
                validateId(message.id)
                JsonObject(
                    "type" to JsonString("cancel"),
                    "id" to JsonString(message.id)
                )
            }
            is Message.Progress -> {
                validateId(message.id)
                JsonObject(
                    "type" to JsonString("progress"),
                    "id" to JsonString(message.id),
                    "data" to message.data
                )
            }
            is Message.Result -> {
                validateId(message.id)
                JsonObject(
                    "type" to JsonString("result"),
                    "id" to JsonString(message.id),
                    "data" to message.data
                )
            }
            is Message.Failure -> {
                validateId(message.id)
                validateCode(message.code)
                JsonObject(
                    "type" to JsonString("error"),
                    "id" to JsonString(message.id),
                    "code" to JsonString(message.code),
                    "message" to JsonString(message.message)
                )
            }
        }
        return JsonWriter.write(obj)
    }

    fun decode(line: String): Message {
        val root = try {
            JsonParser.parse(line)
        } catch (e: JsonException) {
            throw ProtocolException(Protocol.BAD_REQUEST, e.message ?: "Invalid JSON", e)
        }

        if (root !is JsonObject) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Message must be a JSON object")
        }

        val typeVal = root["type"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'type' field")
        if (typeVal !is JsonString) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Field 'type' must be a string")
        }

        return when (val type = typeVal.value) {
            "hello" -> {
                val vVal = root["v"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'v' field")
                if (vVal !is JsonNumber || !vVal.isLong) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'v' must be an integer")
                }
                val vLong = vVal.longValue!!
                if (vLong < 1L || vLong > Int.MAX_VALUE.toLong()) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'v' must be between 1 and ${Int.MAX_VALUE}")
                }
                Message.Hello(vLong.toInt())
            }
            "request" -> {
                val id = extractId(root)
                val toolVal = root["tool"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'tool' field")
                if (toolVal !is JsonString) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'tool' must be a string")
                }
                val tool = toolVal.value
                validateTool(tool)
                val argsVal = root["args"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'args' field")
                if (argsVal !is JsonObject) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'args' must be an object")
                }
                Message.Request(id, tool, argsVal)
            }
            "cancel" -> {
                val id = extractId(root)
                Message.Cancel(id)
            }
            "progress" -> {
                val id = extractId(root)
                val dataVal = root["data"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'data' field")
                if (dataVal !is JsonObject) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'data' must be an object")
                }
                Message.Progress(id, dataVal)
            }
            "result" -> {
                val id = extractId(root)
                val dataVal = root["data"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'data' field")
                if (dataVal !is JsonObject) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'data' must be an object")
                }
                Message.Result(id, dataVal)
            }
            "error" -> {
                val id = extractId(root)
                val codeVal = root["code"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'code' field")
                if (codeVal !is JsonString) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'code' must be a string")
                }
                val code = codeVal.value
                validateCode(code)
                val msgVal = root["message"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'message' field")
                if (msgVal !is JsonString) {
                    throw ProtocolException(Protocol.BAD_REQUEST, "Field 'message' must be a string")
                }
                Message.Failure(id, code, msgVal.value)
            }
            else -> throw ProtocolException(Protocol.BAD_REQUEST, "Unknown message type '$type'")
        }
    }

    private fun extractId(obj: JsonObject): String {
        val idVal = obj["id"] ?: throw ProtocolException(Protocol.BAD_REQUEST, "Missing required 'id' field")
        if (idVal !is JsonString) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Field 'id' must be a string")
        }
        val id = idVal.value
        validateId(id)
        return id
    }

    private fun validateId(id: String) {
        if (!ID_REGEX.matches(id)) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Invalid message id '$id': must be 1..64 chars of [A-Za-z0-9._-]")
        }
    }

    private fun validateTool(tool: String) {
        if (!TOOL_REGEX.matches(tool)) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Invalid tool name '$tool': must match [a-z][a-z0-9_.]* and be 1..64 chars")
        }
    }

    private fun validateCode(code: String) {
        if (!Protocol.ERROR_CODES.contains(code)) {
            throw ProtocolException(Protocol.BAD_REQUEST, "Unknown error code '$code'")
        }
    }
}
