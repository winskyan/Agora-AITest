package io.agora.ai.rtm.test.rtc

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.rtm.RtmTestManager.TestStatusCallback
import io.agora.ai.rtm.test.utils.KeyCenter
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

class RtcTestManager(private val context: Context) : RtcManager.RtcMessageListener {

    companion object {
        private const val TAG = Constants.TAG + "-RtcTestManager"
        private const val SEND_FRAME_COUNT_ONCE = 4
    }

    private var joinChannelSuccess = false

    private var testStatusCallback: TestStatusCallback? = null

    // File output
    private var historyFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private var bufferedWriter: BufferedWriter? = null

    private var receiverMessageDiffSum = 0L

    private var audioMetaDataSendCount = 0
    private var audioMetaDataReceiveCount = 0

    private var pendingAudioDataByteBuffer: ByteBuffer? = null

    // Callbacks
    interface TestStatusCallback {
        fun onRtcTestStarted()
        fun onRtcTestProgress(message: String)
        fun onRtcTestCompleted()
    }

    fun initialize(
        callback: TestStatusCallback
    ) {
        this.testStatusCallback = callback

        RtcManager.create(context, KeyCenter.APP_ID, this)
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


    fun startTest(channelName: String) {
        joinChannelSuccess = false
        audioMetaDataSendCount = 0
        audioMetaDataReceiveCount = 0
        receiverMessageDiffSum = 0L
        pendingAudioDataByteBuffer?.clear()

        historyFileName =
            "history-rtc-${channelName}-${System.currentTimeMillis()}.txt"
        initWriter()

        writeMessageToFile("Demo version: ${BuildConfig.VERSION_NAME}", false)
        writeMessageToFile("RTC version: ${RtcManager.getRtcVersion()}", false)

        writeMessageToFile("joinChannel channel: $channelName")
        // Initialize history file
        RtcManager.joinChannel("", channelName, 0, io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER)
    }

    fun stopTest() {
        if (joinChannelSuccess) {
            joinChannelSuccess = false

            writeMessageToFile("leaveChannel")
            RtcManager.leaveChannel()
            updateTestAverage()
        }

        pendingAudioDataByteBuffer?.clear()
    }

    @Synchronized
    private fun sendAudioFrame(data: ByteArray) {
        if (!joinChannelSuccess) {
            Log.d(TAG, "sendAudioFrame: not in channel")
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

//                val audioMetaData =
//                    System.currentTimeMillis().toString() + "-" + Utils.byteArrayToBase64(audioData)
                val audioMetaData =
                    System.currentTimeMillis().toString() + "-" + "audioData"
                writeMessageToFile("Send audio meta data: $audioMetaData")
                RtcManager.sendAudioMetadata(audioMetaData.toByteArray())
                audioMetaDataSendCount++

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
            "Audio metadata test result: No data received, send count: $audioMetaDataSendCount"
        } else {
            val avgDiff = receiverMessageDiffSum / audioMetaDataReceiveCount
            "Audio metadata test result: Average delay ${avgDiff}ms, Success rate: ${audioMetaDataReceiveCount * 100 / audioMetaDataSendCount}%, " +
                    "Sent: $audioMetaDataSendCount, Received: $audioMetaDataReceiveCount"
        }
        updateHistoryUI(averageDiff)
    }

    private fun updateHistoryUI(message: String) {
        // Add timestamp to log message
        val timestamp = Constants.DATE_FORMAT.format(System.currentTimeMillis())
        val timestampedMessage = "$timestamp: $message"

        Log.d(TAG, timestampedMessage)
        testStatusCallback?.onRtcTestProgress(timestampedMessage) // Send timestamped message to UI
        writeMessageToFile(message) // The timestamp is added in writeMessageToFile method
    }

    fun release() {
        closeWriter()
        pendingAudioDataByteBuffer?.clear()
        pendingAudioDataByteBuffer = null
        RtcManager.destroy()
    }

    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
        joinChannelSuccess = true
        writeMessageToFile("onJoinChannelSuccess Channel: $channel uid:$uid")
        testStatusCallback?.onRtcTestStarted()
    }

    override fun onLeaveChannelSuccess() {
        joinChannelSuccess = false
        writeMessageToFile("onLeaveChannelSuccess")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error sleeping", e)
            }
            closeWriter()
        }
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
                            "Receive audio meta data: ${parts[0]}-[audio_data], diff: ${diff}ms " +
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
            sendAudioFrame(data ?: ByteArray(0))
        }
    }

}