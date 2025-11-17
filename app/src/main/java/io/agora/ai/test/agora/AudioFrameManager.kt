package io.agora.ai.test.agora

import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

object AudioFrameManager {
    private const val TAG = "${ExamplesConstants.TAG}-AudioFrameManager"
    private const val SESSION_TIMEOUT_MS = 500L

    private var mAudioFrameFinishJob: Job? = null
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mCallback: ICallback? = null

    // New protocol state tracking
    private var mSessionId8: Int = 0  // 8-bit session ID (0-255)
    private var mSentenceId12: Int = 0  // 12-bit sentence ID for data packets
    private var mBasePts16: Int = 0  // 16-bit base PTS
    private var mCurrentSessionId: Int = -1  // Track current active session
    private var mSessionTimeoutJob: Job? = null


    // Track processed command packets to avoid duplicate callbacks
    private var mLastEndCommandSessionId: Int = -1  // Last session that received end command
    private var mLastInterruptCommandSessionId: Int =
        -1  // Last session that received interrupt command
    private var mLastEndedSessionId: Int =
        -1  // Last session that ended (to avoid duplicate end events)

    /**
     * Callback interface to notify session events.
     *
     * Terminology:
     * - session: a round of dialog identified by session_id
     */
    interface ICallback {
        /**
         * Invoked when a new session starts (first data packet with new session_id).
         * @param sessionId identifier of the dialog session (8-bit, 0-255)
         */
        fun onSessionStart(sessionId: Int) {

        }

        /**
         * Invoked when a session ends (cmd_type=1 received or timeout).
         * @param sessionId identifier of the dialog session (8-bit, 0-255)
         */
        fun onSessionEnd(sessionId: Int) {

        }

        /**
         * Invoked when a session is interrupted (cmd_type=2 received).
         * @param sessionId identifier of the dialog session (8-bit, 0-255)
         */
        fun onSessionInterrupt(sessionId: Int) {

        }
    }

    /**
     * Parsed tracking unit from pts fields (new protocol).
     * @param sessionId 8-bit session identifier
     * @param cmdOrDataType 0=data, 1=cmd
     * @param sentenceId 12-bit sentence ID (for data packets)
     * @param cmdType 5-bit command type (for cmd packets): 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks 12-bit session duration in packets (for cmd_type=1)
     */
    data class SessionPayload(
        val sessionId: Int,
        val cmdOrDataType: Int,
        val sentenceId: Int = 0,  // Only valid for data packets
        val cmdType: Int = 0,     // Only valid for cmd packets
        val sessionDurInPacks: Int = 0  // Only valid for cmd_type=1
    )

    /**
     * Initialize the AudioFrameManager with a callback.
     * This method sets the callback that will be invoked when a sentence or session ends.
     * It logs the initialization message.
     *
     * @param callback callback to be invoked when a sentence or a whole session ends
     */
    @JvmStatic
    @Synchronized
    fun init(callback: ICallback) {
        LogUtils.d(TAG, "AudioFrameManager init")
        mCallback = callback
    }


