package io.agora.ai.test.agora

import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Audio Frame Manager
 * Responsible for PTS generation, parsing, and session lifecycle management
 * Current protocol version: v8
 */
object AudioFrameManager {
    private const val TAG = "${ExamplesConstants.TAG}-AudioFrameManager"

    // ========== Protocol Configuration Constants ==========
    private const val PROTOCOL_VERSION = 8  // Current protocol version
    private const val SESSION_TIMEOUT_MS = 500L  // Session timeout duration

    // ========== Coroutine Related ==========
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mSessionTimeoutJob: Job? = null
    private var mAudioFrameFinishJob: Job? = null

    // ========== Callback Interface ==========
    private var mCallback: ICallback? = null

    // ========== PTS Generation State ==========
    private var mSessionId8: Int = 0  // 8-bit session ID (0-255)
    private var mSentenceId12: Int = 0  // 12-bit sentence ID for data packets
    private var mBasePts16: Int = 0  // 16-bit base PTS

    // ========== Session Tracking State ==========
    private var mCurrentSessionId: Int = -1  // Current active session ID
    private var mLastReceivedPts: Long = 0L  // Last received PTS (for duplicate detection)
    private var mLastEndedSessionId: Int = -1  // Last ended session ID (avoid duplicate callbacks)
    private var mLastEndCommandSessionId: Int = -1  // Last session ID that received end command
    private var mLastInterruptCommandSessionId: Int =
        -1  // Last session ID that received interrupt command

    // ========== Data Classes ==========
    /**
     * Session payload data
     * @param sessionId 8-bit session identifier
     * @param cmdOrDataType 0=data packet, 1=command packet
     * @param sentenceId 12-bit sentence ID (valid for data packets only)
     * @param cmdType 5-bit command type (valid for command packets only): 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks 12-bit session duration in packets (valid for cmd_type=1 only)
     */
    data class SessionPayload(
        val sessionId: Int,
        val cmdOrDataType: Int,
        val sentenceId: Int = 0,
        val cmdType: Int = 0,
        val sessionDurInPacks: Int = 0
    )

    // ========== Callback Interface Definition ==========
    /**
     * Session event callback interface
     */
    interface ICallback {
        /**
         * Session start callback
         * @param sessionId Session identifier (8-bit, 0-255)
         */
        fun onSessionStart(sessionId: Int) {}

        /**
         * Session end callback
         * @param sessionId Session identifier (8-bit, 0-255)
         */
        fun onSessionEnd(sessionId: Int) {}

        /**
         * Session interrupt callback
         * @param sessionId Session identifier (8-bit, 0-255)
         */
        fun onSessionInterrupt(sessionId: Int) {}
    }

    // ========== Public API ==========
    /**
     * Initialize AudioFrameManager
     * @param callback Session event callback
     */
    @JvmStatic
    @Synchronized
    fun init(callback: ICallback) {
        LogUtils.d(TAG, "AudioFrameManager init, protocol version: $PROTOCOL_VERSION")
        mCallback = callback
    }

