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
    private const val SESSION_TIMEOUT_MS = 500L  // Session timeout duration (shared by v2 and v8)
    private const val SESSION_TIMEOUT_MIN_MS = 200L  // v2 protocol min timeout (for session end)

    // ========== Coroutine Related ==========
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mSessionTimeoutJob: Job? = null
    private var mAudioFrameFinishJob: Job? = null

    // ========== Callback Interface ==========
    private var mCallback: ICallback? = null

    // ========== PTS Generation State ==========
    // v8 protocol state
    private var mSessionId8: Int = 0  // 8-bit session ID (0-255)
    private var mSentenceId12: Int = 0  // 12-bit sentence ID for data packets
    private var mBasePts16: Int = 0  // 16-bit base PTS

    // v2 protocol state
    private var mSessionId18: Int = 1  // 18-bit session ID (v2 protocol)
    private var mBasePts32: Long = 0L  // 32-bit base PTS (v2 protocol)

    // ========== Session Tracking State ==========
    // Common state (shared by v2 and v8)
    private var mLastReceivedPts: Long = 0L  // Last received PTS (for duplicate detection)
    private var mLastEndedSessionId: Int = -1  // Last ended session ID (avoid duplicate callbacks)
    private var mLastPayload: SessionPayload? =
        null  // Last session payload (for protocol state tracking)

    // v8 protocol state
    private var mCurrentSessionId: Int = -1  // Current active session ID
    private var mLastEndCommandSessionId: Int = -1  // Last session ID that received end command
    private var mLastInterruptCommandSessionId: Int =
        -1  // Last session ID that received interrupt command

    // ========== Data Classes ==========
    /**
     * Session payload data (shared by v2 and v8 protocols)
     *
     * For v8 protocol:
     * @param sessionId 8-bit session identifier
     * @param cmdOrDataType 0=data packet, 1=command packet
     * @param sentenceId 12-bit sentence ID (valid for data packets only)
     * @param cmdType 5-bit command type (valid for command packets only): 1=session_end, 2=session_interrupt
     * @param sessionDurInPacks 12-bit session duration in packets (valid for cmd_type=1 only)
     *
     * For v2 protocol:
     * @param sessionId identifier of the dialog session
     * @param sentenceId identifier of the sentence within the session
     * @param chunkId identifier of the chunk within the sentence
     * @param isSessionEnd whether this marks the end of the session
     */
    data class SessionPayload(
        val sessionId: Int,
        val sentenceId: Int = 0,
        val chunkId: Int = 0,
        val cmdOrDataType: Int = 0,
        val cmdType: Int = 0,
        val sessionDurInPacks: Int = 0,
        val isSessionEnd: Boolean = false
    )

    // ========== Callback Interface Definition ==========
    /**
     * Session event callback interface
     */
    interface ICallback {
        /**
         * Session start callback
         * @param sessionId Session identifier
         */
        fun onSessionStart(sessionId: Int) {}

        /**
         * Session end callback
         * @param sessionId Session identifier
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
     * Generate PTS (public interface, dispatches by protocol version)
     * @param data Audio data
     * @param sampleRate Sample rate
     * @param channels Number of channels
     * @param isSessionEnd Whether it's session end
     * @return 64-bit PTS
     */
    @Synchronized
    fun generatePts(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Long {
        return when (PROTOCOL_VERSION) {
            2 -> generatePtsV2(data, sampleRate, channels, isSessionEnd)
            8 -> generatePtsV8(data, sampleRate, channels, 0, 0, isSessionEnd)
            else -> {
                LogUtils.w(
                    TAG,
                    "generatePts: unsupported protocol version=$PROTOCOL_VERSION, using v8"
                )
                generatePtsV8(data, sampleRate, channels, 0, 0, isSessionEnd)
            }
        }
    }

    /**
     * Generate PTS (v2 protocol)
     *
     * Bit layout (MSB -> LSB):
     * [3 bits: version] | [18 bits: sessionId] | [10 bits: last_chunk_duration_ms] |
     * [1 bit: isSessionEnd] | [32 bits: basePts]
     *
     * Rules:
     * - version defaults to 1
     * - sessionId is an 18-bit internal counter, always increments (wraps to 1 after 0x3FFFF)
     * - last_chunk_duration_ms: when {@code isSessionEnd == true}, set to {@code durationMs & 0x3FF}; otherwise 0
     * - basePts: 32-bit rolling timestamp accumulator (adds {@code durationMs} each call, wraps at 2^32)
     * - durationMs is computed from PCM length: {@code durationMs = data.size / ((sampleRate * channels * 2) / 1000)} for 16-bit PCM
     *
     * @param data raw PCM bytes of the current frame (16-bit PCM)
     * @param sampleRate sample rate in Hz (e.g., 48000)
     * @param channels number of audio channels (e.g., 1 for mono)
     * @param isSessionEnd whether the current frame marks the end of the whole session
     * @return 64-bit PTS encoded
     */
    @Synchronized
    fun generatePtsV2(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Long {
        val version = 1
        val safeVersion = version and 0x7
        val sessionId = mSessionId18 and 0x3FFFF
        val durationMs = if (data.isNotEmpty() && sampleRate > 0 && channels > 0) {
            data.size / ((sampleRate * channels * 2) / 1000) // 16-bit PCM
        } else {
            0
        }
        val lastChunkDuration = if (isSessionEnd) (durationMs and 0x3FF) else 0
        val basePts32 = mBasePts32

        var pts = 0L
        pts = pts or ((safeVersion.toLong() and 0x7L) shl 61)
        pts = pts or ((sessionId.toLong() and 0x3FFFFL) shl 43)
        pts = pts or ((lastChunkDuration.toLong() and 0x3FFL) shl 33)
        pts = pts or ((((if (isSessionEnd) 1 else 0).toLong()) and 0x1L) shl 32)
        pts = pts or (basePts32 and 0xFFFF_FFFFL)

        LogUtils.d(
            TAG,
            "generatePtsV2 pts:${String.format("0x%016X", pts)} isSessionEnd:$isSessionEnd " +
                    "sessionId:$sessionId durationMs:$durationMs lastChunkDurationMs:$lastChunkDuration basePts32:$basePts32"
        )

        if (isSessionEnd) {
            // advance session id every call, wrap to 1
            mSessionId18 = if (mSessionId18 >= 0x3FFFF) 1 else mSessionId18 + 1
            mBasePts32 = 0L
        } else {
            mBasePts32 = (mBasePts32 + (durationMs.toLong() and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
        }

        return pts
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

        when (PROTOCOL_VERSION) {
            2 -> processAudioFrameV2(data, pts)
            8 -> processAudioFrameV8(data, pts)
            else -> {
                LogUtils.w(
                    TAG,
                    "processAudioFrame: unsupported protocol version=$PROTOCOL_VERSION"
                )
            }
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
            sessionId = sessionId,
            sentenceId = sentenceId,
            cmdOrDataType = cmdOrDataType,
            cmdType = cmdType,
            sessionDurInPacks = sessionDurInPacks
        )

        // Detect duplicate packets
        val isDuplicatePacket = (pts == mLastReceivedPts)

        LogUtils.d(
            TAG,
            "processAudioFrameV8 version:$version payload:$currentPayload basePts:$basePts " +
                    "pts:${String.format("0x%016X", pts)} isDuplicate:$isDuplicatePacket"
        )

        // Handle session change
        handleSessionChange(sessionId, mCurrentSessionId, "V8")

        // Handle timeout logic
        handleTimeoutRestart(isDuplicatePacket, pts, sessionId, true)

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

    // ========== Private Methods: v2 Protocol Specific ==========
    /**
     * Process audio frame (v2 protocol)
     *
     * When {@code version = 2}, PTS bit layout:
     * [high 4 bits: version] | [16 bits: sessionId] | [16 bits: sentenceId] |
     * [10 bits: chunkId] | [2 bits: isEnd] | [low 16 bits: basePts]
     *
     * End detection logic:
     * - Immediate switches:
     *   - sessionId changes: immediately report previous session end (isSessionEnd = true)
     *   - sentenceId changes (same session): immediately report previous sentence end (isSessionEnd = false)
     * - Silence timeout window (500ms): if no new frame arrives in time, report end for the last payload:
     *   - isEnd == 0 -> sentence end (isSessionEnd = false)
     *   - isEnd != 0 -> session end (isSessionEnd = true)
     * - Duplicate packet detection: same PTS does not restart timeout timer
     *
     * @param data raw PCM audio bytes of the current frame
     * @param pts 64-bit PTS carried with the frame. Only version = 2 is processed currently; other versions are ignored
     */
    private fun processAudioFrameV2(data: ByteArray, pts: Long) {
        // Detect duplicate packets
        val isDuplicatePacket = (pts == mLastReceivedPts)

        val version = ((pts ushr 60) and 0xFL).toInt()
        val sessionId = ((pts ushr 44) and 0xFFFFL).toInt()
        val sentenceId = ((pts ushr 28) and 0xFFFFL).toInt()
        val chunkId = ((pts ushr 18) and 0x3FFL).toInt()
        val isEndBits = ((pts ushr 16) and 0x3L).toInt()
        val basePts = (pts and 0xFFFFL).toInt()
        val isSessionEnd = isEndBits != 0

        val currentPayload = SessionPayload(
            sessionId = sessionId,
            sentenceId = sentenceId,
            chunkId = chunkId,
            isSessionEnd = isSessionEnd
        )

        LogUtils.d(
            TAG,
            "processAudioFrameV2 version:$version currentPayload:$currentPayload basePts:$basePts " +
                    "pts:${String.format("0x%016X", pts)} isDuplicate:$isDuplicatePacket"
        )

        val previousPayload = mLastPayload
        mLastPayload = currentPayload

        // Handle session change
        val oldSessionId = previousPayload?.sessionId
        if (previousPayload == null || previousPayload.sessionId != sessionId) {
            handleSessionChange(sessionId, oldSessionId, "V2")
            handleTimeoutRestart(false, pts, sessionId, false)  // Always restart timeout on session change
            return
        }

        // Handle session end
        if (isSessionEnd) {
            LogUtils.d(TAG, "V2: session end detected for session $sessionId")
            if (!isSessionEnded(sessionId)) {
                try {
                    mCallback?.onSessionEnd(sessionId)
                } catch (e: Exception) {
                    LogUtils.e(TAG, "processAudioFrameV2: onSessionEnd error: ${e.message}")
                }
                mLastEndedSessionId = sessionId
            }
            // Session ended, cancel timeout and update PTS
            mAudioFrameFinishJob?.cancel()
            updateLastReceivedPts(pts)
            return
        }

        // Handle duplicate packet detection for timeout restart
        handleTimeoutRestart(isDuplicatePacket, pts, sessionId, false)
    }

    /**
     * Start or restart audio frame finish timeout (v2 protocol)
     */
    private fun startAudioFrameFinishTimeout() {
        mAudioFrameFinishJob?.cancel()
        mAudioFrameFinishJob = mSingleThreadScope.launch {
            try {
                val delayTime =
                    if (mLastPayload?.isSessionEnd == true) SESSION_TIMEOUT_MIN_MS else SESSION_TIMEOUT_MS
                delay(delayTime)
                LogUtils.d(TAG, "V2: Session timeout after ${delayTime}ms")
                synchronized(this@AudioFrameManager) {
                    val sessionId = mLastPayload?.sessionId
                    if (sessionId != null && !isSessionEnded(sessionId)) {
                        try {
                            mCallback?.onSessionEnd(sessionId)
                        } catch (e: Exception) {
                            LogUtils.e(
                                TAG,
                                "startAudioFrameFinishTimeout: onSessionEnd error: ${e.message}"
                            )
                        }
                        mLastEndedSessionId = sessionId
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "startAudioFrameFinishTimeout error: ${e.message}")
            }
        }
    }


    // ========== Private Methods: Common Utilities ==========
    /**
     * Handle session change (common for v2 and v8 protocols)
     * @param newSessionId The new session ID
     * @param oldSessionId The old session ID (null for first session or v8 using mCurrentSessionId)
     * @param protocolName Protocol name for logging ("V2" or "V8")
     */
    private fun handleSessionChange(newSessionId: Int, oldSessionId: Int?, protocolName: String) {
        // Check if session unchanged
        if (oldSessionId == newSessionId) {
            return
        }

        // Handle old session end
        if (oldSessionId != null && oldSessionId != -1) {
            if (!isSessionEnded(oldSessionId)) {
                LogUtils.d(TAG, "$protocolName: Session changed from $oldSessionId to $newSessionId")
                try {
                    mCallback?.onSessionEnd(oldSessionId)
                } catch (e: Exception) {
                    LogUtils.e(TAG, "handleSessionChange: onSessionEnd error: ${e.message}")
                }
                mLastEndedSessionId = oldSessionId
            } else {
                LogUtils.d(
                    TAG,
                    "$protocolName: Session changed from $oldSessionId to $newSessionId (already ended)"
                )
            }
        } else {
            // First session
            LogUtils.d(TAG, "$protocolName: First session started: $newSessionId")
        }

        // Start new session
        if (protocolName == "V8") {
            mCurrentSessionId = newSessionId
        }
        try {
            mCallback?.onSessionStart(newSessionId)
        } catch (e: Exception) {
            LogUtils.e(TAG, "handleSessionChange: onSessionStart error: ${e.message}")
        }
    }

    /**
     * Handle timeout restart logic based on duplicate packet detection
     * @param isDuplicatePacket Whether the current packet is a duplicate
     * @param pts Current PTS value
     * @param sessionId Current session ID
     * @param isV8Protocol Whether this is v8 protocol (true) or v2 protocol (false)
     */
    private fun handleTimeoutRestart(
        isDuplicatePacket: Boolean,
        pts: Long,
        sessionId: Int,
        isV8Protocol: Boolean
    ) {
        if (!isDuplicatePacket) {
            // Received new PTS, restart timeout timer
            val protocolName = if (isV8Protocol) "V8" else "V2"
            LogUtils.d(TAG, "$protocolName: Received new PTS, restarting timeout for session $sessionId")
            if (isV8Protocol) {
                startSessionTimeout(sessionId)
            } else {
                startAudioFrameFinishTimeout()
            }
            updateLastReceivedPts(pts)
        } else {
            // Received duplicate packet, do not restart timeout timer
            val protocolName = if (isV8Protocol) "V8" else "V2"
            LogUtils.w(
                TAG,
                "$protocolName: Received duplicate PTS: ${String.format("0x%016X", pts)}, timeout continues"
            )
        }
    }

    // ========== Private Methods: v8 Protocol Specific ==========

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
                    if (!isSessionEnded(sessionId)) {
                        try {
                            mCallback?.onSessionEnd(sessionId)
                        } catch (e: Exception) {
                            LogUtils.e(
                                TAG,
                                "handleCommandPacketV8: onSessionEnd error: ${e.message}"
                            )
                        }
                        mLastEndedSessionId = sessionId
                    }
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
                    if (!isSessionEnded(sessionId)) {
                        try {
                            mCallback?.onSessionInterrupt(sessionId)
                        } catch (e: Exception) {
                            LogUtils.e(
                                TAG,
                                "handleCommandPacketV8: onSessionInterrupt error: ${e.message}"
                            )
                        }
                        mLastEndedSessionId = sessionId
                    }
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
     * Check if session has already ended (avoid duplicate callbacks)
     * @return true if session already ended, false otherwise
     */
    private fun isSessionEnded(sessionId: Int): Boolean {
        return mLastEndedSessionId == sessionId
    }

    /**
     * Mark session as ended and update last received PTS
     */
    private fun markSessionEnded(sessionId: Int, pts: Long) {
        mLastEndedSessionId = sessionId
        mLastReceivedPts = pts
    }

    /**
     * Update last received PTS (for duplicate detection)
     */
    private fun updateLastReceivedPts(pts: Long) {
        mLastReceivedPts = pts
    }

    /**
     * Start or restart session timeout timer (v8 protocol)
     */
    private fun startSessionTimeout(sessionId: Int) {
        mSessionTimeoutJob?.cancel()
        mSessionTimeoutJob = mSingleThreadScope.launch {
            try {
                delay(SESSION_TIMEOUT_MS)
                LogUtils.d(TAG, "Session timeout: session=$sessionId after ${SESSION_TIMEOUT_MS}ms")
                synchronized(this@AudioFrameManager) {
                    if (mCurrentSessionId == sessionId && !isSessionEnded(sessionId)) {
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
        // Common state
        mLastReceivedPts = 0L
        mLastEndedSessionId = -1
        mLastPayload = null

        // v8 protocol state
        mSessionId8 = 0
        mSentenceId12 = 0
        mBasePts16 = 0
        mCurrentSessionId = -1
        mLastEndCommandSessionId = -1
        mLastInterruptCommandSessionId = -1

        // v2 protocol state
        mSessionId18 = 1
        mBasePts32 = 0L
    }
}