    /**
     * Generate PTS (new protocol).
     *
     * Bit layout (MSB -> LSB):
     * [1 bit: fixed=0] | [1 bit: is_agora] | [4 bits: version] | [8 bits: session_id] |
     * [1 bit: cmd_or_data_type] | [...] | [16 bits: base_pts]
     *
     * For data packets (cmd_or_data_type=0):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:0] | [12:sentence_id] |
     * [5:reserved] | [16:reserved] | [16:base_pts]
     *
     * For cmd packets (cmd_or_data_type=1):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:1] | [5:cmd_type] |
     * [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
     *
     * @param data raw PCM bytes (ignored for cmd packets, can be empty)
     * @param sampleRate sample rate (ignored)
     * @param channels channels (ignored)
     * @param isSessionEnd legacy parameter (ignored)
     * @param cmdType command type: 0=data, 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks session duration in packets (only for cmd_type=1)
     * @return 64-bit PTS encoded
     */
    @Synchronized
    fun generatePts(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Long {
        return generatePtsNew(data, sampleRate, channels, 0, 0)
    }

    /**
     * Generate PTS with new protocol support.
     * @param cmdType 0=data, 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks session duration in packets (only for cmd_type=1)
     */
    @Synchronized
    fun generatePtsNew(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        cmdType: Int,
        sessionDurInPacks: Int = 0
    ): Long {
        val fixed = 0  // Always 0
        val isAgora = 1  // 1 for Agora
        val version = 1  // Version 1
        val sessionId = mSessionId8 and 0xFF
        val cmdOrDataType = if (cmdType == 0) 0 else 1
        val basePts = mBasePts16 and 0xFFFF

        val byteDataMs = data.size * 1000 / (sampleRate * channels * 2)

        var pts = 0L
        pts = pts or ((fixed.toLong() and 0x1L) shl 63)
        pts = pts or ((isAgora.toLong() and 0x1L) shl 62)
        pts = pts or ((version.toLong() and 0xFL) shl 58)
        pts = pts or ((sessionId.toLong() and 0xFFL) shl 50)
        pts = pts or ((cmdOrDataType.toLong() and 0x1L) shl 49)

        if (cmdOrDataType == 0) {
            // Data packet: [12:sentence_id] | [5:reserved] | [16:reserved] | [16:base_pts]
            val sentenceId = mSentenceId12 and 0xFFF
            pts = pts or ((sentenceId.toLong() and 0xFFFL) shl 37)
            // 5-bit reserved at bits 32-36 (kept as 0)
            // 16-bit reserved at bits 16-31 (kept as 0)
        } else {
            // Cmd packet: [5:cmd_type] | [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
            val safeCmdType = cmdType and 0x1F
            pts = pts or ((safeCmdType.toLong() and 0x1FL) shl 44)
            if (cmdType == 1) {
                // Session end: use sessionDurInPacks
                val safeSessionDur = sessionDurInPacks and 0xFFF
                pts = pts or ((safeSessionDur.toLong() and 0xFFFL) shl 32)
            }
            // 16-bit reserved at bits 16-31 (kept as 0)
        }

        pts = pts or (basePts.toLong() and 0xFFFFL)

        LogUtils.d(
            TAG,
            "generatePtsNew pts:$pts ${
                String.format(
                    "0x%016X",
                    pts
                )
            } isAgora:${isAgora} version:${version} sessionId:$sessionId cmdType:$cmdType " +
                    "cmdOrDataType:$cmdOrDataType sentenceId:${if (cmdOrDataType == 0) mSentenceId12 else -1} byteDataMs:${byteDataMs} basePts:$basePts"
        )

        // Advance counters
        if (cmdOrDataType == 0) {
            // Data packet: increment sentence ID and base PTS
            mSentenceId12 = (mSentenceId12 + 1) and 0xFFF
        }
        mBasePts16 = (mBasePts16 + byteDataMs) and 0xFFFF  // Always increment by 10ms

        return pts
    }

    /**
     * Process an audio frame with new protocol.
     *
     * New PTS bit layout:
     * [1:fixed=0] | [1:is_agora] | [4:version] | [8:session_id] | [1:cmd_or_data_type] | [...] | [16:base_pts]
     *
     * Session detection logic:
     * - New session start: when session_id changes from previous
     * - Session end: when cmd_type=1 received or timeout (500ms)
     * - Session interrupt: when cmd_type=2 received
     *
     * @param data raw PCM audio bytes of the current frame
     * @param pts 64-bit PTS carried with the frame
     */
    @Synchronized
    fun processAudioFrame(data: ByteArray, pts: Long) {
        if (pts == 0L) {
            return
        }

        // Parse new protocol
        val fixed = ((pts ushr 63) and 0x1L).toInt()
        val isAgora = ((pts ushr 62) and 0x1L).toInt()
        val version = ((pts ushr 58) and 0xFL).toInt()
        val sessionId = ((pts ushr 50) and 0xFFL).toInt()
        val cmdOrDataType = ((pts ushr 49) and 0x1L).toInt()
        val basePts = (pts and 0xFFFFL).toInt()

        var sentenceId = 0
        var cmdType = 0
        var sessionDurInPacks = 0

        if (cmdOrDataType == 0) {
            // Data packet
            sentenceId = ((pts ushr 37) and 0xFFFL).toInt()
        } else {
            // Cmd packet
            cmdType = ((pts ushr 44) and 0x1FL).toInt()
            if (cmdType == 1) {
                sessionDurInPacks = ((pts ushr 32) and 0xFFFL).toInt()
            }
        }

        val currentPayload =
            SessionPayload(sessionId, cmdOrDataType, sentenceId, cmdType, sessionDurInPacks)

        LogUtils.d(
            TAG,
            "processAudioFrame version:$version currentPayload:$currentPayload  basePts:$basePts pts:$pts pts:${
                String.format(
                    "0x%016X",
                    pts
                )
            }"
        )

        // Handle session changes and commands
        if (mCurrentSessionId != sessionId && mCurrentSessionId != -1) {
            // Session changed - end previous session only if it hasn't already ended
            if (mLastEndedSessionId != mCurrentSessionId) {
                LogUtils.d(TAG, "Session changed from $mCurrentSessionId to $sessionId")
                mCallback?.onSessionEnd(mCurrentSessionId)
                mLastEndedSessionId = mCurrentSessionId
            } else {
                LogUtils.d(
                    TAG,
                    "Session changed from $mCurrentSessionId to $sessionId (previous session already ended)"
                )
            }
        }

