package io.agora.ai.rtm.test.ws

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.rtm.RtmManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors

/**
 * WebSocket Test Manager
 * Handles WebSocket test statistics, file output, and test cycle management
 */
class WsTestManager(private val context: Context) {

    companion object {
        private const val TAG = Constants.TAG + "-WsTestManager"
    }

    // Test configuration
    private var wsUrl = "wss://108.129.196.84:8765"
    private var remainingTests = 0
    private var remainInChannelTestCount = 0

    // Timing data
    private var loginTime = 0L
    private var sendMessageTime = 0L
    private var sendMessage = ""

    // Statistics
    private var loginConnectedDiffSum = 0L
    private var receiverMessageDiffSum = 0L
    private var receiverMessageFromLoginDiffSum = 0L
    private var receiverMessageInChannelDiffSum = 0L
    private var testCount = 0
    private var timeoutCount = 0

    // Timeout handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var messageTimeoutRunnable: Runnable? = null

    // File output
    private var historyFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private var bufferedWriter: BufferedWriter? = null

    // Variable to store disconnect protection Runnable
    private var disconnectProtectionRunnable: Runnable? = null

    // Flag for handling disconnect event
    private var isHandlingDisconnect = false

    // Callbacks
    interface TestStatusCallback {
        fun onWsTestStarted()
        fun onWsTestProgress(message: String)
        fun onWsTestCompleted()
        fun onWsTestCycleCompleted()
    }

    private var testStatusCallback: TestStatusCallback? = null

