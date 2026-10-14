package app.armada.proto

sealed class JsonValue

object JsonNull : JsonValue() {
    override fun toString(): String = "null"
}

data class JsonBool(val value: Boolean) : JsonValue() {
    override fun toString(): String = value.toString()
}

data class JsonNumber(
    val longValue: Long? = null,
    val doubleValue: Double? = null
) : JsonValue() {
    init {
        require((longValue != null) xor (doubleValue != null)) {
            "JsonNumber must hold either a Long or a Double"
        }
        if (doubleValue != null) {
            require(!doubleValue.isNaN() && !doubleValue.isInfinite()) {
                "NaN and Infinity are not allowed in JSON"
            }
        }
    }

    constructor(value: Long) : this(value, null)
    constructor(value: Double) : this(null, value)

    val isLong: Boolean get() = longValue != null
    val isDouble: Boolean get() = doubleValue != null

    val asLong: Long
        get() = longValue ?: doubleValue!!.toLong()

    val asDouble: Double
        get() = doubleValue ?: longValue!!.toDouble()

    override fun toString(): String = longValue?.toString() ?: doubleValue.toString()
}

data class JsonString(val value: String) : JsonValue() {
    override fun toString(): String = value
}

data class JsonArray(val elements: List<JsonValue> = emptyList()) : JsonValue(), Iterable<JsonValue> by elements {
    val size: Int get() = elements.size
    operator fun get(index: Int): JsonValue = elements[index]
    override fun toString(): String = elements.toString()
}

data class JsonObject(val entries: Map<String, JsonValue> = emptyMap()) : JsonValue() {
    constructor(vararg pairs: Pair<String, JsonValue>) : this(linkedMapOf(*pairs))

    val size: Int get() = entries.size
    operator fun get(key: String): JsonValue? = entries[key]
    fun containsKey(key: String): Boolean = entries.containsKey(key)
    val keys: Set<String> get() = entries.keys
    val values: Collection<JsonValue> get() = entries.values

    override fun toString(): String = entries.toString()

    fun requireString(key: String): String {
        val v = get(key) ?: throw ProtocolException(Protocol.BAD_ARGS, "Missing required argument '$key'")
        if (v is JsonString) return v.value
        throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be a string")
    }

    fun optString(key: String, default: String? = null): String? {
        val v = get(key) ?: return default
        if (v is JsonNull) return default
        if (v is JsonString) return v.value
        throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be a string")
    }

    fun requireLong(key: String, min: Long? = null, max: Long? = null): Long {
        val v = get(key) ?: throw ProtocolException(Protocol.BAD_ARGS, "Missing required argument '$key'")
        if (v !is JsonNumber || !v.isLong) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be an integer")
        }
        val n = v.longValue!!
        if (min != null && n < min) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be >= $min")
        }
        if (max != null && n > max) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be <= $max")
        }
        return n
    }

    fun optLong(key: String, default: Long? = null, min: Long? = null, max: Long? = null): Long? {
        val v = get(key) ?: return default
        if (v is JsonNull) return default
        if (v !is JsonNumber || !v.isLong) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be an integer")
        }
        val n = v.longValue!!
        if (min != null && n < min) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be >= $min")
        }
        if (max != null && n > max) {
            throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be <= $max")
        }
        return n
    }

    fun optBoolean(key: String, default: Boolean = false): Boolean {
        val v = get(key) ?: return default
        if (v is JsonNull) return default
        if (v is JsonBool) return v.value
        throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be a boolean")
    }

    fun requireObject(key: String): JsonObject {
        val v = get(key) ?: throw ProtocolException(Protocol.BAD_ARGS, "Missing required argument '$key'")
        if (v is JsonObject) return v
        throw ProtocolException(Protocol.BAD_ARGS, "Argument '$key' must be an object")
    }
}