        if (mCurrentSessionId != sessionId) {
            // New session started
            LogUtils.d(TAG, "New session started: $sessionId")
            mCurrentSessionId = sessionId
            mCallback?.onSessionStart(sessionId)
            startSessionTimeout(sessionId)
        } else {
            // Same session - restart timeout
            startSessionTimeout(sessionId)
        }

        // Handle command packets - only process first packet for each session
        if (cmdOrDataType == 1) {
            when (cmdType) {
                1 -> {
                    // Session end command - only process first packet for this session
                    if (mLastEndCommandSessionId != sessionId) {
                        LogUtils.d(
                            TAG,
                            "Session end command received for session $sessionId, duration: $sessionDurInPacks packets"
                        )
                        mLastEndCommandSessionId = sessionId
                        mSessionTimeoutJob?.cancel()
                        mCallback?.onSessionEnd(sessionId)
                        mLastEndedSessionId = sessionId
                    } else {
                        LogUtils.d(
                            TAG,
                            "Ignoring duplicate session end command for session $sessionId"
                        )
                    }
                }

                2 -> {
                    // Session interrupt command - only process first packet for this session
                    if (mLastInterruptCommandSessionId != sessionId) {
                        LogUtils.d(TAG, "Session interrupt command received for session $sessionId")
                        mLastInterruptCommandSessionId = sessionId
                        mSessionTimeoutJob?.cancel()
                        mCallback?.onSessionInterrupt(sessionId)
                        mLastEndedSessionId = sessionId
                    } else {
                        LogUtils.d(
                            TAG,
                            "Ignoring duplicate session interrupt command for session $sessionId"
                        )
                    }
                }
            }
        }
    }

    /**
     * Start or restart session timeout mechanism.
     */
    private fun startSessionTimeout(sessionId: Int) {
        mSessionTimeoutJob?.cancel()
        mSessionTimeoutJob = mSingleThreadScope.launch {
            delay(SESSION_TIMEOUT_MS)
            LogUtils.d(
                TAG,
                "Session timeout for sessionId: $sessionId after ${SESSION_TIMEOUT_MS}ms"
            )
            if (mCurrentSessionId == sessionId) {
                mCallback?.onSessionEnd(sessionId)
                mLastEndedSessionId = sessionId
            }
        }
    }

    /**
     * Release the AudioFrameManager.
     * Cancels internal jobs, shuts down the executor and clears internal states/counters.
     */
    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")
        mAudioFrameFinishJob?.cancel()
        mSessionTimeoutJob?.cancel()
        mExecutor.close()
        mCallback = null

        // Reset new protocol state
        mSessionId8 = 0
        mSentenceId12 = 0
        mBasePts16 = 0
        mCurrentSessionId = -1
        mLastEndCommandSessionId = -1
        mLastInterruptCommandSessionId = -1
        mLastEndedSessionId = -1
    }

    /**
     * Set the current session ID for generating PTS.
     * Call this when starting a new session.
     */
    fun setSessionId(sessionId: Int) {
        mSessionId8 = sessionId and 0xFF
        mSentenceId12 = 0  // Reset sentence counter for new session
        LogUtils.d(TAG, "Set session ID to: $mSessionId8")
    }

    /**
     * Send session end command packets (10 packets for reliability).
     * @param sessionDurInPacks duration of the session in packets
     */
    fun sendSessionEndCommand(sessionDurInPacks: Int) {
        LogUtils.d(
            TAG,
            "Sending session end command for session $mSessionId8, duration: $sessionDurInPacks packets"
        )
        // Send 10 packets for reliability as specified in protocol
        repeat(10) {
            val pts = generatePtsNew(ByteArray(0), 0, 0, 1, sessionDurInPacks)
            // In real implementation, this PTS would be sent with pushExternalAudioFrame
            LogUtils.d(
                TAG,
                "Session end command packet ${it + 1}/10: ${String.format("0x%016X", pts)}"
            )
        }
    }

    /**
     * Send session interrupt command packets (10 packets for reliability).
     */
    fun sendSessionInterruptCommand() {
        LogUtils.d(TAG, "Sending session interrupt command for session $mSessionId8")
        // Send 10 packets for reliability as specified in protocol
        repeat(10) {
            val pts = generatePtsNew(ByteArray(0), 0, 0, 2, 0)
            // In real implementation, this PTS would be sent with pushExternalAudioFrame
            LogUtils.d(
                TAG,
                "Session interrupt command packet ${it + 1}/10: ${String.format("0x%016X", pts)}"
            )
        }
    }
}