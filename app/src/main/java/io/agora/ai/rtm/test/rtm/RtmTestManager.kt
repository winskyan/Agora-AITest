package io.agora.ai.rtm.test.rtm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.base.TestManagerBase
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.utils.KeyCenter
import io.agora.ai.rtm.test.utils.Utils

/**
 * RTM Test Manager
 * Handles RTM test statistics, file output, and test cycle management
 */
class RtmTestManager(context: Context) : TestManagerBase(context) {
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

    private var messageTimeoutRunnable: Runnable? = null

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
            if (0L != sendMessageTime && message.startsWith(sendMessage)) {
                // Cancel message timeout
                cancelMessageTimeout()

                val currentTime = System.currentTimeMillis()
                val sendRtmMessageDiff = currentTime - sendMessageTime
                receiverMessageInChannelDiffSum += sendRtmMessageDiff
                val receiverMessageInChannelAverageDiff =
                    receiverMessageInChannelDiffSum / (Constants.MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
                val sendRtmMessageDiffStr =
                    "ReceiveRtmMessage:$message diff:${sendRtmMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
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
                updateHistoryUI("ReceiveRtmMessage:$message fail to match send message:$sendMessage")
            }
        }

        override fun onRtmConnected() {
            val currentTime = System.currentTimeMillis()
            val loginConnectedTime = currentTime - loginTime
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

        updateHistoryUI("Demo version: ${BuildConfig.VERSION_NAME}")
        updateHistoryUI("RTM version: ${RtmManager.getRtmVersion()}")

        // Reset statistics
        resetTestStats()

        // Start test cycle
        testStatusCallback?.onRtmTestStarted()

        testStartTime = System.currentTimeMillis()
        testStarted = true
        startRtmTestCycle(true)
    }

    /**
     * Stop ongoing test
     */
    fun stopTest() {
        remainingTests = 0
        cancelMessageTimeout()
        testStarted = false
    }

    /**
     * Release resources
     */
    override fun release() {
        super.release()
        cancelMessageTimeout()
        RtmManager.release()
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
        testStatusCallback?.onRtmTestProgress(formatMessage) // Send timestamped message to UI
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