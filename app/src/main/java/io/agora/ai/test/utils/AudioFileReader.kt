package io.agora.ai.test.utils

import android.content.Context
import android.os.Process
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.Volatile

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
    private val audioReadListener: OnAudioReadListener?
) {

    companion object {
        /**
         * The constant BITS_PER_SAMPLE.
         */
        private const val BITS_PER_SAMPLE: Int = 16
    }

    @Volatile
    private var pushing = false
    private var thread: InnerThread? = null
    private var inputStream: InputStream? = null

    private fun getBufferSize(): Int {
        return (((sampleRate / 1000).toLong() * interval) * (BITS_PER_SAMPLE / 8 * numOfChannels)).toInt()
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
        fun onAudioRead(buffer: ByteArray?, timestamp: Long)
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
                audioReadListener?.onAudioRead(
                    readBuffer(),
                    System.currentTimeMillis()
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

        fun readBuffer(): ByteArray {
            val byteSize = getBufferSize()
            val buffer = ByteArray(byteSize)
            try {
                if (inputStream?.read(buffer)!! < 0) {
                    inputStream?.reset()
                    return readBuffer()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return buffer
        }
    }


}
