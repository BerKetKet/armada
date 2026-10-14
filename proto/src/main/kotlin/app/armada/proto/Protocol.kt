package app.armada.proto

object Protocol {
    const val VERSION: Int = 1

    // Closed set of error codes
    const val BAD_REQUEST: String = "bad_request"
    const val UNKNOWN_TOOL: String = "unknown_tool"
    const val BAD_ARGS: String = "bad_args"
    const val DENIED: String = "denied"
    const val UNSUPPORTED: String = "unsupported"
    const val FAILED: String = "failed"
    const val TIMEOUT: String = "timeout"
    const val CANCELLED: String = "cancelled"
    const val INTERNAL: String = "internal"

    val ERROR_CODES: Set<String> = setOf(
        BAD_REQUEST,
        UNKNOWN_TOOL,
        BAD_ARGS,
        DENIED,
        UNSUPPORTED,
        FAILED,
        TIMEOUT,
        CANCELLED,
        INTERNAL,
    )
}
