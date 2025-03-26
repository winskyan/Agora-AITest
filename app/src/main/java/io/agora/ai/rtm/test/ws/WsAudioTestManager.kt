package io.agora.ai.rtm.test.ws

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.base.TestManagerBase
import io.agora.ai.rtm.test.rtc.RtcManager
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

/**
 * WebSocket Test Manager
 * Handles WebSocket test statistics, file output, and test cycle management
 */
class WsAudioTestManager(context: Context) : TestManagerBase(context) {

    companion object {
        private const val SEND_FRAME_COUNT_ONCE = 6
        private const val SEND_FRAME_SIZE = 500
    }

    // Test configuration
    private var wsUrl = ""
    private var connectedSuccess = false

    private var testStatusCallback: TestStatusCallback? = null

    private var receiverMessageDiffSum = 0L

    private var audioDataSendCount = 0
    private var audioDataSendFailCount = 0
    private var audioDataReceiveCount = 0

    private var pendingAudioDataByteBuffer: ByteBuffer? = null


    // Callbacks
    interface TestStatusCallback {
        fun onWsAudioTestStarted()
        fun onWssAudioTestProgress(message: String)
        fun onWssAudioTestCompleted()
    }


    // WebSocket message listener implementation
    private val internalWsListener = object : WsManager.WSMessageListener {
        override fun onWSConnected() {
            Log.d(TAG, "onWSConnected for audio test")
            connectedSuccess = true
            updateHistoryUI("ws audio test connected")
        }

        override fun onWSDisconnected() {
            // Check if already handling disconnect event
            connectedSuccess = false
            updateHistoryUI("ws audio test disconnect")
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Error sleeping", e)
                }
                closeWriter()
            }
            updateTestAverage()
            testStatusCallback?.onWssAudioTestCompleted()
        }

        override fun onWSMessageReceived(message: String) {
            Log.d(TAG, "onWSMessageReceived message:$message")

            if (!connectedSuccess) {
                Log.d(TAG, "onWSMessageReceived: not connected")
                return
            }

            try {
                if (message.contains("-")) {
                    audioDataReceiveCount++
                    val currentTime = System.currentTimeMillis()
                    val parts = message.split("-", limit = 2)

                    if (parts.size >= 2) {
                        val timestamp = parts[0].toLongOrNull()
                        if (timestamp != null) {
                            val diff = currentTime - timestamp
                            receiverMessageDiffSum += diff
                            val avgDiff = receiverMessageDiffSum / audioDataReceiveCount

                            writeMessageToFile(
                                "Receive audio data: ${timestamp}, diff: ${diff}ms " +
                                        "Average diff: ${avgDiff}ms sendCount: $audioDataSendCount " +
                                        "receiveCount: $audioDataReceiveCount"
                            )
                        } else {
                            Log.e(
                                TAG,
                                "Failed to parse timestamp from audio metadata: ${parts[0]}"
                            )
                        }
                    } else {
                        Log.e(TAG, "Malformed audio data: $message")
                    }
                } else {
                    Log.d(TAG, "Invalid audio metadata format: $message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio message: ${e.message}")
            }
        }

        override fun onWSMessageReceived(message: ByteArray) {
            Log.d(TAG, "onWSMessageReceived message:${String(message)}")
            onWSMessageReceived(String(message))
        }

        override fun onWSError(errorMessage: String) {
            updateHistoryUI("WebSocket Error: $errorMessage")
        }
    }

    /**
     * Initialize WebSocket
     *
     * @param callback Test status callback
     */
    fun initialize(callback: TestStatusCallback) {
        this.testStatusCallback = callback

        Log.d(TAG, "WebSocket initialized")
    }

    /**
     * Start WebSocket test with specified count
     */
    fun startTest(
        wsUrl: String
    ) {
        connectedSuccess = false
        audioDataSendCount = 0
        audioDataSendFailCount = 0
        audioDataReceiveCount = 0
        receiverMessageDiffSum = 0L
        pendingAudioDataByteBuffer?.clear()

        // Initialize history file
        historyFileName = "history-ws-audio-${System.currentTimeMillis()}.txt"
        initWriter()

        updateHistoryUI("Demo version: ${BuildConfig.VERSION_NAME}")
        updateHistoryUI("RTM version: ${RtmManager.getRtmVersion()}")
        updateHistoryUI("RTC version: ${RtcManager.getRtcVersion()}")

        // Start test cycle
        testStatusCallback?.onWsAudioTestStarted()
        testStartTime = System.currentTimeMillis()
        testStarted = true

        // Initialize WebSocket Manager with our internal listener
        WsManager.setListener(internalWsListener)

        connectWs(wsUrl)
    }

    /**
     * Stop ongoing test
     */
    fun stopTest() {
        if (connectedSuccess) {
            updateHistoryUI("Ws Audio Test End after 1s")
            mainHandler.postDelayed({
                logoutWs()
            }, 1000)

        }
        testStarted = false
        pendingAudioDataByteBuffer?.clear()
    }

    /**
     * Release resources
     */
    override fun release() {
        super.release()
        pendingAudioDataByteBuffer?.clear()
        pendingAudioDataByteBuffer = null
        WsManager.release()
    }

    /**
     * Connect to WebSocket
     */
    private fun connectWs(url: String) {
        val message = "connectWs url:$url"
        updateHistoryUI(message)

        // Using WsManager's built-in timeout settings (5 seconds)
        WsManager.connect(url)
    }

    /**
     * Logout from WebSocket
     */
    private fun logoutWs() {
        connectedSuccess = false
        WsManager.release()
    }

    @Synchronized
    fun sendAudioFrame(data: ByteArray) {
        if (!connectedSuccess || !testStarted) {
            Log.i(TAG, "SendWsMessage: Not connected")
            return
        }
        if (data.isEmpty()) {
            return
        }
        // Initialize buffer if needed
        if (pendingAudioDataByteBuffer == null) {
            pendingAudioDataByteBuffer =
                ByteBuffer.allocateDirect(SEND_FRAME_SIZE)
            updateHistoryUI("Allocated ws test buffer for audio data size: $SEND_FRAME_SIZE")
        }

        try {
            val remainingSpace = pendingAudioDataByteBuffer?.remaining() ?: 0
            val dataToAdd = if (data.size <= remainingSpace) {
                pendingAudioDataByteBuffer?.put(data)
                0
            } else {
                pendingAudioDataByteBuffer?.put(data, 0, remainingSpace)
                data.size - remainingSpace
            }

            if ((pendingAudioDataByteBuffer?.remaining() ?: 0) == 0) {
                val position = pendingAudioDataByteBuffer?.position() ?: 0
                val audioData = ByteArray(position)
                pendingAudioDataByteBuffer?.flip()
                pendingAudioDataByteBuffer?.get(audioData)

                val currentTime = System.currentTimeMillis().toString()
                val audioDataStr =
                    currentTime + "-" + Utils.byteArrayToBase64(audioData)
                writeMessageToFile("Send ws audio data: $currentTime")
                val ret = WsManager.sendMessage(audioDataStr)
                audioDataSendCount++
                if (!ret) {
                    audioDataSendFailCount++
                    updateHistoryUI("Failed to send ws audio data: $currentTime")
                }

                pendingAudioDataByteBuffer?.clear()

                if (dataToAdd > 0) {
                    pendingAudioDataByteBuffer?.put(data, data.size - dataToAdd, dataToAdd)
                }
            }
        } catch (e: BufferOverflowException) {
            Log.e(TAG, "Buffer overflow in sendAudioFrame: ${e.message}, clearing buffer")
            pendingAudioDataByteBuffer?.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendAudioFrame: ${e.message}")
        }
    }


    /**
     * Update test average statistics
     */
    private fun updateTestAverage() {
        val averageDiff = if (audioDataReceiveCount == 0) {
            "Ws audio data test result: No data received, send count: $audioDataSendCount"
        } else {
            val avgDiff = receiverMessageDiffSum / audioDataReceiveCount
            "Ws audio data test result: Average delay ${avgDiff}ms, Receive rate: ${audioDataReceiveCount * 100 / audioDataSendCount}%, " +
                    "Sent: $audioDataSendCount, Received: $audioDataReceiveCount Send Fail Count:${audioDataSendFailCount} in ${
                        formatTime(
                            System.currentTimeMillis() - testStartTime
                        )
                    }"
        }
        updateHistoryUI(averageDiff)
    }

    /**
     * Update history UI and write to file
     */
    override fun updateHistoryUI(message: String) {
        val formatMessage = formatMessage(message)
        super.updateHistoryUI(formatMessage)
        testStatusCallback?.onWssAudioTestProgress(formatMessage) // Send timestamped message to UI
    }
}