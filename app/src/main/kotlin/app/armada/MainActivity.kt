package app.armada

import android.app.Activity
import android.graphics.Typeface
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusTitleView: TextView
    private lateinit var statusDetailView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val paddingPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
            ).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        statusTitleView = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "Checking daemon status..."
        }
        layout.addView(statusTitleView)

        statusDetailView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            val topMarginPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
            ).toInt()
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMarginPx, 0, 0)
            }
            layoutParams = params
            text = ""
        }
        layout.addView(statusDetailView)

        setContentView(layout)
    }

    override fun onStart() {
        super.onStart()
        checkDaemonInBackground()
    }

    private fun checkDaemonInBackground() {
        Thread {
            val status = queryDaemonStatus()
            runOnUiThread {
                updateUi(status)
            }
        }.start()
    }

    private fun queryDaemonStatus(): DaemonStatus {
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(DaemonClassifier.SOCKET_NAME))
            socket.soTimeout = 1000
            val status = DaemonClassifier.classifyStreams(socket.inputStream, socket.outputStream)
            socket.close()
            status
        } catch (e: Throwable) {
            try { socket.close() } catch (ignored: Exception) {}
            DaemonStatus.Unreachable(e.javaClass.simpleName)
        }
    }

    private fun updateUi(status: DaemonStatus) {
        when (status) {
            is DaemonStatus.Connected -> {
                statusTitleView.text = "Daemon running"
                statusDetailView.text = "Protocol version: ${status.version}"
            }
            is DaemonStatus.Denied -> {
                statusTitleView.text = "Armada needs root"
                statusDetailView.text = "This app is not root, so the daemon refused it. Start the daemon with su and use the CLI."
            }
            is DaemonStatus.Unreachable -> {
                statusTitleView.text = "Daemon not reachable"
                statusDetailView.text = status.exceptionClass
            }
        }
    }
}