    /**
     * Generate PTS (legacy interface for compatibility)
     * @param data Audio data
     * @param sampleRate Sample rate
     * @param channels Number of channels
     * @param isSessionEnd Whether it's session end (deprecated, kept for compatibility)
     * @return 64-bit PTS
     */
    @Synchronized
    fun generatePts(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Long {
        if (PROTOCOL_VERSION == 8) {
            return generatePtsV8(data, sampleRate, channels, 0, 0, isSessionEnd)
        }
        return 0L
    }

    /**
     * Generate PTS (v8 protocol)
     *
     * Bit layout (MSB -> LSB):
     * [1 bit: fixed=0] | [1 bit: is_agora] | [4 bits: version] | [8 bits: session_id] |
     * [1 bit: cmd_or_data_type] | [...] | [16 bits: base_pts]
     *
     * Data packet (cmd_or_data_type=0):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:0] | [12:sentence_id] |
     * [5:reserved] | [16:reserved] | [16:base_pts]
     *
     * Command packet (cmd_or_data_type=1):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:1] | [5:cmd_type] |
     * [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
     *
     * @param data Audio data
     * @param sampleRate Sample rate
     * @param channels Number of channels
     * @param cmdType Command type: 0=data packet, 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks Session duration in packets (valid for cmd_type=1 only)
     * @param isSessionEnd Legacy parameter (kept for compatibility)
     * @return 64-bit PTS
     */
    @Synchronized
    fun generatePtsV8(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        cmdType: Int,
        sessionDurInPacks: Int = 0,
        isSessionEnd: Boolean
    ): Long {
        // Protocol fields
        val fixed = 0  // Fixed to 0
        val isAgora = 1  // Agora identifier
        val version = 0  // Protocol version
        val sessionId = mSessionId8 and 0xFF
        val cmdOrDataType = if (cmdType == 0) 0 else 1
        val basePts = mBasePts16 and 0xFFFF

        // Calculate audio data duration (ms)
        val byteDataMs = if (data.isNotEmpty() && sampleRate > 0 && channels > 0) {
            data.size * 1000 / (sampleRate * channels * 2)
        } else {
            0
        }

        // Build PTS
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
        } else {
            // Command packet: [5:cmd_type] | [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
            val safeCmdType = cmdType and 0x1F
            pts = pts or ((safeCmdType.toLong() and 0x1FL) shl 44)
            if (cmdType == 1) {
                val safeSessionDur = sessionDurInPacks and 0xFFF
                pts = pts or ((safeSessionDur.toLong() and 0xFFFL) shl 32)
            }
        }

        pts = pts or (basePts.toLong() and 0xFFFFL)

        // Update counters
        if (cmdOrDataType == 0) {
            mSentenceId12 = (mSentenceId12 + 1) and 0xFFF
        }
        mBasePts16 = (mBasePts16 + byteDataMs) and 0xFFFF

        return pts
    }

    /**
     * Process audio frame (public interface, dispatches by protocol version)
     *
     * @param data Audio data
     * @param pts 64-bit PTS
     */
    @Synchronized
    fun processAudioFrame(data: ByteArray, pts: Long) {
        if (pts == 0L) {
            LogUtils.w(TAG, "processAudioFrame: invalid pts=0, ignored")
            return
        }

        if (PROTOCOL_VERSION == 8) {
            processAudioFrameV8(data, pts)
        }
    }

    /**
     * Process audio frame (v8 protocol)
     *
     * v8 Protocol PTS Bit Layout (MSB -> LSB, total 64 bits):
     * [1 bit: fixed=0] | [1 bit: is_agora] | [4 bits: version] | [8 bits: session_id] |
     * [1 bit: cmd_or_data_type] | [...] | [16 bits: base_pts]
     *
     * Data packet (cmd_or_data_type=0):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:0] | [12:sentence_id] |
     * [5:reserved] | [16:reserved] | [16:base_pts]
     * - bit 63: fixed=0 (fixed value)
     * - bit 62: is_agora=1 (Agora identifier)
     * - bit 58-61: version=8 (protocol version)
     * - bit 50-57: session_id (8-bit, 0-255)
     * - bit 49: cmd_or_data_type=0 (data packet identifier)
     * - bit 37-48: sentence_id (12-bit, sentence ID)
     * - bit 32-36: reserved (5-bit, reserved)
     * - bit 16-31: reserved (16-bit, reserved)
     * - bit 0-15: base_pts (16-bit, base timestamp)
     *
     * Command packet (cmd_or_data_type=1):
     * [1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:1] | [5:cmd_type] |
     * [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
     * - bit 63: fixed=0 (fixed value)
     * - bit 62: is_agora=1 (Agora identifier)
     * - bit 58-61: version=8 (protocol version)
     * - bit 50-57: session_id (8-bit, 0-255)
     * - bit 49: cmd_or_data_type=1 (command packet identifier)
     * - bit 44-48: cmd_type (5-bit, command type: 1=session_end, 2=session_interrupt)
     * - bit 32-43: session_dur_or_reserved (12-bit, session duration when cmd_type=1)
     * - bit 16-31: reserved (16-bit, reserved)
     * - bit 0-15: base_pts (16-bit, base timestamp)
     *
     * Session detection logic:
     * - New session start: session_id changes
     * - Session end: cmd_type=1 received or timeout (500ms)
     * - Session interrupt: cmd_type=2 received
     * - Duplicate packet detection: same PTS does not restart timeout timer
     *
     * @param data Audio data
     * @param pts 64-bit PTS
     */
    private fun processAudioFrameV8(data: ByteArray, pts: Long) {
        // Parse protocol fields
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
            // Command packet
            cmdType = ((pts ushr 44) and 0x1FL).toInt()
            if (cmdType == 1) {
                sessionDurInPacks = ((pts ushr 32) and 0xFFFL).toInt()
            }
        }

