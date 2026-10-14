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
    data class Unreachable(val exceptionClass: String, val detail: String? = null) : DaemonStatus()
}

object DaemonClassifier {
    const val SOCKET_NAME: String = "armada.daemon"

    fun classifyStreams(inputStream: InputStream, outputStream: OutputStream): DaemonStatus {
        return try {
            val reader = FrameReader(inputStream)
            val writer = FrameWriter(outputStream)

            val firstLine = reader.readFrame()
                ?: return DaemonStatus.Unreachable("EOFException", "peer closed before greeting")
            val firstMsg = try {
                Messages.decode(firstLine)
            } catch (e: Exception) {
                return DaemonStatus.Unreachable(e.javaClass.simpleName, e.message)
            }

            if (firstMsg is Message.Failure) {
                return if (firstMsg.code == Protocol.DENIED) {
                    DaemonStatus.Denied
                } else {
                    DaemonStatus.Unreachable("ProtocolException", firstMsg.message)
                }
            }

            if (firstMsg !is Message.Hello) {
                return DaemonStatus.Unreachable("ProtocolException", "expected hello")
            }

            val version = firstMsg.v

            // Send Hello back
            var writeException: Exception? = null
            try {
                writer.writeFrame(Messages.encode(Message.Hello(Protocol.VERSION)))
            } catch (e: Exception) {
                writeException = e
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

            if (writeException != null) {
                return DaemonStatus.Unreachable(writeException.javaClass.simpleName, writeException.message)
            }

            DaemonStatus.Connected(version)
        } catch (e: Throwable) {
            DaemonStatus.Unreachable(e.javaClass.simpleName, e.message)
        }
    }
}
