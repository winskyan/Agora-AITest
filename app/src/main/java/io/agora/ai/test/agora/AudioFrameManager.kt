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
    private var mSessionId4Bits: Int = 0
    private var mChunkId24Bits: Int = 0
    private var mSessionDuration16Bits: Int = 0


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

    // No pre-registration is required anymore. Sentence/session end will be inferred from pts fields.

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
        mSessionId4Bits = 0
        mChunkId24Bits = 0
        mSessionDuration16Bits = 0
    }

    /**
     * Generate PTS by v3 spec.
     * Bit layout (MSB -> LSB):
     * [3 bits: version] | [4 bits: sessionId] | [24 bits: chunkId] | [16 bits: sentence_duration] | [1 bit: isSessionEnd] | [16 bits: basePts]
     *
     * Rules:
     * - version defaults to 3
     * - sessionId is internal (not passed in). When isSessionEnd == true, it increments by 1 (wraps within 4 bits 0..15) for the NEXT session
     * - chunkId is internal 24-bit counter: starts at 0, increments by 1 each call; when isSessionEnd == true, it resets to 0 for the next session
     * - basePts defaults to 0
     * - sentence_duration is internally computed as the sum of frame sizes in the current session, and only written when isSessionEnd == true
     */
    @Synchronized
    fun generatePtsV3(
        isSessionEnd: Boolean,
        frameSize: Int,
    ): Long {
        val version = 3
        val safeVersion = version and 0x7
        val sessionId = mSessionId4Bits and 0xF
        val chunkId = mChunkId24Bits and 0xFFFFFF
        val safeChunkId = chunkId and 0xFFFFFF
        mSessionDuration16Bits = (mSessionDuration16Bits + (frameSize and 0xFFFF)) and 0xFFFF
        val safeSentenceDuration = if (isSessionEnd) mSessionDuration16Bits else 0
        val safeBasePts = 0

        var pts = 0L
        pts = pts or ((safeVersion.toLong() and 0x7L) shl 61)
        pts = pts or ((sessionId.toLong() and 0xFL) shl 57)
        pts = pts or ((safeChunkId.toLong() and 0xFFFFFFL) shl 33)
        pts = pts or ((safeSentenceDuration.toLong() and 0xFFFFL) shl 17)
        pts = pts or ((((if (isSessionEnd) 1 else 0).toLong()) and 0x1L) shl 16)
        pts = pts or (safeBasePts.toLong() and 0xFFFFL)

        LogUtils.d(
            TAG,
            "generatePtsV3 pts:$pts isSessionEnd:$isSessionEnd frameSize:$frameSize " +
                    "sessionId:$sessionId chunkId:$safeChunkId sentenceDuration:$safeSentenceDuration"
        )

        // Update internal counters for next call
        if (isSessionEnd) {
            // Session ends: advance session id and reset chunk id for next session
            mSessionId4Bits = (mSessionId4Bits + 1) and 0xF
            mChunkId24Bits = 0
            mSessionDuration16Bits = 0
        } else {
            // Continue current session: increment chunk id
            mChunkId24Bits = (mChunkId24Bits + 1) and 0xFFFFFF
        }

        return pts
    }
}