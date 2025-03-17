package io.agora.ai.rtm.test.rtm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.utils.KeyCenter
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
    private var channelName = "wei888"
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

    // File output
    private var historyFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private var bufferedWriter: BufferedWriter? = null

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
        rtmListener: RtmManager.RtmMessageListener? = null
    ) {
        this.testStatusCallback = callback

        // Initialize RTM Manager with our internal listener
        RtmManager.create(KeyCenter.APP_ID, KeyCenter.getRtmUid().toString(), internalRtmListener)

        Log.d(
            TAG,
            "RTM initialized with APP_ID: ${KeyCenter.APP_ID}, UID: ${KeyCenter.getRtmUid()}"
        )
    }

    /**
     * Set channel name for RTM test
     */
    fun setChannelName(name: String) {
        if (name.isNotEmpty()) {
            this.channelName = name
        }
    }

    /**
     * Start RTM test with specified count
     */
    fun startTest(testCount: Int = Constants.DEFAULT_TEST_COUNT) {
        remainingTests = testCount

        // Initialize history file
        historyFileName =
            "history-rtm-${channelName}-${KeyCenter.getRtmUid()}-${System.currentTimeMillis()}.txt"
        initWriter()

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
    }

    /**
     * Release resources
     */
    fun release() {
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
            var delayTime = Constants.INTERVAL_LOOP_WAIT
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
            val message = "Rtm Test End"
            updateHistoryUI(message)
            testStatusCallback?.onRtmTestCompleted()
        }
    }

    /**
     * Login to RTM
     */
    private fun loginRtm() {
        updateLoginTime()
        val message = "loginRtm channel:$channelName uid:${KeyCenter.getRtmUid()}"
        updateHistoryUI(message)
        RtmManager.login(KeyCenter.getRtmToken2(KeyCenter.getRtmUid()))
    }

    /**
     * Logout from RTM
     */
    private fun logoutRtm() {
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

        val ret = RtmManager.sendRtmMessage(sendMessage.toByteArray(Charsets.UTF_8), channelName)
        if (ret != 0) {
            Log.e(TAG, "sendRtmMessage failed")
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
        val averageDiff = "login Connected diff average:${loginConnectedDiffSum / testCount}ms," +
                "echo message average:${receiverMessageDiffSum / testCount}ms," +
                "receiver first echo message from login average:${receiverMessageFromLoginDiffSum / testCount}ms," +
                "test count:$testCount"
        updateHistoryUI(averageDiff)
    }

    /**
     * Update history UI and write to file
     */
    private fun updateHistoryUI(message: String) {
        Log.d(TAG, message)
        testStatusCallback?.onRtmTestProgress(message)
        writeMessageToFile(message)
    }

    /**
     * Write message to file
     */
    private fun writeMessageToFile(message: String) {
        logExecutor.execute {
            try {
                bufferedWriter?.append(message)?.append("\n")
                bufferedWriter?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing message to file", e)
            }
        }
    }
} 