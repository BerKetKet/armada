package app.armada.proto

import java.io.IOException

/**
 * Thrown on any JSON syntax or structural parse failure.
 * Always carries the 0-based character position where the failure occurred.
 */
class JsonException(
    message: String,
    val position: Int = -1,
    cause: Throwable? = null
) : RuntimeException(if (position >= 0) "$message at position $position" else message, cause)

/**
 * Thrown on any protocol validation failure (bad request, invalid arguments, etc.).
 * Carries one of the closed set error codes defined in [Protocol].
 */
class ProtocolException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException("[$code] $message", cause)

/**
 * Thrown when an incoming frame line exceeds the configured maximum byte limit.
 */
class FrameTooLongException(
    message: String
) : IOException(message)

/**
 * Thrown when an input stream terminates in the middle of an unfinished frame line.
 */
class TruncatedFrameException(
    message: String
) : IOException(message)

/**
 * Thrown when a frame payload fails strict UTF-8 decoding.
 */
class MalformedFrameException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
