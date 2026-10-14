package app.armada.daemon

import app.armada.proto.JsonObject

interface ToolContext {
    val isCancelled: Boolean
    fun progress(data: JsonObject)
}

interface Tool {
    val name: String
    fun run(args: JsonObject, ctx: ToolContext): JsonObject
}
