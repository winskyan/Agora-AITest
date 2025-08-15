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
    }
}