        val currentPayload = SessionPayload(
            sessionId,
            cmdOrDataType,
            sentenceId,
            cmdType,
            sessionDurInPacks
        )

        // Detect duplicate packets
        val isDuplicatePacket = (pts == mLastReceivedPts)

        LogUtils.d(
            TAG,
            "processAudioFrameV8 version:$version payload:$currentPayload basePts:$basePts " +
                    "pts:${String.format("0x%016X", pts)} isDuplicate:$isDuplicatePacket"
        )

        // Handle session change
        handleSessionChangeV8(sessionId)

        // Handle timeout logic
        handleSessionTimeoutV8(sessionId, isDuplicatePacket, pts)

        // Handle command packets
        if (cmdOrDataType == 1) {
            handleCommandPacketV8(sessionId, cmdType, sessionDurInPacks)
        }
    }

    /**
     * Set session ID
     * @param sessionId Session ID (0-255)
     */
    @Synchronized
    fun setSessionId(sessionId: Int) {
        val safeSessionId = sessionId and 0xFF
        mSessionId8 = safeSessionId
        mSentenceId12 = 0  // Reset sentence counter
        LogUtils.d(TAG, "setSessionId: $mSessionId8")
    }

    /**
     * Release resources
     */
    @Synchronized
    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")

        // Cancel all coroutines
        mSessionTimeoutJob?.cancel()
        mSessionTimeoutJob = null
        mAudioFrameFinishJob?.cancel()
        mAudioFrameFinishJob = null

        // Close executor
        try {
            mExecutor.close()
        } catch (e: Exception) {
            LogUtils.e(TAG, "release: executor close error: ${e.message}")
        }

        // Clear callback
        mCallback = null

        // Reset all state
        resetState()
    }

    // ========== Private Methods: v8 Protocol Specific ==========
    /**
     * Handle session change (v8 protocol)
     */
    private fun handleSessionChangeV8(newSessionId: Int) {
        if (mCurrentSessionId == newSessionId) {
            return  // Session unchanged
        }

        // Session changed
        if (mCurrentSessionId != -1) {
            // End old session (if not already ended)
            if (mLastEndedSessionId != mCurrentSessionId) {
                LogUtils.d(TAG, "V8: Session changed from $mCurrentSessionId to $newSessionId")
                try {
                    mCallback?.onSessionEnd(mCurrentSessionId)
                } catch (e: Exception) {
                    LogUtils.e(TAG, "handleSessionChangeV8: onSessionEnd error: ${e.message}")
                }
                mLastEndedSessionId = mCurrentSessionId
            } else {
                LogUtils.d(
                    TAG,
                    "V8: Session changed from $mCurrentSessionId to $newSessionId (already ended)"
                )
            }
        }

        // Start new session
        LogUtils.d(TAG, "V8: New session started: $newSessionId")
        mCurrentSessionId = newSessionId
        try {
            mCallback?.onSessionStart(newSessionId)
        } catch (e: Exception) {
            LogUtils.e(TAG, "handleSessionChangeV8: onSessionStart error: ${e.message}")
        }
    }

    /**
     * Handle session timeout logic (v8 protocol)
     */
    private fun handleSessionTimeoutV8(sessionId: Int, isDuplicatePacket: Boolean, pts: Long) {
        if (mCurrentSessionId != sessionId) {
            // New session, start timeout timer
            startSessionTimeout(sessionId)
            mLastReceivedPts = pts
        } else {
            // Same session
            if (!isDuplicatePacket) {
                // Received new PTS, restart timeout timer
                LogUtils.d(TAG, "V8: Received new PTS, restarting timeout for session $sessionId")
                startSessionTimeout(sessionId)
                mLastReceivedPts = pts
            } else {
                // Received duplicate packet, do not restart timeout timer
                LogUtils.w(
                    TAG,
                    "V8: Received duplicate PTS: ${
                        String.format(
                            "0x%016X",
                            pts
                        )
                    }, timeout continues"
                )
            }
        }
    }

    /**
     * Handle command packets (v8 protocol)
     */
    private fun handleCommandPacketV8(sessionId: Int, cmdType: Int, sessionDurInPacks: Int) {
        when (cmdType) {
            1 -> {
                // Session end command
                if (mLastEndCommandSessionId != sessionId) {
                    LogUtils.d(
                        TAG,
                        "V8: Session end command: session=$sessionId, duration=$sessionDurInPacks packets"
                    )
                    mLastEndCommandSessionId = sessionId
                    mSessionTimeoutJob?.cancel()
                    try {
                        mCallback?.onSessionEnd(sessionId)
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "handleCommandPacketV8: onSessionEnd error: ${e.message}")
                    }
                    mLastEndedSessionId = sessionId
                } else {
                    LogUtils.d(
                        TAG,
                        "V8: Ignoring duplicate session end command for session $sessionId"
                    )
                }
            }

            2 -> {
                // Session interrupt command
                if (mLastInterruptCommandSessionId != sessionId) {
                    LogUtils.d(TAG, "V8: Session interrupt command: session=$sessionId")
                    mLastInterruptCommandSessionId = sessionId
                    mSessionTimeoutJob?.cancel()
                    try {
                        mCallback?.onSessionInterrupt(sessionId)
                    } catch (e: Exception) {
                        LogUtils.e(
                            TAG,
                            "handleCommandPacketV8: onSessionInterrupt error: ${e.message}"
                        )
                    }
                    mLastEndedSessionId = sessionId
                } else {
                    LogUtils.d(
                        TAG,
                        "V8: Ignoring duplicate session interrupt command for session $sessionId"
                    )
                }
            }

            else -> {
                LogUtils.w(TAG, "V8: Unknown command type: $cmdType for session $sessionId")
            }
        }
    }

    // ========== Private Methods: Common Utilities ==========

    /**
     * Start or restart session timeout timer
     */
    private fun startSessionTimeout(sessionId: Int) {
        mSessionTimeoutJob?.cancel()
        mSessionTimeoutJob = mSingleThreadScope.launch {
            try {
                delay(SESSION_TIMEOUT_MS)
                LogUtils.d(TAG, "Session timeout: session=$sessionId after ${SESSION_TIMEOUT_MS}ms")
                synchronized(this@AudioFrameManager) {
                    if (mCurrentSessionId == sessionId && mLastEndedSessionId != sessionId) {
                        try {
                            mCallback?.onSessionEnd(sessionId)
                        } catch (e: Exception) {
                            LogUtils.e(TAG, "startSessionTimeout: onSessionEnd error: ${e.message}")
                        }
                        mLastEndedSessionId = sessionId
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(TAG, "Session timeout cancelled: ${e.message}")
            }
        }
    }

    /**
     * Reset all state
     */
    private fun resetState() {
        mSessionId8 = 0
        mSentenceId12 = 0
        mBasePts16 = 0
        mCurrentSessionId = -1
        mLastReceivedPts = 0L
        mLastEndedSessionId = -1
        mLastEndCommandSessionId = -1
        mLastInterruptCommandSessionId = -1
    }
}
