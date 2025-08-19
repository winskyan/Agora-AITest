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
    private const val PLAYBACK_AUDIO_FRAME_TIMEOUT_MS: Long = 200 // ms

    private var mAudioFrameFinishJob: Job? = null
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mCallback: ICallback? = null
    private var mLastPayload: SentencePayload? = null

    // v2-specific counters
    private var mV2SessionId16: Int = 1
    private var mV2ChunkId10: Int = 1

    // v3-specific counters
    private var mSessionId4Bits: Int = 1
    private var mChunkId24Bits: Int = 1
    private var mSessionDuration16Bits: Int = 0
    private var mSessionBasePts16Bits: Int = 0

    // v4-specific counters
    private var mV4SessionId18: Int = 1
    private var mV4BasePts32: Long = 0L

    /**
     * Callback interface to notify sentence/session end events.
     *
     * Terminology:
     * - session: a full round of dialog from start to end
     * - sentence: a single utterance within a session
     * - chunk: a fragment of audio belonging to one sentence
     * - isSessionEnd: whether this marks the end of the session
     */
    interface ICallback {
        /**
         * Invoked when a sentence or a whole session ends.
         * @param sessionId identifier of the dialog session
         * @param sentenceId identifier of the sentence within the session
         * @param chunkId identifier of the chunk within the sentence
         * @param isSessionEnd true if a session end is detected; false if only a sentence end is detected
         */
        fun onSentenceEnd(sessionId: Int, sentenceId: Int, chunkId: Int, isSessionEnd: Boolean) {

        }
    }

    /**
     * Parsed tracking unit from pts fields.
     * @param sessionId identifier of the dialog session
     * @param sentenceId identifier of the sentence within the session
     * @param chunkId identifier of the chunk within the sentence
     * @param isSessionEnd whether this marks the end of the session
     */
    data class SentencePayload(
        val sessionId: Int,
        val sentenceId: Int,
        val chunkId: Int,
        val isSessionEnd: Boolean
    )

    /**
     * Initialize the AudioFrameManager with a callback.
     * This method sets the callback that will be invoked when a sentence or session ends.
     * It logs the initialization message.
     * @param callback The callback to be invoked on sentence end.
     */
    @JvmStatic
    @Synchronized
    fun init(callback: ICallback) {
        LogUtils.d(TAG, "AudioFrameManager init")
        mCallback = callback
    }

    /**
     * Generate PTS by v2 spec.
     * Bit layout (MSB -> LSB):

     * When version = 2, pts bit layout:
     * [high 4 bits: version] | [16 bits: sessionId] | [16 bits: sentenceId] | [10 bits: chunkId] | [2 bits: isEnd] | [low 16 bits: basePts]
     *
     * End detection logic:
     * - Immediate switches:
     *   - sessionId changes: immediately report previous session end (isSessionEnd=true)
     *   - sentenceId changes (within the same session): immediately report previous sentence end (isSessionEnd=false)
     * - Silence timeout window (200ms): if no new frame arrives in time, report end for the last payload:
     *   - isEnd == 0 -> sentence end (isSessionEnd=false)
     *   - isEnd != 0 -> session end (isSessionEnd=true)
     */
    @Synchronized
    fun generatePtsV2(
        isSessionEnd: Boolean,
        frameSize: Int,
    ): Long {
        val version = 2
        val safeVersion = version and 0xF
        val sessionId = mV2SessionId16 and 0xFFFF
        val sentenceId = 1 // default 1
        val chunkId = mV2ChunkId10 and 0x3FF
        val isEndBits = if (isSessionEnd) 1 else 0
        val basePts = 0

        var pts = 0L
        pts = pts or ((safeVersion.toLong() and 0xFL) shl 60)
        pts = pts or ((sessionId.toLong() and 0xFFFFL) shl 44)
        pts = pts or ((sentenceId.toLong() and 0xFFFFL) shl 28)
        pts = pts or ((chunkId.toLong() and 0x3FFL) shl 18)
        pts = pts or ((isEndBits.toLong() and 0x3L) shl 16)
        pts = pts or (basePts.toLong() and 0xFFFFL)

        LogUtils.d(
            TAG,
            "generatePtsV2 pts:$pts ${String.format("0x%016X", pts)} isSessionEnd:$isSessionEnd " +
                    "sessionId:$sessionId sentenceId:$sentenceId chunkId:$chunkId"
        )

        // Update counters for next call
        if (isSessionEnd) {
            mV2SessionId16 = if (mV2SessionId16 >= 0xFFFF) 1 else mV2SessionId16 + 1
            mV2ChunkId10 = 1
        } else {
            mV2ChunkId10 = if (mV2ChunkId10 >= 0x3FF) 1 else mV2ChunkId10 + 1
        }

        return pts
    }

    /**
     * Generate PTS by v3 spec.
     * Bit layout (MSB -> LSB):
     * [3 bits: version] | [4 bits: sessionId] | [24 bits: chunkId] | [16 bits: duration(ms)] | [1 bit: isSessionEnd] | [16 bits: basePts]
     *
     * Rules:
     * - version defaults to 3
     * - sessionId is internal (not passed in). When isSessionEnd == true, it increments by 1 (wraps within 4 bits 0..15) to the NEXT session
     * - chunkId is internal 24-bit counter: starts at 1, increments by 1 each call; when isSessionEnd == true, it resets to 1 for the next session
     * - basePts defaults to 0
     * - duration(ms) is internally accumulated from the durations passed in each call, and only written when isSessionEnd == true (otherwise 0)
     */
    @Synchronized
    fun generatePtsV3(
        isSessionEnd: Boolean,
        durationMs: Int,
    ): Long {
        val version = 3
        val safeVersion = version and 0x7
        val sessionId = mSessionId4Bits and 0xF
        val chunkId = mChunkId24Bits and 0xFFFFFF
        val safeChunkId = chunkId and 0xFFFFFF
        mSessionDuration16Bits = (mSessionDuration16Bits + (durationMs and 0xFFFF)) and 0xFFFF
        val safeDuration = if (isSessionEnd) mSessionDuration16Bits else 0
        mSessionBasePts16Bits = (mSessionBasePts16Bits + (10 and 0xFFFF)) and 0xFFFF
        val safeBasePts = mSessionBasePts16Bits

        var pts = 0L
        pts = pts or ((safeVersion.toLong() and 0x7L) shl 61)
        pts = pts or ((sessionId.toLong() and 0xFL) shl 57)
        pts = pts or ((safeChunkId.toLong() and 0xFFFFFFL) shl 33)
        pts = pts or ((safeDuration.toLong() and 0xFFFFL) shl 17)
        pts = pts or ((((if (isSessionEnd) 1 else 0).toLong()) and 0x1L) shl 16)
        pts = pts or (safeBasePts.toLong() and 0xFFFFL)

        LogUtils.d(
            TAG,
            "generatePtsV3 pts:$pts ${
                String.format(
                    "0x%016X",
                    pts
                )
            } isSessionEnd:$isSessionEnd durationMs:$durationMs " +
                    "sessionId:$sessionId chunkId:$safeChunkId duration:$safeDuration basePts:$safeBasePts"
        )

        // Update internal counters for next call
        if (isSessionEnd) {
            // Session ends: advance session id (wrap to 1) and reset chunk id to 1 for next session
            mSessionId4Bits = if (mSessionId4Bits >= 0xF) 1 else mSessionId4Bits + 1
            mChunkId24Bits = 1
            mSessionDuration16Bits = 0
            mSessionBasePts16Bits = 0
        } else {
            // Continue current session: increment chunk id (wrap to 1)
            mChunkId24Bits = if (mChunkId24Bits >= 0xFFFFFF) 1 else mChunkId24Bits + 1
        }

        return pts
    }

    /**
     * Generate PTS by v4 spec.
     * Bit layout (MSB -> LSB):
     * [3 bits: version] | [18 bits: sessionId] | [10 bits: last_chunk_duration_ms] | [1 bit: isSessionEnd] | [32 bits: basePts]
     * Rules:
     * - version defaults to 1
     * - sessionId is 18-bit internal counter, always increments (wraps to 1 after 0x3FFFF)
     * - last_chunk_duration_ms: when isSessionEnd == true, set to durationMs (masked to 10 bits); otherwise 0
     * - basePts: 32-bit rolling timestamp accumulator (adds durationMs each call, wraps at 2^32)
     */
    @Synchronized
    fun generatePtsV4(
        isSessionEnd: Boolean,
        durationMs: Int,
    ): Long {
        val version = 1
        val safeVersion = version and 0x7
        val sessionId = mV4SessionId18 and 0x3FFFF
        val lastChunkDuration = if (isSessionEnd) (durationMs and 0x3FF) else 0
        val basePts32 = mV4BasePts32

        var pts = 0L
        pts = pts or ((safeVersion.toLong() and 0x7L) shl 61)
        pts = pts or ((sessionId.toLong() and 0x3FFFFL) shl 43)
        pts = pts or ((lastChunkDuration.toLong() and 0x3FFL) shl 33)
        pts = pts or ((((if (isSessionEnd) 1 else 0).toLong()) and 0x1L) shl 32)
        pts = pts or (basePts32 and 0xFFFF_FFFFL)

        LogUtils.d(
            TAG,
            "generatePts pts:$pts ${String.format("0x%016X", pts)} isSessionEnd:$isSessionEnd " +
                    "sessionId:$sessionId lastChunkDurationMs:$lastChunkDuration basePts32:$basePts32"
        )

        if (isSessionEnd) {
            // advance session id every call, wrap to 1
            mV4SessionId18 = if (mV4SessionId18 >= 0x3FFFF) 1 else mV4SessionId18 + 1
            mV4BasePts32 = 0L
        } else {
            mV4BasePts32 = (mV4BasePts32 + (durationMs.toLong() and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
        }

        return pts
    }

    /**
     * Process an audio frame.
     *
     * When version = 2, pts bit layout:
     * [high 4 bits: version] | [16 bits: sessionId] | [16 bits: sentenceId] | [10 bits: chunkId] | [2 bits: isEnd] | [low 16 bits: basePts]
     *
     * End detection logic:
     * - Immediate switches:
     *   - sessionId changes: immediately report previous session end (isSessionEnd=true)
     *   - sentenceId changes (within the same session): immediately report previous sentence end (isSessionEnd=false)
     * - Silence timeout window (200ms): if no new frame arrives in time, report end for the last payload:
     *   - isEnd == 0 -> sentence end (isSessionEnd=false)
     *   - isEnd != 0 -> session end (isSessionEnd=true)
     */
    fun processAudioFrame(data: ByteArray, pts: Long) {
        if (pts == 0L) {
            return
        }
        mAudioFrameFinishJob?.cancel()

        val version = ((pts ushr 60) and 0xFL).toInt()
        val sessionId = ((pts ushr 44) and 0xFFFFL).toInt()
        val sentenceId = ((pts ushr 28) and 0xFFFFL).toInt()
        val chunkId = ((pts ushr 18) and 0x3FFL).toInt()
        val isEndBits = ((pts ushr 16) and 0x3L).toInt()
        val basePts = (pts and 0xFFFFL).toInt()
        val isSessionEnd = isEndBits != 0

        val currentPayload = SentencePayload(sessionId, sentenceId, chunkId, isSessionEnd)

        LogUtils.d(
            TAG,
            "processAudioFrame version:${version} currentPayload:${currentPayload} chunkId:$chunkId basePts:$basePts"
        )

        val previousPayload = mLastPayload
        if (previousPayload == null) {
            LogUtils.d(TAG, "first payload: $currentPayload")
        } else if (previousPayload.sessionId != currentPayload.sessionId) {
            LogUtils.d(TAG, "payload changed: prev=$previousPayload -> curr=$currentPayload")
            callbackOnSentenceEnd(
                previousPayload.sessionId,
                previousPayload.sentenceId,
                previousPayload.chunkId,
                true
            )
            return
        } else if (previousPayload.sentenceId != currentPayload.sentenceId) {
            LogUtils.d(TAG, "sentenceId changed: prev=$previousPayload -> curr=$currentPayload")
            callbackOnSentenceEnd(
                previousPayload.sessionId,
                previousPayload.sentenceId,
                previousPayload.chunkId,
                false
            )
            return
        }

        mLastPayload = currentPayload

        mAudioFrameFinishJob = mSingleThreadScope.launch {
            delay(PLAYBACK_AUDIO_FRAME_TIMEOUT_MS)
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${PLAYBACK_AUDIO_FRAME_TIMEOUT_MS}ms"
            )
            val snap = mLastPayload?.let {
                SentencePayload(
                    it.sessionId,
                    it.sentenceId,
                    it.chunkId,
                    it.isSessionEnd
                )
            }
            if (snap != null) {
                callbackOnSentenceEnd(
                    snap.sessionId,
                    snap.sentenceId,
                    snap.chunkId,
                    snap.isSessionEnd
                )
            }
        }
    }

    private fun callbackOnSentenceEnd(
        sessionId: Int,
        sentenceId: Int,
        chunkId: Int,
        isSessionEnd: Boolean
    ) {
        mCallback?.onSentenceEnd(sessionId, sentenceId, chunkId, isSessionEnd)
        mLastPayload = null
    }

    /**
     * Release the AudioFrameManager.
     * This method is used to release the AudioFrameManager.
     * It will cancel the audio frame finish job and close the executor.
     * It will also clear the internal state.
     */
    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")
        mAudioFrameFinishJob?.cancel()
        mExecutor.close()
        mCallback = null
        mLastPayload = null
        mSessionId4Bits = 1
        mChunkId24Bits = 1
        mSessionDuration16Bits = 0
        mSessionBasePts16Bits = 0
        mV2SessionId16 = 1
        mV2ChunkId10 = 1
        mV4SessionId18 = 1
        mV4BasePts32 = 0L
    }
}