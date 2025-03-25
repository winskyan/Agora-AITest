package io.agora.ai.rtm.test.rtc

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.base.TestManagerBase
import io.agora.ai.rtm.test.utils.KeyCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class RtcTestManager(context: Context) : TestManagerBase(context),
    RtcManager.RtcMessageListener {

    companion object {
        private const val SEND_FRAME_COUNT_ONCE = 4
    }

    private var joinChannelSuccess = false

    private var testStatusCallback: TestStatusCallback? = null


    private var receiverMessageDiffSum = 0L

    private var audioMetaDataSendCount = 0
    private var audioMetaDataSendFailCount = 0
    private var audioMetaDataReceiveCount = 0

    private var pendingAudioDataByteBuffer: ByteBuffer? = null

    // Callbacks
    interface TestStatusCallback {
        fun onRtcTestStarted()
        fun onRtcTestProgress(message: String)
        fun onRtcTestCompleted()
        fun onRecordAudioFrame(data: ByteArray?)
    }

    fun initialize(
        callback: TestStatusCallback
    ) {
        this.testStatusCallback = callback

        RtcManager.create(context, KeyCenter.APP_ID, this)
    }

    fun startTest(channelName: String) {
        joinChannelSuccess = false
        audioMetaDataSendCount = 0
        audioMetaDataSendFailCount = 0
        audioMetaDataReceiveCount = 0
        receiverMessageDiffSum = 0L
        pendingAudioDataByteBuffer?.clear()

        historyFileName =
            "history-rtc-${channelName}-${System.currentTimeMillis()}.txt"
        initWriter()

        updateHistoryUI("Demo version: ${BuildConfig.VERSION_NAME}")
        updateHistoryUI("RTC version: ${RtcManager.getRtcVersion()}")

        updateHistoryUI("joinChannel channel: $channelName")
        testStartTime = System.currentTimeMillis()
        testStarted = true
        // Initialize history file
        RtcManager.joinChannel("", channelName, 0, io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER)
    }

    fun stopTest() {
        if (joinChannelSuccess) {
            updateHistoryUI("leaveChannel after 1s")
            mainHandler.postDelayed({
                RtcManager.leaveChannel()
            }, 1000)
        }
        testStarted = false
        pendingAudioDataByteBuffer?.clear()
    }

    @Synchronized
    private fun sendAudioFrame(data: ByteArray) {
        if (!joinChannelSuccess || !testStarted) {
            Log.i(TAG, "sendAudioFrame: not in channel")
            return
        }
        if (data.isEmpty()) {
            return
        }

        // Initialize buffer if needed
        if (pendingAudioDataByteBuffer == null) {
            pendingAudioDataByteBuffer =
                ByteBuffer.allocateDirect(data.size * SEND_FRAME_COUNT_ONCE)
            updateHistoryUI("Allocated rtc test buffer for audio data size: ${data.size} frame count: $SEND_FRAME_COUNT_ONCE")
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

//                val audioMetaData =
//                    System.currentTimeMillis().toString() + "-" + Utils.byteArrayToBase64(audioData)
                val audioMetaData =
                    System.currentTimeMillis().toString() + "-" + "audioData"
                writeMessageToFile("Send audio meta data: $audioMetaData")
                val ret = RtcManager.sendAudioMetadata(audioMetaData.toByteArray())
                if (ret == 0) {
                    audioMetaDataSendCount++
                } else {
                    audioMetaDataSendFailCount++
                    updateHistoryUI("Failed to send audio metadata: $audioMetaData ret: $ret")
                }

                pendingAudioDataByteBuffer?.clear()
            }
        } catch (e: BufferOverflowException) {
            Log.e(TAG, "Buffer overflow in sendAudioFrame: ${e.message}, clearing buffer")
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendAudioFrame: ${e.message}")
        }
    }

    private fun updateTestAverage() {
        val averageDiff = if (audioMetaDataReceiveCount == 0) {
            "Rtc Audio metadata test result: No data received, send count: $audioMetaDataSendCount"
        } else {
            val avgDiff = receiverMessageDiffSum / audioMetaDataReceiveCount
            val testTime = System.currentTimeMillis() - testStartTime
            "Rtc Audio metadata test result: Average delay ${avgDiff}ms, Receive rate: ${audioMetaDataReceiveCount * 100 / audioMetaDataSendCount}%, " +
                    "Sent: $audioMetaDataSendCount, Received: $audioMetaDataReceiveCount Send Fail Count:${audioMetaDataSendFailCount} in ${
                        formatTime(
                            testTime
                        )
                    }"
        }
        updateHistoryUI(averageDiff)
    }

    override fun updateHistoryUI(message: String) {
        val formatMessage = formatMessage(message)
        super.updateHistoryUI(formatMessage)
        testStatusCallback?.onRtcTestProgress(formatMessage) // Send timestamped message to UI
    }

    override fun release() {
        super.release()
        pendingAudioDataByteBuffer?.clear()
        pendingAudioDataByteBuffer = null
        RtcManager.destroy()
    }

    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
        joinChannelSuccess = true
        updateHistoryUI("onJoinChannelSuccess Channel: $channel uid:$uid")
        testStatusCallback?.onRtcTestStarted()
    }

    override fun onLeaveChannelSuccess() {
        joinChannelSuccess = false
        updateHistoryUI("onLeaveChannelSuccess")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error sleeping", e)
            }
            closeWriter()
        }
        updateTestAverage()
        testStatusCallback?.onRtcTestCompleted()
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {

    }

    override fun onUserOffline(uid: Int, reason: Int) {

    }

    override fun onAudioMetadataReceived(uid: Int, data: ByteArray?) {
        if (!joinChannelSuccess) {
            Log.d(TAG, "onAudioMetadataReceived: not in channel")
            return
        }

        try {
            val audioMetaData = data?.toString(Charsets.UTF_8)
            if (audioMetaData != null && audioMetaData.contains("-")) {
                audioMetaDataReceiveCount++
                val currentTime = System.currentTimeMillis()
                val parts = audioMetaData.split("-", limit = 2)

                if (parts.size >= 2) {
                    val timestamp = parts[0].toLongOrNull()
                    if (timestamp != null) {
                        val diff = currentTime - timestamp
                        receiverMessageDiffSum += diff
                        val avgDiff = receiverMessageDiffSum / audioMetaDataReceiveCount

                        writeMessageToFile(
                            "Receive rtc audio meta data: ${audioMetaData}, diff: ${diff}ms " +
                                    "Average diff: ${avgDiff}ms sendCount: $audioMetaDataSendCount " +
                                    "receiveCount: $audioMetaDataReceiveCount"
                        )
                    } else {
                        Log.e(TAG, "Failed to parse timestamp from audio metadata: ${parts[0]}")
                    }
                } else {
                    Log.e(TAG, "Malformed audio metadata: $audioMetaData")
                }
            } else {
                Log.d(TAG, "Invalid audio metadata format: $audioMetaData")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio metadata: ${e.message}")
        }
    }

    override fun onRecordAudioFrame(data: ByteArray?) {
        CoroutineScope(Dispatchers.Default).launch {
            testStatusCallback?.onRecordAudioFrame(data)
        }

        CoroutineScope(Dispatchers.Default).launch {
            sendAudioFrame(data ?: ByteArray(0))
        }
    }

}