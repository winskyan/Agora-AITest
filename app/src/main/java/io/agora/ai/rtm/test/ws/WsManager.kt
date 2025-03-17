package io.agora.ai.rtm.test.ws

import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object WsManager {
    private const val TAG = Constants.TAG + "-WSManager"
    private var webSocket: WebSocket? = null
    private var wsListener: WSMessageListener? = null

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

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
                Log.d(TAG, "receiver text: $text")
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