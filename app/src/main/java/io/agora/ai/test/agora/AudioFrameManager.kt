package io.agora.ai.test.agora

import com.google.gson.Gson
import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object AudioFrameManager {
    private const val TAG = "${ExamplesConstants.TAG}-AudioFrameManager"
    private const val PLAYBACK_AUDIO_FRAME_TIMEOUT_MS: Long = 200 // ms
    private const val PLAYBACK_PTS_CHANGE_THRESHOLD_MS: Long = 1000 // ms
    private const val SENTENCE_PTS_THRESHOLD_MS: Long = 50000 // ms

    private var mAudioFrameFinishJob: Job? = null
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mCallback: ICallback? = null
    private var mSentencesMap: MutableMap<Long, SentencePayload> = ConcurrentHashMap()
    private var mPrePts: Long = 0L

    private val mGson = Gson()


    interface ICallback {
        fun onSentenceEnd(sentenceId: String, isRoundEnd: Boolean) {

        }
    }

    data class SentencePayload(
        val sentenceId: String,
        val basePts: Long,
        val sentenceDataLength: Int,
        var sentenceDataCurrentLength: Int,
        val isRoundEnd: Boolean
    )

    /**
     * Initialize the AudioFrameManager with a callback.
     * This method sets the callback that will be invoked when a sentence ends.
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
     * Update sentence with basePts, sentenceId, sentenceDataLength, and isRoundEnd.
     * If the basePts already exists in the map, it will log a debug message and return without updating.
     * If the sentence is successfully updated, it will be added to the mSentencesMap.
     * If the sentenceId is empty, it will log an error message and return.
     * @param sentenceId The unique identifier for the sentence.
     * @param basePts The base presentation timestamp for the sentence.
     * @param sentenceDataLength The length of the sentence data.
     * @param isRoundEnd Indicates whether this sentence marks the end of a round.
     */
    @JvmStatic
    @Synchronized
    fun updateSentence(
        sentenceId: String,
        basePts: Long,
        sentenceDataLength: Int,
        isRoundEnd: Boolean
    ) {
        if (sentenceId.isBlank()) {
            LogUtils.e(TAG, "updateSentence error: sentenceId is blank")
            return
        }

        if (mSentencesMap.containsKey(basePts)) {
            LogUtils.d(TAG, "updateSentence basePts $basePts already exists")
            return
        }
        LogUtils.d(
            TAG, "updateSentence: sentenceId: $sentenceId, basePts: $basePts, " +
                    "sentenceDataLength: $sentenceDataLength, isRoundEnd: $isRoundEnd"
        )
        mSentencesMap[basePts] =
            SentencePayload(sentenceId, basePts, sentenceDataLength, 0, isRoundEnd)
    }

    /**
     * Update sentence with JSON payload.
     * This method expects a JSON string that contains the sentenceId, basePts,
     * sentenceDataLength, and isRoundEnd fields.
     * Example JSON: {"sentenceId":"123", "basePts":50000, "sentenceDataLength":1000, "isRoundEnd":true}
     * If the JSON is empty or malformed, it will log an error.
     * If the sentence with the given basePts already exists, it will log a debug message and return without updating.
     * If the sentence is successfully updated, it will be added to the mSentencesMap.
     * If an exception occurs during parsing, it will log the error message.
     * @param sentencePayloadJson The JSON string containing the sentence payload.
     */
    fun updateSentenceWithJson(sentencePayloadJson: String) {
        LogUtils.d(TAG, "updateSentenceWithJson: $sentencePayloadJson")
        if (sentencePayloadJson.isEmpty()) {
            LogUtils.e(TAG, "updateSentenceWithJson error: sentencePayloadJson is empty")
            return
        }
        try {
            val p = mGson.fromJson(sentencePayloadJson, SentencePayload::class.java)
            updateSentence(p.sentenceId, p.basePts, p.sentenceDataLength, p.isRoundEnd)
        } catch (e: Exception) {
            LogUtils.e(TAG, "updateSentenceWithJson error: ${e.message}")
        }
    }

    /**
     * Process an audio frame.
     * This method is used to process an audio frame.
     * It will check if the sentence has ended and if so, it will call the callback.
     * It will also check if the sentence has changed and if so, it will call the callback.
     * @param data The audio frame data.
     * @param pts The presentation timestamp of the audio frame.
     */
    fun processAudioFrame(data: ByteArray, pts: Long) {
        mAudioFrameFinishJob?.cancel()
        var previousSentence: SentencePayload? = null
        if (mPrePts != 0L && pts != 0L && pts > mPrePts) {
            previousSentence = resolveSentence(mPrePts)
            if (previousSentence == null) {
                LogUtils.d(
                    TAG,
                    "processAudioFrame: previous sentencePayload is null, ignore"
                )
            } else {
                if (pts - mPrePts > PLAYBACK_PTS_CHANGE_THRESHOLD_MS) {
                    callbackSentenceEnd(previousSentence)
                    mPrePts = pts
                    return
                } else {
                    previousSentence.sentenceDataCurrentLength += data.size
                    // if (previousSentence.sentenceDataCurrentLength >= previousSentence.sentenceDataLength) {
                    //     callbackSentenceEnd(previousSentence)
                    //     mPrePts = pts
                    //     return
                    // }
                }
            }
        }
        mPrePts = pts
        mAudioFrameFinishJob = mSingleThreadScope.launch {
            delay(PLAYBACK_AUDIO_FRAME_TIMEOUT_MS.toLong())
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${PLAYBACK_AUDIO_FRAME_TIMEOUT_MS}ms"
            )
            callbackSentenceEnd(previousSentence)
        }
    }

    private fun resolveSentence(pts: Long): SentencePayload? {
        val basePts = (pts / SENTENCE_PTS_THRESHOLD_MS) * SENTENCE_PTS_THRESHOLD_MS
        return mSentencesMap[basePts]
    }

    private fun callbackSentenceEnd(sentencePayload: SentencePayload?) {
        LogUtils.d(TAG, "callbackSentenceEnd: sentencePayload: $sentencePayload")
        if (sentencePayload == null) {
            LogUtils.e(TAG, "callbackSentenceEnd error: sentencePayload is null")
            return
        }
        mCallback?.onSentenceEnd(
            sentencePayload.sentenceId,
            sentencePayload.isRoundEnd
        )
        mSentencesMap.remove(sentencePayload.basePts)
    }

    private fun clearSentences() {
        mSentencesMap.clear()
        mPrePts = 0L
    }

    /**
     * Release the AudioFrameManager.
     * This method is used to release the AudioFrameManager.
     * It will cancel the audio frame finish job and close the executor.
     * It will also clear the sentences map and set the pre pts to 0.
     */
    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")
        mAudioFrameFinishJob?.cancel()
        mExecutor.close()
        mCallback = null
        clearSentences()
    }
}