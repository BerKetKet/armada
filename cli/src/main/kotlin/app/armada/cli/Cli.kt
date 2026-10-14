package app.armada.cli

import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import app.armada.proto.JsonNumber
import app.armada.proto.JsonObject
import app.armada.proto.JsonString
import app.armada.proto.JsonWriter
import app.armada.proto.Message
import app.armada.proto.Messages
import app.armada.proto.Protocol
import java.io.PrintStream

class Cli(
    private val socketProvider: SocketProvider,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err
) {
    companion object {
        const val SOCKET_NAME: String = "armada.daemon"

        const val EXIT_OK: Int = 0
        const val EXIT_ERROR: Int = 1
        const val EXIT_USAGE: Int = 2
    }

    fun run(args: Array<String>): Int {
        if (args.isEmpty()) {
            printUsage("No command specified.")
            return EXIT_USAGE
        }

        val command = args[0]
        return when (command) {
            "hello" -> handleHello(args)
            "echo" -> handleEcho(args)
            else -> {
                printUsage("Unknown command '$command'.")
                return EXIT_USAGE
            }
        }
    }

    private fun handleHello(args: Array<String>): Int {
        if (args.size != 1) {
            printUsage("The 'hello' command takes no additional arguments.")
            return EXIT_USAGE
        }

        return execute { reader, writer ->
            // Handshake: server sends Hello(v) first
            val serverHelloFrame = reader.readFrame()
                ?: throw IllegalStateException("Connection closed before greeting received")
            val serverMsg = Messages.decode(serverHelloFrame)
            if (serverMsg is Message.Failure) {
                printError(serverMsg)
                return@execute EXIT_ERROR
            }
            if (serverMsg !is Message.Hello) {
                printErrorJson("handshake", Protocol.BAD_REQUEST, "Expected hello from daemon, got: $serverHelloFrame")
                return@execute EXIT_ERROR
            }

            // Client sends Hello(Protocol.VERSION)
            writer.writeFrame(Messages.encode(Message.Hello(Protocol.VERSION)))

            // Print the result data as one JSON line to stdout and exit 0
            val resultData = JsonObject("v" to JsonNumber(serverMsg.v.toLong()))
            out.println(JsonWriter.write(resultData))
            EXIT_OK
        }
    }

    private fun handleEcho(args: Array<String>): Int {
        if (args.size < 2) {
            printUsage("Missing text argument for 'echo'. Usage: echo <text> [--delay-ms N]")
            return EXIT_USAGE
        }

        val text = args[1]
        var delayMs = 0L

        var i = 2
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--delay-ms" -> {
                    if (i + 1 >= args.size) {
                        printUsage("Missing value for --delay-ms.")
                        return EXIT_USAGE
                    }
                    val parsed = args[i + 1].toLongOrNull()
                    if (parsed == null || parsed < 0L) {
                        printUsage("Invalid value for --delay-ms: '${args[i + 1]}'. Must be a non-negative integer.")
                        return EXIT_USAGE
                    }
                    delayMs = parsed
                    i += 2
                }
                arg.startsWith("--delay-ms=") -> {
                    val rawVal = arg.substringAfter("--delay-ms=")
                    val parsed = rawVal.toLongOrNull()
                    if (parsed == null || parsed < 0L) {
                        printUsage("Invalid value for --delay-ms: '$rawVal'. Must be a non-negative integer.")
                        return EXIT_USAGE
                    }
                    delayMs = parsed
                    i += 1
                }
                else -> {
                    printUsage("Unknown option '$arg'. Usage: echo <text> [--delay-ms N]")
                    return EXIT_USAGE
                }
            }
        }

        return execute { reader, writer ->
            // Handshake
            val serverHelloFrame = reader.readFrame()
                ?: throw IllegalStateException("Connection closed before greeting received")
            val serverMsg = Messages.decode(serverHelloFrame)
            if (serverMsg is Message.Failure) {
                printError(serverMsg)
                return@execute EXIT_ERROR
            }
            if (serverMsg !is Message.Hello) {
                printErrorJson("handshake", Protocol.BAD_REQUEST, "Expected hello from daemon, got: $serverHelloFrame")
                return@execute EXIT_ERROR
            }
            writer.writeFrame(Messages.encode(Message.Hello(Protocol.VERSION)))

            // Send Request
            val reqId = "cli-" + System.currentTimeMillis()
            val toolArgs = if (delayMs > 0L) {
                JsonObject(
                    "text" to JsonString(text),
                    "delayMs" to JsonNumber(delayMs)
                )
            } else {
                JsonObject(
                    "text" to JsonString(text)
                )
            }
            writer.writeFrame(Messages.encode(Message.Request(id = reqId, tool = "echo", args = toolArgs)))

            // Read until terminal message
            while (true) {
                val line = reader.readFrame()
                    ?: throw IllegalStateException("Connection closed before response received")
                val msg = Messages.decode(line)
                when (msg) {
                    is Message.Progress -> {
                        // Incremental progress ignored by default CLI
                        continue
                    }
                    is Message.Result -> {
                        out.println(JsonWriter.write(msg.data))
                        return@execute EXIT_OK
                    }
                    is Message.Failure -> {
                        printError(msg)
                        return@execute EXIT_ERROR
                    }
                    else -> {
                        printErrorJson(reqId, Protocol.BAD_REQUEST, "Unexpected message: $line")
                        return@execute EXIT_ERROR
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            EXIT_OK
        }
    }

    private fun execute(action: (FrameReader, FrameWriter) -> Int): Int {
        val conn = try {
            socketProvider.open(SOCKET_NAME)
        } catch (e: Throwable) {
            printErrorJson("handshake", Protocol.FAILED, "Cannot connect to $SOCKET_NAME: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_ERROR
        }

        return try {
            val reader = FrameReader(conn.inputStream)
            val writer = FrameWriter(conn.outputStream)
            action(reader, writer)
        } catch (e: Throwable) {
            printErrorJson("handshake", Protocol.FAILED, e.message ?: e.javaClass.simpleName)
            EXIT_ERROR
        } finally {
            try {
                conn.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun printError(failure: Message.Failure) {
        val json = Messages.encode(failure)
        err.println(json)
    }

    private fun printErrorJson(id: String, code: String, message: String) {
        val json = Messages.encode(Message.Failure(id = id, code = code, message = message))
        err.println(json)
    }

    private fun printUsage(errorDetail: String? = null) {
        if (errorDetail != null) {
            err.println("Error: $errorDetail")
        }
        err.println("Usage: armada-cli <command> [arguments...]")
        err.println("Commands:")
        err.println("  hello                      Verify daemon connection and get protocol version")
        err.println("  echo <text> [--delay-ms N] Echo text with optional delay in milliseconds")
    }
}
