package io.agora.ai.rtm.test.ws

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.base.TestManagerBase
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.rtm.RtmManager

/**
 * WebSocket Test Manager
 * Handles WebSocket test statistics, file output, and test cycle management
 */
class WsTestManager(context: Context) : TestManagerBase(context) {
    // Test configuration
    private var wsUrl = ""
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

    private var messageTimeoutRunnable: Runnable? = null


    // Variable to store disconnect protection Runnable
    private var disconnectProtectionRunnable: Runnable? = null

    // Flag for handling disconnect event
    private var isHandlingDisconnect = false

    private var loopSleepTime = Constants.INTERVAL_LOOP_WAIT

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
            if (0L != sendMessageTime && message.startsWith(sendMessage)) {
                // Cancel message timeout
                cancelMessageTimeout()

                val currentTime = System.currentTimeMillis()
                val sendMessageDiff = currentTime - sendMessageTime

                receiverMessageInChannelDiffSum += sendMessageDiff
                val receiverMessageInChannelAverageDiff =
                    receiverMessageInChannelDiffSum / (Constants.MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
                val sendWsMessageDiffStr =
                    "ReceiverWsMessage:$message diff:${sendMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
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
                updateHistoryUI("ReceiveWsMessage:$message not match send message:$sendMessage")
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

        Log.d(TAG, "WebSocket initialized")
    }

    /**
     * Start WebSocket test with specified count
     */
    fun startTest(
        wsUrl: String,
        testCount: Int = Constants.DEFAULT_TEST_COUNT,
        loopSleepTime: Long = Constants.INTERVAL_LOOP_WAIT
    ) {
        this.wsUrl = wsUrl
        remainingTests = testCount
        this.loopSleepTime = loopSleepTime
        timeoutCount = 0

        // Initialize history file
        historyFileName = "history-ws-${System.currentTimeMillis()}.txt"
        initWriter()

        updateHistoryUI("Demo version: ${BuildConfig.VERSION_NAME}")
        updateHistoryUI("RTM version: ${RtmManager.getRtmVersion()}")

        // Reset statistics
        resetTestStats()

        // Start test cycle
        testStatusCallback?.onWsTestStarted()
        testStartTime = System.currentTimeMillis()

        // Initialize WebSocket Manager with our internal listener
        WsManager.setListener(internalWsListener)

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
    override fun release() {
        super.release()
        cancelMessageTimeout()
        WsManager.release()
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
            var delayTime = loopSleepTime
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
                "test count:$testCount, success count:$successCount, timeout count:$timeoutCount in ${
                    formatTime(
                        System.currentTimeMillis() - testStartTime
                    )
                }"
        updateHistoryUI(averageDiff)
    }

    /**
     * Update history UI and write to file
     */
    override fun updateHistoryUI(message: String) {
        val formatMessage = formatMessage(message)
        super.updateHistoryUI(formatMessage)
        testStatusCallback?.onWsTestProgress(formatMessage) // Send timestamped message to UI
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