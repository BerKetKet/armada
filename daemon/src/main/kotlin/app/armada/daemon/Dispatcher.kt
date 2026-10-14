package app.armada.daemon

import app.armada.proto.JsonObject
import app.armada.proto.Protocol
import app.armada.proto.ProtocolException

class Dispatcher(private val tools: Map<String, Tool> = emptyMap()) {
    fun find(name: String): Tool? = tools[name]

    fun dispatch(toolName: String, args: JsonObject, ctx: ToolContext): JsonObject {
        val tool = tools[toolName]
            ?: throw ProtocolException(Protocol.UNKNOWN_TOOL, "unknown tool: $toolName")
        return tool.run(args, ctx)
    }
}
