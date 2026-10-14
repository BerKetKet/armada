package app.armada

import app.armada.proto.FrameReader
import app.armada.proto.FrameWriter
import app.armada.proto.Message
import app.armada.proto.Messages
import app.armada.proto.Protocol
import java.io.InputStream
import java.io.OutputStream

sealed class DaemonStatus {
    data class Connected(val version: Int) : DaemonStatus()
    object Denied : DaemonStatus()
    data class Unreachable(val exceptionClass: String) : DaemonStatus()
}

object DaemonClassifier {
    const val SOCKET_NAME: String = "armada.daemon"

    fun classifyStreams(inputStream: InputStream, outputStream: OutputStream): DaemonStatus {
        return try {
            val reader = FrameReader(inputStream)
            val writer = FrameWriter(outputStream)

            val firstLine = reader.readFrame()
                ?: return DaemonStatus.Unreachable("EOFException")
            val firstMsg = try {
                Messages.decode(firstLine)
            } catch (e: Exception) {
                return DaemonStatus.Unreachable(e.javaClass.simpleName)
            }

            if (firstMsg is Message.Failure) {
                return if (firstMsg.code == Protocol.DENIED) {
                    DaemonStatus.Denied
                } else {
                    DaemonStatus.Unreachable("ProtocolException")
                }
            }

            if (firstMsg !is Message.Hello) {
                return DaemonStatus.Unreachable("ProtocolException")
            }

            val version = firstMsg.v

            // Send Hello back
            try {
                writer.writeFrame(Messages.encode(Message.Hello(Protocol.VERSION)))
            } catch (e: Exception) {
                return DaemonStatus.Unreachable(e.javaClass.simpleName)
            }

            // Check if daemon sent a denied error frame
            val secondLine = try {
                reader.readFrame()
            } catch (e: Exception) {
                null
            }

            if (secondLine != null) {
                val secondMsg = try {
                    Messages.decode(secondLine)
                } catch (e: Exception) {
                    null
                }
                if (secondMsg is Message.Failure && secondMsg.code == Protocol.DENIED) {
                    return DaemonStatus.Denied
                }
            }

            DaemonStatus.Connected(version)
        } catch (e: Throwable) {
            DaemonStatus.Unreachable(e.javaClass.simpleName)
        }
    }
}
