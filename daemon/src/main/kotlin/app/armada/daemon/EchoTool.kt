package app.armada.daemon

import app.armada.proto.JsonObject
import app.armada.proto.JsonNumber
import app.armada.proto.JsonString
import app.armada.proto.Protocol
import app.armada.proto.ProtocolException

class EchoTool(
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : Tool {
    override val name: String = "echo"

    override fun run(args: JsonObject, ctx: ToolContext): JsonObject {
        val text = args.requireString("text")
        if (text.length > 4096) {
            throw ProtocolException(Protocol.BAD_ARGS, "text length exceeds 4096 characters")
        }
        val delayMs = args.optLong("delayMs", default = 0L, min = 0L, max = 10000L) ?: 0L

        if (delayMs > 0L) {
            val start = clock()
            var elapsed = 0L
            while (elapsed < delayMs) {
                if (ctx.isCancelled || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("cancelled")
                }
                val step = minOf(10L, delayMs - elapsed)
                sleeper(step)
                elapsed = clock() - start
            }
            if (ctx.isCancelled || Thread.currentThread().isInterrupted) {
                throw InterruptedException("cancelled")
            }
            ctx.progress(
                JsonObject(
                    "seq" to JsonNumber(1L),
                    "elapsedMs" to JsonNumber(elapsed)
                )
            )
        }

        return JsonObject("text" to JsonString(text))
    }
}
