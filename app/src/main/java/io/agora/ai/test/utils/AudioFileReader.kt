package io.agora.ai.test.utils

import android.content.Context
import android.os.Process
import java.io.IOException
import java.io.InputStream


/**
 * The type Audio file reader.
 */
class AudioFileReader
/**
 * Instantiates a new Audio file reader.
 *
 * @param context  the context
 * @param fileName the file name
 * @param audioReadListener the listener
 */(
    private val context: Context,
    private val fileName: String,
    private val sampleRate: Int,
    private val numOfChannels: Int,
    private val interval: Int,
    private val loopPlayback: Boolean,
    private val audioReadListener: OnAudioReadListener?
) {

    companion object {
        /**
         * The constant BITS_PER_SAMPLE.
         */
        private const val BITS_PER_SAMPLE: Int = 16
    }

    @kotlin.jvm.Volatile
    private var pushing = false
    private var thread: InnerThread? = null
    private var inputStream: InputStream? = null

    private fun getBufferSize(): Int {
        return (((sampleRate / 1000).toLong() * interval) * (BITS_PER_SAMPLE / 8 * numOfChannels)).toInt()
    }

    private fun getOneMsBufferSize(): Int {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val samplesPerMs = sampleRate / 1000
        return samplesPerMs * bytesPerSample * numOfChannels
    }

    fun getSampleRate(): Int {
        return sampleRate
    }

    fun getNumOfChannels(): Int {
        return numOfChannels
    }

    fun getBytePerSample(): Int {
        return BITS_PER_SAMPLE / 8
    }

    /**
     * Start.
     */
    fun start() {
        if (thread == null) {
            thread = InnerThread()
            thread?.start()
        }
    }

    /**
     * Stop.
     */
    fun stop() {
        pushing = false
        if (thread != null) {
            try {
                thread?.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } finally {
                thread = null
            }
        }
    }

    /**
     * The interface On audio read listener.
     */
    interface OnAudioReadListener {
        /**
         * On audio read.
         *
         * @param buffer    the buffer
         * @param timestamp the timestamp
         */
        fun onAudioRead(buffer: ByteArray?, timestamp: Long, isLastFrame: Boolean)
    }

    private inner class InnerThread : Thread() {
        override fun run() {
            super.run()
            try {
                inputStream = context.assets.open(fileName)
            } catch (e: IOException) {
                e.printStackTrace()
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            pushing = true

            val startTime = System.currentTimeMillis()
            var sentAudioFrames = 0
            while (pushing) {
                val frame = readNextFrame() ?: break
                audioReadListener?.onAudioRead(
                    frame.first,
                    System.currentTimeMillis(),
                    frame.second
                )
                ++sentAudioFrames
                val nextFrameStartTime = sentAudioFrames * interval + startTime
                val now = System.currentTimeMillis()

                if (nextFrameStartTime > now) {
                    val sleepDuration = nextFrameStartTime - now
                    try {
                        sleep(sleepDuration)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }

            if (inputStream != null) {
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    inputStream = null
                }
            }
        }

        fun readNextFrame(): Pair<ByteArray, Boolean>? {
            var attemptReopen = false
            do {
                attemptReopen = false
                val fullFrameSize = getBufferSize()
                val oneMsSize = getOneMsBufferSize()

                if (!loopPlayback) {
                    val remaining = try {
                        inputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    if (remaining <= 0) {
                        return null
                    }

                    if (remaining > fullFrameSize) {
                        val buffer = ByteArray(fullFrameSize)
                        var totalRead = 0
                        try {
                            while (totalRead < fullFrameSize) {
                                val read =
                                    inputStream?.read(buffer, totalRead, fullFrameSize - totalRead)
                                        ?: -1
                                if (read <= 0) break
                                totalRead += read
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        if (totalRead <= 0) return null
                        return Pair(
                            if (totalRead == fullFrameSize) buffer else buffer.copyOf(
                                totalRead
                            ), false
                        )
                    }

                    val lastSize = (remaining / oneMsSize) * oneMsSize
                    if (lastSize <= 0) {
                        // tail smaller than 1ms, discard
                        return null
                    }
                    val buffer = ByteArray(lastSize)
                    var totalRead = 0
                    try {
                        while (totalRead < lastSize) {
                            val read =
                                inputStream?.read(buffer, totalRead, lastSize - totalRead) ?: -1
                            if (read <= 0) break
                            totalRead += read
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    if (totalRead <= 0) return null
                    return Pair(
                        if (totalRead == lastSize) buffer else buffer.copyOf(totalRead),
                        true
                    )
                } else {
                    // loop playback: return normal frames; at loop end, drop remainder and only emit last frame sized to multiple of 1ms
                    val remaining = try {
                        inputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    if (remaining <= 0) {
                        try {
                            inputStream?.close()
                        } catch (_: IOException) {
                        }
                        try {
                            inputStream = context.assets.open(fileName)
                            attemptReopen = true
                            continue
                        } catch (e: IOException) {
                            e.printStackTrace()
                            return null
                        }
                    }

                    if (remaining > fullFrameSize) {
                        val buffer = ByteArray(fullFrameSize)
                        var totalRead = 0
                        try {
                            while (totalRead < fullFrameSize) {
                                val read =
                                    inputStream?.read(buffer, totalRead, fullFrameSize - totalRead)
                                        ?: -1
                                if (read <= 0) break
                                totalRead += read
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        if (totalRead <= 0) continue
                        return Pair(
                            if (totalRead == fullFrameSize) buffer else buffer.copyOf(
                                totalRead
                            ), false
                        )
                    }

                    val lastSize = (remaining / oneMsSize) * oneMsSize
                    if (lastSize > 0) {
                        // discard remainder before last multiple-of-1ms frame, if any
                        val preLast = remaining - lastSize
                        if (preLast > 0) {
                            val drainBuf = ByteArray(preLast)
                            var drained = 0
                            try {
                                while (drained < preLast) {
                                    val read =
                                        inputStream?.read(drainBuf, drained, preLast - drained)
                                            ?: -1
                                    if (read <= 0) break
                                    drained += read
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        val buffer = ByteArray(lastSize)
                        var totalRead = 0
                        try {
                            while (totalRead < lastSize) {
                                val read =
                                    inputStream?.read(buffer, totalRead, lastSize - totalRead) ?: -1
                                if (read <= 0) break
                                totalRead += read
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        if (totalRead <= 0) continue
                        return Pair(
                            if (totalRead == lastSize) buffer else buffer.copyOf(totalRead),
                            true
                        )
                    }

                    // remaining < oneMsSize: drain and reopen for next loop
                    val drainBuf = ByteArray(remaining)
                    var drained = 0
                    try {
                        while (drained < remaining) {
                            val read =
                                inputStream?.read(drainBuf, drained, remaining - drained) ?: -1
                            if (read <= 0) break
                            drained += read
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        inputStream?.close()
                    } catch (_: IOException) {
                    }
                    try {
                        inputStream = context.assets.open(fileName)
                        attemptReopen = true
                        continue
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return null
                    }
                }
            } while (attemptReopen)
            return null
        }
    }


}
