package io.agora.ai.rtm.test.base

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class TestManagerBase(val context: Context) {
    companion object {
        var TAG = Constants.TAG + "-" + this::class.java.simpleName
    }

    // File output
    protected open var historyFileName = ""
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var bufferedWriter: BufferedWriter? = null

    protected open var testStartTime = 0L
    protected open var testStarted = false


    // Timeout handler
    protected open val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize file writer
     */
    fun initWriter() {
        try {
            closeWriter()
            val cacheDir = context.externalCacheDir
            val logFile = File(cacheDir, historyFileName)
            bufferedWriter = BufferedWriter(FileWriter(logFile, true))
        } catch (e: IOException) {
            Log.e(TAG, "Error creating BufferedWriter", e)
        }
    }

    /**
     * Write message to file
     */
    fun writeMessageToFile(message: String, withTimestamp: Boolean = true) {
        logExecutor.execute {
            try {
                if (withTimestamp) {
                    // Add timestamp with yyyy-MM-dd HH:mm:ss.SSS format
                    val timestamp = Constants.DATE_FORMAT.format(System.currentTimeMillis())
                    bufferedWriter?.append("$timestamp: $message")?.append("\n")
                } else {
                    bufferedWriter?.append(message)?.append("\n")
                }
                bufferedWriter?.flush()
                Log.d(TAG, message)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing message to file", e)
            }
        }
    }

    /**
     * Close file writer
     */
    fun closeWriter() {
        try {
            bufferedWriter?.close()
            bufferedWriter = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing BufferedWriter", e)
        }
    }

    fun formatMessage(message: String): String {
        return Constants.DATE_FORMAT.format(System.currentTimeMillis()) + ": $message"
    }

    @SuppressLint("DefaultLocale")
    fun formatTime(time: Long): String {
        val seconds = time / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return String.format("%02dh:%02dm:%02ds", hours, minutes % 60, seconds % 60)
    }

    protected open fun updateHistoryUI(message: String) {
        writeMessageToFile(message, false) // The timestamp is added in writeMessageToFile method
    }

    open fun release() {
        closeWriter()
        logExecutor.shutdown()
    }


}