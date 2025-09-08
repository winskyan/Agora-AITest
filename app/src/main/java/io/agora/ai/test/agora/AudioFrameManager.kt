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
    private const val PLAYBACK_AUDIO_FRAME_MAX_TIMEOUT_MS: Long = 500 // ms
    private const val PLAYBACK_AUDIO_FRAME_MIN_TIMEOUT_MS: Long = 200 // ms

    private var mAudioFrameFinishJob: Job? = null
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mCallback: ICallback? = null
    private var mLastPayload: SentencePayload? = null

    private var mSessionId18: Int = 1
    private var mBasePts32: Long = 0L

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
     * Generate PTS.
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
    fun generatePts(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Long {
        val version = 1
        val safeVersion = version and 0x7
        val sessionId = mSessionId18 and 0x3FFFF
        val durationMs = data.size / ((sampleRate * channels * 2) / 1000) // 16-bit PCM
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
            "generatePts pts:$pts ${String.format("0x%016X", pts)} isSessionEnd:$isSessionEnd " +
                    "sessionId:$sessionId durationMs:${durationMs} lastChunkDurationMs:$lastChunkDuration basePts32:$basePts32"
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
     * Process an audio frame.
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
     *
     * @param data raw PCM audio bytes of the current frame
     * @param pts 64-bit PTS carried with the frame. Only version = 2 is processed currently; other versions are ignored
     */
    @Synchronized
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
            "processAudioFrame version:${version} currentPayload:${currentPayload} basePts:$basePts"
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
            val delayTime =
                if (mLastPayload?.isSessionEnd == true) PLAYBACK_AUDIO_FRAME_MIN_TIMEOUT_MS else PLAYBACK_AUDIO_FRAME_MAX_TIMEOUT_MS
            delay(delayTime)
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${delayTime}ms"
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
                    true
                )
            }
        }
    }

    fun processAudioFrameForLab(data: ByteArray, pts: Long) {
        mAudioFrameFinishJob?.cancel()
        LogUtils.d(
            TAG,
            "processAudioFrameForLab pts:$pts"
        )

        mAudioFrameFinishJob = mSingleThreadScope.launch {
            val delayTime = PLAYBACK_AUDIO_FRAME_MAX_TIMEOUT_MS
            delay(delayTime)
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${delayTime}ms"
            )
            callbackOnSentenceEnd(
                1,
                1,
                1,
                true
            )
        }
    }

    private fun callbackOnSentenceEnd(
        sessionId: Int,
        sentenceId: Int,
        chunkId: Int,
        isSessionEnd: Boolean
    ) {
        LogUtils.d(
            TAG,
            "callbackOnSentenceEnd sessionId:$sessionId sentenceId:$sentenceId chunkId:$chunkId isSessionEnd:$isSessionEnd"
        )
        mCallback?.onSentenceEnd(sessionId, sentenceId, chunkId, isSessionEnd)
        mLastPayload = null
    }

    /**
     * Release the AudioFrameManager.
     * Cancels internal jobs, shuts down the executor and clears internal states/counters.
     */
    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")
        mAudioFrameFinishJob?.cancel()
        mExecutor.close()
        mCallback = null
        mLastPayload = null
        mSessionId18 = 1
        mBasePts32 = 0L
    }
}