package io.agora.ai.rtm.test.ws

import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

object WSManager {
    private const val TAG = Constants.TAG + "-WSManager"
    private var webSocket: WebSocket? = null
    private var wsListener: WSMessageListener? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()


    interface WSMessageListener {
        fun onWSConnected()
        fun onWSDisconnected()
        fun onWSMessageReceived(message: String)
        fun onWSMessageReceived(message: ByteArray)
        fun onWSError(errorMessage: String)
    }

    fun create(listener: WSMessageListener) {
        this.wsListener = listener
    }

    fun release() {
        webSocket?.close(1000, "正常关闭")
        webSocket = null
    }

    fun connect(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                wsListener?.onWSConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "receiver test: $text")
                wsListener?.onWSMessageReceived(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "receiver ws byte String: ${String(bytes.toByteArray())}")
                wsListener?.onWSMessageReceived(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code, $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code, $reason")
                wsListener?.onWSDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connect fail", t)
                wsListener?.onWSError(t.message ?: "WebSocket connect fail")
            }
        })
    }

    fun sendMessage(message: String): Boolean {
        return webSocket?.send(message) == true
    }

    fun sendMessage(message: ByteArray): Boolean {
        return webSocket?.send(ByteString.of(*message)) ?: false
    }
}