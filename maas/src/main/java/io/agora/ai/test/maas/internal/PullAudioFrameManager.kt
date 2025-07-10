package io.agora.ai.test.maas.internal

import android.content.Context
import android.util.Log
import io.agora.ai.test.maas.MaaSConstants
import io.agora.rtc2.RtcEngine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PullAudioFrameManager {
    private const val TAG = MaaSConstants.TAG

    private val started = AtomicBoolean(false)

    private var startTime: Long = 0L
    /**
     * Tracks the total number of audio frames consumed so far.
     * This uses the `Long` type to prevent overflow issues during long-running sessions. A `Long`
     * can store a number so large that, even at a 10ms interval, it would take billions of
     * years to overflow, making it completely safe for any practical application lifetime.
     */
    private var consumeAudioFrames: Long = 0L

    private var fileOutputStream: FileOutputStream? = null
    private var fileWriterExecutor: ExecutorService? = null

    // ByteArray pool related
    /**
     * A memory pool for reusing ByteArray objects to reduce Garbage Collection (GC) pressure.
     *
     * The data structure is `Map<Int, MutableList<ByteArray>>`. Here's why:
     * - The Key (`Int`): Represents the number of audio frames a ByteArray can hold (e.g., 1, 2, 5 frames).
     * - The Value (`MutableList<ByteArray>`): A list of actual ByteArray objects of that specific size.
     *
     * We use a LIST of byte arrays (and not just a single one) because the "chasing" mechanism might
     * require several buffers of the same size in quick succession, faster than they can be recycled.
     * The list acts as a buffer of buffers, maximizing the chance of a cache hit and preventing
     * the creation of new objects even under high load.
     */
    private val byteArrayPool: MutableMap<Int, MutableList<ByteArray>> = mutableMapOf()
    private val poolLock = Any()
    private var poolOnePackageAudioSize: Int = 0
    private const val MIN_POOL_FRAMES = 1
    private const val MAX_POOL_FRAMES = 5

    /**
     * Pull audio frame from maas engine and push to maas engine, optionally saving to a file.
     * @param context: Application context to access cache directory.
     * @param rtcEngine: rtc engine
     * @param interval: millisecond
     * @param sampleRate: sample rate
     * @param channelCount: channel count
     * @param saveToFile: Flag to enable saving audio data to a file. Defaults to false.
     */
    fun start(
        context: Context,
        rtcEngine: RtcEngine,
        interval: Int,
        sampleRate: Int,
        channelCount: Int,
        saveToFile: Boolean = false
    ) {
        Log.d(TAG, "Starting PullAudioFrameManager.")
        if (started.compareAndSet(false, true)) {
            startTime = System.currentTimeMillis()
            consumeAudioFrames = 0L
            fileOutputStream = null
            fileWriterExecutor = null

            // Calculate the size in bytes for a single audio frame based on RTC parameters.
            val onePackageAudioSize = sampleRate / 1000 * 2 * channelCount * interval
            poolOnePackageAudioSize = onePackageAudioSize
            if (onePackageAudioSize <= 0) {
                Log.e(TAG, "Invalid audio parameters resulting in zero package size.")
                started.set(false)
                return
            }

            val maxFramesPerCycle = Int.MAX_VALUE / onePackageAudioSize

            if (saveToFile) {
                try {
                    val cacheDir = context.externalCacheDir ?: throw SecurityException(
                        "Failed to access cache directory"
                    )
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val audioFile = File(cacheDir, "pull_audio_$startTime.pcm")
                    fileOutputStream = FileOutputStream(audioFile)
                    Log.i(TAG, "Start saving audio to: ${audioFile.absolutePath}")
                    fileWriterExecutor = Executors.newSingleThreadExecutor()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to open file for saving audio: $startTime.pcm", e)
                    fileOutputStream = null
                } catch (e: SecurityException) {
                    Log.e(
                        TAG,
                        "SecurityException: Failed to access cache directory or create file: $startTime.pcm",
                        e
                    )
                    fileOutputStream = null
                }
            }

            // Main loop for continuously pulling audio frames.
            while (started.get()) {
                val currentTime = System.currentTimeMillis()

                // --- Start of the "Chasing" Mechanism ---
                // Calculate the total number of frames that *should* have been consumed since the start time.
                // This provides the target timeline.
                val totalRequiredFrames = (currentTime - startTime) / interval

                // Calculate how many frames we are behind the target timeline.
                // This is the number of frames we need to pull in this cycle to "catch up".
                // If this number is very large (e.g., after the app resumes from background), it represents the accumulated "debt".
                val framesToPullThisCycleLong = totalRequiredFrames - consumeAudioFrames
                // --- End of the "Chasing" Mechanism ---


                if (framesToPullThisCycleLong > 0) {
                    // Here, `coerceAtMost` acts as a safety fuse. It ensures that we don't try to pull a massive
                    // number of frames in a single cycle, which could lead to an OutOfMemoryError.
                    // If `framesToPullThisCycleLong` exceeds the safe limit (`maxFramesPerCycle`), it's "capped"
                    // at that limit for this cycle. The remaining "debt" of frames will be handled in subsequent cycles.
                    val framesToPullThisCycle =
                        framesToPullThisCycleLong.coerceAtMost(maxFramesPerCycle.toLong()).toInt()


                    if (framesToPullThisCycle > 0) {
                        val pullSize = onePackageAudioSize * framesToPullThisCycle
                        if (pullSize < 0) {
                            Log.e(
                                TAG,
                                "Calculated pullSize $pullSize exceeds Int.MAX_VALUE or became negative."
                            )
                            break
                        }
                        // Get a suitably sized ByteArray from our memory pool to avoid new allocations.
                        val pullDataArray = getByteArrayFromPool(pullSize)
                        val ret =
                            rtcEngine.pullPlaybackAudioFrame(pullDataArray, pullSize)

                        if (ret == 0) {
                            // Successfully pulled frames, update our consumed frames counter.
                            consumeAudioFrames += framesToPullThisCycle

                            // Asynchronously write the audio data to a file if enabled.
                            fileOutputStream?.let { stream ->
                                if (fileWriterExecutor?.isShutdown != true) {
                                    fileWriterExecutor?.submit {
                                        try {
                                            stream.write(pullDataArray)
                                            // After writing, recycle the ByteArray back to the pool.
                                            recycleByteArrayToPool(pullDataArray)
                                        } catch (e: IOException) {
                                            Log.e(
                                                TAG,
                                                "Failed to write audio data to file asynchronously",
                                                e
                                            )
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Executor is shutdown, skipping write task.")
                                }
                            }

                        } else {
                            Log.e(TAG, "pullPlaybackAudioFrame failed, ret: $ret")
                            break
                        }
                    }
                }

                try {
                    // Wait for the next pull cycle.
                    Thread.sleep(interval.toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Pull audio thread interrupted.")
                    break
                }
            }

            closeStream()
            clearByteArrayPool()

        } else {
            Log.w(TAG, "PullAudioFrameManager already started.")
        }
    }

    fun startNormal(
        context: Context,
        rtcEngine: RtcEngine,
        interval: Int,
        sampleRate: Int,
        channelCount: Int,
        saveToFile: Boolean = false
    ) {
        Log.d(TAG, "startNormal PullAudioFrameManager.")
        if (started.compareAndSet(false, true)) {
            startTime = System.currentTimeMillis()
            val onePackageAudioSize = sampleRate / 1000 * 2 * channelCount * interval
            val dataByteArray = ByteArray(onePackageAudioSize)

            if (saveToFile) {
                try {
                    val cacheDir = context.externalCacheDir ?: throw SecurityException(
                        "Failed to access cache directory"
                    )
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val audioFile = File(cacheDir, "pull_audio_$startTime.pcm")
                    fileOutputStream = FileOutputStream(audioFile)
                    Log.i(TAG, "Start saving audio to: ${audioFile.absolutePath}")
                    fileWriterExecutor = Executors.newSingleThreadExecutor()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to open file for saving audio: $startTime.pcm", e)
                    fileOutputStream = null
                } catch (e: SecurityException) {
                    Log.e(
                        TAG,
                        "SecurityException: Failed to access cache directory or create file: $startTime.pcm",
                        e
                    )
                    fileOutputStream = null
                }
            }
            while (started.get()) {
                val ret = rtcEngine.pullPlaybackAudioFrame(dataByteArray, onePackageAudioSize)
                if (ret == 0) {
                    fileOutputStream?.let { stream ->
                        if (fileWriterExecutor?.isShutdown != true) {
                            fileWriterExecutor?.submit {
                                try {
                                    stream.write(dataByteArray)
                                    recycleByteArrayToPool(dataByteArray)
                                } catch (e: IOException) {
                                    Log.e(
                                        TAG,
                                        "Failed to write audio data to file asynchronously",
                                        e
                                    )
                                }
                            }
                        } else {
                            Log.w(TAG, "Executor is shutdown, skipping write task.")
                        }
                    }
                } else {
                    Log.e(TAG, "pullPlaybackAudioFrame failed, ret: $ret")
                    break
                }

                try {
                    Thread.sleep(interval.toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Pull audio thread interrupted.")
                    break
                }
            }
        }

    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping PullAudioFrameManager.")
            closeStream()
            clearByteArrayPool()
        } else {
            Log.d(TAG, "PullAudioFrameManager already stopped or never started.")
        }
    }

    private fun closeStream() {
        // 1. Shutdown Executor gracefully
        fileWriterExecutor?.let { executor ->
            executor.shutdown() // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to complete
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.w(TAG, "File writer executor did not terminate in 5 seconds.")
                    // Optionally force shutdown: executor.shutdownNow()
                    // Forcing shutdown might interrupt ongoing writes. Consider data loss implications.
                }
            } catch (ie: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for file writer tasks completion.")
                // (Re-)Cancel if current thread also interrupted
                executor.shutdownNow()
                // Preserve interrupt status
                Thread.currentThread().interrupt()
            }
            fileWriterExecutor = null // Reset executor reference
        }

        // 2. Close the FileOutputStream
        fileOutputStream?.let { stream ->
            try {
                stream.flush()
                stream.close()
                Log.i(TAG, "Closed audio save file (pull_audio_$startTime.pcm).")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close audio save file ($startTime.pcm)", e)
            } finally {
                fileOutputStream = null // Set to null after attempting close
            }
        }
    }

    /**
     * Retrieves a ByteArray from the pool, analogous to "checking out a tool from a toolbox".
     * If no suitable array is found in the pool for the requested size, it creates a new one as a fallback.
     * This helps to avoid frequent memory allocations and GC pauses.
     * @param size The required size of the ByteArray.
     * @return A ByteArray of the specified size, preferably reused from the pool.
     */
    private fun getByteArrayFromPool(size: Int): ByteArray {
        synchronized(poolLock) {
            val frameCount = size / poolOnePackageAudioSize
            if (frameCount in MIN_POOL_FRAMES..MAX_POOL_FRAMES) {
                byteArrayPool[frameCount]?.let { pool ->
                    if (pool.isNotEmpty()) {
                        // Found a reusable array, remove it from the pool and return it.
                        return pool.removeAt(pool.size - 1)
                    }
                }
            }
        }
        // If no array is available in the pool, create a new one.
        return ByteArray(size)
    }

    /**
     * Returns a used ByteArray back to the pool for future reuse, analogous to "returning a tool to the toolbox".
     * The array is added to the list corresponding to its size, ready to be checked out again.
     * @param array The ByteArray to be recycled.
     */
    private fun recycleByteArrayToPool(array: ByteArray) {
        synchronized(poolLock) {
            val frameCount = array.size / poolOnePackageAudioSize
            if (frameCount in MIN_POOL_FRAMES..MAX_POOL_FRAMES) {
                val pool = byteArrayPool.getOrPut(frameCount) { mutableListOf() }
                pool.add(array)
            }
        }
    }

    /**
     * Clears all cached ByteArrays from the pool. Called when the manager stops.
     */
    private fun clearByteArrayPool() {
        synchronized(poolLock) {
            byteArrayPool.clear()
        }
    }
}