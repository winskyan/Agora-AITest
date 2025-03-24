package io.agora.ai.rtm.test.ws

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.rtc.RtcManager
import io.agora.ai.rtm.test.rtc.RtcTestManager.TestStatusCallback
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * WebSocket Test Manager
 * Handles WebSocket test statistics, file output, and test cycle management
 */
class WsAudioTestManager(private val context: Context) {

    companion object {
        private const val TAG = Constants.TAG + "-WsAudioTestManager"
        private const val SEND_FRAME_COUNT_ONCE = 6
    }

    // Test configuration
    private var wsUrl = ""
    private var connectedSuccess = false

    private var testStatusCallback: TestStatusCallback? = null

    // File output
    private var historyFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private var bufferedWriter: BufferedWriter? = null

    private var receiverMessageDiffSum = 0L

    private var audioDataSendCount = 0
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
            Log.d(TAG, "onWSConnected")
            connectedSuccess = true
            writeMessageToFile("ws audio test connected")
        }

        override fun onWSDisconnected() {
            // Check if already handling disconnect event
            connectedSuccess = false
            writeMessageToFile("ws audio test disconnect")
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Error sleeping", e)
                }
                closeWriter()
            }
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
                                "Receive audio data: ${message}, diff: ${diff}ms " +
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

        // Initialize WebSocket Manager with our internal listener
        WsManager.create(internalWsListener)

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
        audioDataReceiveCount = 0
        receiverMessageDiffSum = 0L
        pendingAudioDataByteBuffer?.clear()

        // Initialize history file
        historyFileName = "history-ws-audio-${System.currentTimeMillis()}.txt"
        initWriter()

        writeMessageToFile("Demo version: ${BuildConfig.VERSION_NAME}", false)
        writeMessageToFile("RTM version: ${RtmManager.getRtmVersion()}", false)
        writeMessageToFile("RTC version: ${RtcManager.getRtcVersion()}", false)

        // Start test cycle
        testStatusCallback?.onWsAudioTestStarted()
        connectWs(wsUrl)
    }

    /**
     * Stop ongoing test
     */
    fun stopTest() {
        if (connectedSuccess) {
            updateHistoryUI("Ws Audio Test End")
            logoutWs()
            updateTestAverage()
        }
        pendingAudioDataByteBuffer?.clear()
    }

    /**
     * Release resources
     */
    fun release() {
        closeWriter()
        pendingAudioDataByteBuffer?.clear()
        pendingAudioDataByteBuffer = null
        logExecutor.shutdown()
        WsManager.release()
    }

    /**
     * Initialize file writer
     */
    private fun initWriter() {
        try {
            closeWriter()
            val cacheDir = context.externalCacheDir
            val logFile = File(cacheDir, historyFileName)
            bufferedWriter = BufferedWriter(FileWriter(logFile, true))
        } catch (e: IOException) {
            Log.e(TAG, "Error creating BufferedWriter", e)
        }
    }

    /**
     * Close file writer
     */
    private fun closeWriter() {
        try {
            bufferedWriter?.close()
            bufferedWriter = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing BufferedWriter", e)
        }
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
        if (!connectedSuccess) {
            Log.i(TAG, "SendWsMessage: Not connected")
            return
        }
        if (data.isEmpty()) {
            return
        }
        // Initialize buffer if needed
        if (pendingAudioDataByteBuffer == null) {
            pendingAudioDataByteBuffer =
                ByteBuffer.allocateDirect(data.size * SEND_FRAME_COUNT_ONCE)
        }

        try {
            // Now put new data in buffer
            pendingAudioDataByteBuffer?.put(data)

            // If buffer is full, process it
            if ((pendingAudioDataByteBuffer?.remaining() ?: 0) == 0) {
                val position = pendingAudioDataByteBuffer?.position() ?: 0
                val audioData = ByteArray(position)
                pendingAudioDataByteBuffer?.flip()
                pendingAudioDataByteBuffer?.get(audioData)

                val audioDataStr =
                    System.currentTimeMillis().toString() + "-" + Utils.byteArrayToBase64(audioData)
                writeMessageToFile("Send ws audio data: $audioDataStr")
                WsManager.sendMessage(audioDataStr)
                audioDataSendCount++

                pendingAudioDataByteBuffer?.clear()
            }
        } catch (e: BufferOverflowException) {
            Log.e(TAG, "Buffer overflow in sendAudioFrame: ${e.message}, clearing buffer")
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
            "Ws audio data test result: Average delay ${avgDiff}ms, Success rate: ${audioDataReceiveCount * 100 / audioDataSendCount}%, " +
                    "Sent: $audioDataSendCount, Received: $audioDataReceiveCount"
        }
        updateHistoryUI(averageDiff)
    }

    /**
     * Update history UI and write to file
     */
    private fun updateHistoryUI(message: String) {
        // Add timestamp to log message
        val timestamp = Constants.DATE_FORMAT.format(System.currentTimeMillis())
        val timestampedMessage = "$timestamp: $message"

        Log.d(TAG, timestampedMessage)
        testStatusCallback?.onWssAudioTestProgress(timestampedMessage) // Send timestamped message to UI
        writeMessageToFile(message) // The timestamp is added in writeMessageToFile method
    }

    /**
     * Write message to file
     */
    private fun writeMessageToFile(message: String, withTimestamp: Boolean = true) {
        logExecutor.execute {
            try {
                if (withTimestamp) {
                    // Add timestamp with yyyy-MM-dd HH:mm:ss.SSS format
                    val timestamp = Constants.DATE_FORMAT.format(System.currentTimeMillis())
                    bufferedWriter?.append("$timestamp: $message")?.append("\n")
                } else {
                    bufferedWriter?.append(message)?.append("\n")
                }
                bufferedWriter?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing message to file", e)
            }
        }
    }


}