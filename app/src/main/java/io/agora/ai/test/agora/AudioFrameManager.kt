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


    interface ICallback {
        fun onSentenceEnd(sessionId: Int, sentenceId: Int, isSessionEnd: Boolean) {

        }
    }

    data class SentencePayload(
        val sessionId: Int,
        val sentenceId: Int,
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
     * pts bit layout: [high 16 bits: sessionId] | [middle 14 bits: sentenceId] | [2 bits: isEnd] | [low 32 bits: basePts]
     * End detection rule:
     * - If isEnd == 0: no frame for 200ms -> sentence ended (isSessionEnd = false)
     * - If isEnd == 1: no frame for 200ms -> session ended (isSessionEnd = true)
     */
    fun processAudioFrame(data: ByteArray, pts: Long) {
        if (pts == 0L) {
            return
        }
        mAudioFrameFinishJob?.cancel()

        val sessionId = ((pts ushr 48) and 0xFFFFL).toInt()
        val sentenceId = ((pts ushr 34) and 0x3FFFL).toInt()
        val isEndBits = ((pts ushr 32) and 0x3L).toInt()
        val isSessionEnd = isEndBits != 0

        val currentPayload = SentencePayload(sessionId, sentenceId, isSessionEnd)

        LogUtils.d(
            TAG,
            "processAudioFrame currentPayload:${currentPayload}"
        )

        val previousPayload = mLastPayload
        if (previousPayload == null) {
            LogUtils.d(TAG, "first payload: $currentPayload")
        } else if (previousPayload.sessionId != currentPayload.sessionId) {
            LogUtils.d(TAG, "payload changed: prev=$previousPayload -> curr=$currentPayload")
            callbackOnSentenceEnd(
                previousPayload.sessionId,
                previousPayload.sentenceId,
                true
            )
            return
        } else if (previousPayload.sentenceId != currentPayload.sentenceId) {
            LogUtils.d(TAG, "sentenceId changed: prev=$previousPayload -> curr=$currentPayload")
            callbackOnSentenceEnd(previousPayload.sessionId, previousPayload.sentenceId, false)
            return
        }

        mLastPayload = currentPayload

        mAudioFrameFinishJob = mSingleThreadScope.launch {
            delay(PLAYBACK_AUDIO_FRAME_TIMEOUT_MS)
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${PLAYBACK_AUDIO_FRAME_TIMEOUT_MS}ms"
            )
            // timeout means no new frame arrived within window; decide end type by last payload's isSessionEnd
            if (mLastPayload != null) {
                callbackOnSentenceEnd(
                    mLastPayload!!.sessionId,
                    mLastPayload!!.sentenceId,
                    mLastPayload!!.isSessionEnd
                )
            }
        }
    }

    private fun callbackOnSentenceEnd(sessionId: Int, sentenceId: Int, isSessionEnd: Boolean) {
        mCallback?.onSentenceEnd(sessionId, sentenceId, isSessionEnd)
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