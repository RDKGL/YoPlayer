package open.yo.player.data.telegram

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramStreamProxy @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var server: LocalProxyServer? = null
    private var serverPort = 8765

    fun isReady(): Boolean = server?.isAlive == true

    fun getStreamUrl(chatId: Long, messageId: Long): String {
        startIfNeeded()
        return "http://127.0.0.1:$serverPort/telegram/stream?chat_id=$chatId&msg_id=$messageId"
    }

    @Synchronized
    fun startIfNeeded() {
        if (server == null || !server!!.isAlive) {
            try {
                server = LocalProxyServer(serverPort)
                server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Timber.i("Telegram local HTTP proxy started on port $serverPort")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start Telegram local stream proxy")
            }
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private class LocalProxyServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            if (uri == "/telegram/stream") {
                val params = session.parameters
                val chatId = params["chat_id"]?.firstOrNull()?.toLongOrNull() ?: 0L
                val messageId = params["msg_id"]?.firstOrNull()?.toLongOrNull() ?: 0L

                // Return streaming HTTP response chunked for ExoPlayer
                val emptyStream: InputStream = ByteArrayInputStream(ByteArray(0))
                val response = newChunkedResponse(Response.Status.OK, "audio/mpeg", emptyStream)
                response.addHeader("Accept-Ranges", "bytes")
                return response
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}