    // WebSocket message listener implementation
    private val internalWsListener = object : WsManager.WSMessageListener {
        override fun onWSConnected() {
            Log.d(TAG, "onWSConnected")
            val loginConnectedTime = System.currentTimeMillis() - loginTime
            loginConnectedDiffSum += loginConnectedTime
            testCount++

            val loginConnectedDiff =
                "ws loginConnectedDiff:${loginConnectedTime}ms average:${loginConnectedDiffSum / testCount}ms"
            updateHistoryUI(loginConnectedDiff)
            sendWsMessage()
        }

        override fun onWSDisconnected() {
            // Check if already handling disconnect event
            if (isHandlingDisconnect) {
                Log.w(TAG, "Already handling disconnect event, ignoring duplicate")
                return
            }

            isHandlingDisconnect = true

            try {
                // Cancel any pending timeouts
                cancelMessageTimeout()

                // Cancel disconnect protection
                cancelDisconnectProtection()

                updateTestAverage()
                startWsTestCycle()
                testStatusCallback?.onWsTestCycleCompleted()
            } finally {
                // Ensure flag is reset after handling
                isHandlingDisconnect = false
            }
        }

        override fun onWSMessageReceived(message: String) {
            Log.d(TAG, "onWSMessageReceived message:$message")
            if (0L != sendMessageTime && message == sendMessage) {
                // Cancel message timeout
                cancelMessageTimeout()

                val currentTime = System.currentTimeMillis()
                val sendMessageDiff = currentTime - sendMessageTime

                receiverMessageInChannelDiffSum += sendMessageDiff
                val receiverMessageInChannelAverageDiff =
                    receiverMessageInChannelDiffSum / (Constants.MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
                val sendWsMessageDiffStr =
                    "ReceiverWsMessage:$sendMessage diff:${sendMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
                updateHistoryUI(sendWsMessageDiffStr)

                if (remainInChannelTestCount == 0) {
                    receiverMessageDiffSum += receiverMessageInChannelAverageDiff
                }

                if (remainInChannelTestCount == Constants.MAX_IN_CHANNEL_TEST_COUNT - 1) {
                    val receiverMessageFromLoginDiff = currentTime - loginTime
                    receiverMessageFromLoginDiffSum += receiverMessageFromLoginDiff
                    val receiverMessageFromLoginDiffStr =
                        "ReceiverWsMessage:$sendMessage from login diff:${receiverMessageFromLoginDiff}ms average:${receiverMessageFromLoginDiffSum / testCount}ms"
                    updateHistoryUI(receiverMessageFromLoginDiffStr)
                }

                if (remainInChannelTestCount > 0) {
                    sendWsMessage()
                } else {
                    logoutWs()
                }
            } else {
                updateHistoryUI("ReceiveWsMessage:$message")
            }
        }

        override fun onWSMessageReceived(message: ByteArray) {
            Log.d(TAG, "onWSMessageReceived message:${String(message)}")
            onWSMessageReceived(String(message))
        }

        override fun onWSError(errorMessage: String) {
            updateHistoryUI("WebSocket Error: $errorMessage")

            // When error occurs, proceed to the next test
            handleTestFailure("Connection error: $errorMessage")
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
     * Set WebSocket URL for test
     */
    fun setWsUrl(url: String) {
        if (url.isNotEmpty()) {
            this.wsUrl = url
        }
    }

    /**
     * Start WebSocket test with specified count
     */
    fun startTest(testCount: Int = Constants.DEFAULT_TEST_COUNT) {
        remainingTests = testCount
        timeoutCount = 0

        // Initialize history file
        historyFileName = "history-ws-${System.currentTimeMillis()}.txt"
        initWriter()

        writeMessageToFile("Demo version: ${BuildConfig.VERSION_NAME}", false)
        writeMessageToFile("RTM version: ${RtmManager.getRtmVersion()}", false)

        // Reset statistics
        resetTestStats()

        // Start test cycle
        testStatusCallback?.onWsTestStarted()
        startWsTestCycle(true)
    }

    /**
     * Stop ongoing test
     */
    fun stopTest() {
        remainingTests = 0
        cancelMessageTimeout()
    }

    /**
     * Release resources
     */
    fun release() {
        cancelMessageTimeout()
        closeWriter()
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
     * Reset test statistics
     */
    private fun resetTestStats() {
        loginConnectedDiffSum = 0L
        receiverMessageDiffSum = 0L
        receiverMessageFromLoginDiffSum = 0L
        testCount = 0
    }

    /**
     * Reset message data
     */
    private fun resetData() {
        sendMessageTime = 0L
        sendMessage = ""
    }

    /**
     * Start WebSocket test cycle
     */
    private fun startWsTestCycle(firstTest: Boolean = false) {
        // Reset disconnect handling flag when starting new test cycle
        isHandlingDisconnect = false

        if (remainingTests > 0) {
            var delayTime = Constants.INTERVAL_LOOP_WAIT
            if (firstTest) {
                delayTime = 0
            }
            remainingTests--

            Handler(Looper.getMainLooper()).postDelayed({
                val message = "Ws Test Start remainingTests:$remainingTests"
                updateHistoryUI(message)
                connectWs(wsUrl)
            }, delayTime)
        } else {
            val message = "Ws Test End (Success: $testCount, Timeout/Failed: $timeoutCount)"
            updateHistoryUI(message)
            testStatusCallback?.onWsTestCompleted()
        }
    }

    /**
     * Connect to WebSocket
     */
    private fun connectWs(url: String) {
        updateLoginTime()
        val message = "connectWs url:$url"
        updateHistoryUI(message)

        // Using WsManager's built-in timeout settings (5 seconds)
        WsManager.connect(url)
    }

    /**
     * Logout from WebSocket
     */
    private fun logoutWs() {
        cancelMessageTimeout()
        WsManager.release()
    }

    /**
     * Send WebSocket message
     */
    private fun sendWsMessage() {
        remainInChannelTestCount--
        sendMessageTime = System.currentTimeMillis()
        sendMessage = "wsMessage$sendMessageTime"

        // Start message timeout
        startMessageTimeout()

        // Using WsManager's built-in timeout settings (5 seconds)
        val success = WsManager.sendMessage(sendMessage)

        if (success) {
            updateHistoryUI("SendWsMessage:$sendMessage")
        } else {
            handleTestFailure("Failed to send message")
        }
    }

    /**
     * Update login time
     */
    private fun updateLoginTime() {
        resetData()
        loginTime = System.currentTimeMillis()
        remainInChannelTestCount = Constants.MAX_IN_CHANNEL_TEST_COUNT
        receiverMessageInChannelDiffSum = 0L
    }

    /**
     * Update test average statistics
     */
    private fun updateTestAverage() {
        // Calculate actual successful test count (total tests minus timeout count)
        val successCount = if (testCount > timeoutCount) testCount - timeoutCount else 1

        // If success count is 0, use 1 as divisor to avoid division by zero error
        val divisor = if (successCount > 0) successCount else 1

        val averageDiff = "login Connected diff average:${loginConnectedDiffSum / divisor}ms," +
                "echo message average:${receiverMessageDiffSum / divisor}ms," +
                "receiver first echo message from login average:${receiverMessageFromLoginDiffSum / divisor}ms," +
                "test count:$testCount, success count:$successCount, timeout count:$timeoutCount"
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
        testStatusCallback?.onWsTestProgress(timestampedMessage) // Send timestamped message to UI
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

    /**
     * Start message timeout timer
     */
    private fun startMessageTimeout() {
        cancelMessageTimeout()

        messageTimeoutRunnable = Runnable {
            handleTestFailure("Message receive timeout")
        }

        mainHandler.postDelayed(
            messageTimeoutRunnable!!,
            Constants.INTERVAL_RECEIVER_MESSAGE_TIMEOUT
        )
    }

    /**
     * Cancel message timeout
     */
    private fun cancelMessageTimeout() {
        messageTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            messageTimeoutRunnable = null
        }
    }

    /**
     * Handle test failure (timeout or error)
     */
    private fun handleTestFailure(reason: String) {
        timeoutCount++
        updateHistoryUI("Test failed: $reason")

        // Cancel any pending timeouts
        cancelMessageTimeout()

        // Close current connection
        WsManager.release()

        // Add force disconnect protection, trigger next test cycle if no onWSDisconnected callback within 5 seconds
        val forceDisconnectRunnable = Runnable {
            Log.w(TAG, "Force disconnect protection triggered")
            isHandlingDisconnect = true

            updateTestAverage()
            startWsTestCycle()
            testStatusCallback?.onWsTestCycleCompleted()
        }

        mainHandler.postDelayed(
            forceDisconnectRunnable,
            5000
        ) // Trigger protection mechanism after 5 seconds

        // Save current protection Runnable for cancellation on normal disconnect
        disconnectProtectionRunnable = forceDisconnectRunnable
    }

    /**
     * Cancel disconnect protection
     */
    private fun cancelDisconnectProtection() {
        disconnectProtectionRunnable?.let {
            mainHandler.removeCallbacks(it)
            disconnectProtectionRunnable = null
        }
    }
}