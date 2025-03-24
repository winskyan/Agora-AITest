package io.agora.ai.rtm.test.rtm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.utils.KeyCenter
import io.agora.ai.rtm.test.utils.Utils
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors

/**
 * RTM Test Manager
 * Handles RTM test statistics, file output, and test cycle management
 */
class RtmTestManager(private val context: Context) {

    companion object {
        private const val TAG = Constants.TAG + "-RtmTestManager"
    }

    // Test configuration
    private var channelName = ""
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

    private var loopSleepTime = Constants.INTERVAL_LOOP_WAIT

    private var userId = ""

    // Callbacks
    interface TestStatusCallback {
        fun onRtmTestStarted()
        fun onRtmTestProgress(message: String)
        fun onRtmTestCompleted()
        fun onRtmTestCycleCompleted()
    }

    private var testStatusCallback: TestStatusCallback? = null

    // RTM message listener implementation
    private val internalRtmListener = object : RtmManager.RtmMessageListener {
        override fun onRtmMessageReceived(message: String) {
            Log.d(TAG, "onRtmMessageReceived message:$message")
            if (0L != sendMessageTime && message == sendMessage) {
                // Cancel message timeout
                cancelMessageTimeout()

                val currentTime = System.currentTimeMillis()
                val sendRtmMessageDiff = currentTime - sendMessageTime
                receiverMessageInChannelDiffSum += sendRtmMessageDiff
                val receiverMessageInChannelAverageDiff =
                    receiverMessageInChannelDiffSum / (Constants.MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
                val sendRtmMessageDiffStr =
                    "ReceiveRtmMessage:$sendMessage diff:${sendRtmMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
                updateHistoryUI(sendRtmMessageDiffStr)

                if (remainInChannelTestCount == 0) {
                    receiverMessageDiffSum += receiverMessageInChannelAverageDiff
                }

                if (remainInChannelTestCount == Constants.MAX_IN_CHANNEL_TEST_COUNT - 1) {
                    val receiverMessageFromLoginDiff = currentTime - loginTime
                    receiverMessageFromLoginDiffSum += receiverMessageFromLoginDiff
                    val receiverMessageFromLoginDiffStr =
                        "Receive First Rtm Message from login diff:${receiverMessageFromLoginDiff}ms average:${receiverMessageFromLoginDiffSum / testCount}ms"
                    updateHistoryUI(receiverMessageFromLoginDiffStr)
                }

                if (remainInChannelTestCount > 0) {
                    sendRtmMessages()
                } else {
                    logoutRtm()
                }
            } else {
                updateHistoryUI("ReceiveRtmMessage:$message")
            }
        }

        override fun onRtmConnected() {
            val loginConnectedTime = System.currentTimeMillis() - loginTime
            loginConnectedDiffSum += loginConnectedTime
            testCount++

            val loginConnectedDiff =
                "loginConnectedDiff:${loginConnectedTime}ms average:${loginConnectedDiffSum / testCount}ms"
            updateHistoryUI(loginConnectedDiff)
            RtmManager.subscribeMessageChannel(channelName)
        }

        override fun onRtmDisconnected() {
            // Cancel any pending timeouts
            cancelMessageTimeout()
            updateTestAverage()
            startRtmTestCycle()
            testStatusCallback?.onRtmTestCycleCompleted()
        }

        override fun onRtmSubscribed() {
            sendRtmMessages()
        }
    }

    /**
     * Initialize RTM
     *
     * @param callback Test status callback
     */
    fun initialize(
        callback: TestStatusCallback,
        context: Context,
        rtmListener: RtmManager.RtmMessageListener? = null
    ) {
        this.testStatusCallback = callback
        userId = Utils.generateUniqueRandom(context);
        // Initialize RTM Manager with our internal listener
        RtmManager.create(KeyCenter.APP_ID, userId, internalRtmListener)

        Log.d(
            TAG,
            "RTM initialized with APP_ID: ${KeyCenter.APP_ID}, UID: $userId"
        )
    }

    /**
     * Start RTM test with specified count
     */
    fun startTest(
        channelName: String,
        testCount: Int = Constants.DEFAULT_TEST_COUNT,
        loopSleepTime: Long = Constants.INTERVAL_LOOP_WAIT
    ) {
        this.channelName = channelName
        remainingTests = testCount
        this.loopSleepTime = loopSleepTime
        timeoutCount = 0

        // Initialize history file
        historyFileName =
            "history-rtm-${channelName}-${System.currentTimeMillis()}.txt"
        initWriter()

        writeMessageToFile("Demo version: ${BuildConfig.VERSION_NAME}", false)
        writeMessageToFile("RTM version: ${RtmManager.getRtmVersion()}", false)

        // Reset statistics
        resetTestStats()

        // Start test cycle
        testStatusCallback?.onRtmTestStarted()
        startRtmTestCycle(true)
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
        RtmManager.release()
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
     * Start RTM test cycle
     */
    private fun startRtmTestCycle(firstTest: Boolean = false) {
        if (remainingTests > 0) {
            var delayTime = loopSleepTime
            if (firstTest) {
                delayTime = 0
            }
            remainingTests--

            Handler(Looper.getMainLooper()).postDelayed({
                val message = "Rtm Test Start remainingTests:$remainingTests"
                updateHistoryUI(message)
                loginRtm()
            }, delayTime)
        } else {
            val message = "Rtm Test End (Success: $testCount, Timeout/Failed: $timeoutCount)"
            updateHistoryUI(message)
            testStatusCallback?.onRtmTestCompleted()
        }
    }

    /**
     * Login to RTM
     */
    private fun loginRtm() {
        updateLoginTime()
        val message = "loginRtm channel:$channelName uid:${userId}"
        updateHistoryUI(message)
        RtmManager.login("")
    }

    /**
     * Logout from RTM
     */
    private fun logoutRtm() {
        cancelMessageTimeout()
        RtmManager.unsubscribeMessageChannel(channelName)
        RtmManager.rtmLogout()
    }

    /**
     * Send RTM messages
     */
    private fun sendRtmMessages() {
        remainInChannelTestCount--
        sendMessageTime = System.currentTimeMillis()
        sendMessage = "rtmMessage$sendMessageTime"

        // Start message timeout
        startMessageTimeout()

        val ret = RtmManager.sendRtmMessage(sendMessage.toByteArray(Charsets.UTF_8), channelName)
        if (ret != 0) {
            Log.e(TAG, "sendRtmMessage failed")
            handleTestFailure("Failed to send message")
            return
        }
        updateHistoryUI("SendRtmMessage:$sendMessage")
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
        testStatusCallback?.onRtmTestProgress(timestampedMessage) // Send timestamped message to UI
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

        // Logout from RTM
        RtmManager.unsubscribeMessageChannel(channelName)
        RtmManager.rtmLogout()
    }
} 