package io.agora.ai.rtm.test.dns

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DNS Test Manager
 * Handles DNS resolution tests and result management
 */
class DnsTestManager(private val context: Context) {

    companion object {
        private const val TAG = Constants.TAG + "-DnsTestManager"
    }

    // Test configuration
    private var domainsList = mutableListOf<String>()

    // File output
    private var dnsLogWriter: BufferedWriter? = null

    // Callbacks
    interface TestStatusCallback {
        fun onDnsTestStarted()
        fun onDnsTestProgress(message: String)
        fun onDnsTestCompleted()
        fun onDnsResultReceived(results: List<DnsResolver.DnsResult>)
    }

    private var testStatusCallback: TestStatusCallback? = null

    /**
     * Initialize DNS Test Manager
     *
     * @param callback Test status callback
     */
    fun initialize(callback: TestStatusCallback) {
        this.testStatusCallback = callback
        Log.d(TAG, "DNS Test Manager initialized")
    }

    /**
     * Add standard Agora domains to the test list
     */
    private fun addAgoraDomains() {
        domainsList.addAll(
            listOf(
                "ap1.agora.io",
                "ap2.agora.io",
                "ap3.agora.io",
                "ap4.agora.io",
                "ap5.agora.io"
            )
        )
    }

    /**
     * Initialize DNS log file
     *
     * @return Whether initialization was successful
     */
    private fun initDnsLogFile(): Boolean {
        try {
            closeDnsLogFile()

            val cacheDir = context.externalCacheDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(cacheDir, "dns_resolution_$timestamp.txt")

            dnsLogWriter = BufferedWriter(FileWriter(logFile, true))
            dnsLogWriter?.append("DNS Resolution Log - $timestamp\n")
            dnsLogWriter?.append("==================================\n\n")
            dnsLogWriter?.flush()

            Log.d(TAG, "DNS log file initialized: ${logFile.absolutePath}")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Error creating DNS log file", e)
            return false
        }
    }

    /**
     * Close DNS log file
     */
    private fun closeDnsLogFile() {
        try {
            if (dnsLogWriter != null) {
                dnsLogWriter?.flush()
                dnsLogWriter?.close()
                dnsLogWriter = null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error closing DNS log file", e)
        }
    }

    /**
     * Save DNS resolution results to file
     */
    private fun saveDnsResultsToFile(results: List<DnsResolver.DnsResult>) {
        if (dnsLogWriter == null) return

        try {
            val formattedResults = formatDnsResults(results)
            dnsLogWriter?.append(formattedResults)
            dnsLogWriter?.append("\n")
            dnsLogWriter?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing DNS results to file", e)
        }
    }

    /**
     * Convert DNS resolution results to a formatted string for log display
     */
    private fun formatDnsResults(results: List<DnsResolver.DnsResult>): String {
        val stringBuilder = StringBuilder("DNS Resolution Results:\n")

        for (result in results) {
            stringBuilder.append("Method: ${result.method}\n")
            stringBuilder.append("Domain: ${result.domain}\n")
            stringBuilder.append("Time: ${result.timeMs}ms\n")

            if (result.isSuccess) {
                stringBuilder.append("IP Addresses: ${result.ipAddresses.joinToString(", ")}\n")
            } else {
                stringBuilder.append("Resolution Failed: ${result.errorMessage ?: "Unknown error"}\n")
            }

            stringBuilder.append("------------------------\n")
        }

        return stringBuilder.toString()
    }

    /**
     * Start DNS resolution test
     */
    fun startTest() {
        // Clear previous domains list
        domainsList.clear()

        // Initialize DNS log file
        initDnsLogFile()

        // Add standard Agora domains
        addAgoraDomains()

        // Add some common domains for testing (commented out for now)
        // domainsList.addAll(listOf("www.google.com", "www.baidu.com"))

        // Notify test started
        testStatusCallback?.onDnsTestStarted()

        val message = "Starting DNS resolution for ${domainsList.size} domains..."
        Log.d(TAG, message)
        testStatusCallback?.onDnsTestProgress(message)

        // Start resolving each domain
        for (domain in domainsList) {
            // Asynchronously execute DNS resolution
            DnsResolver.resolveDnsAsync(domain, object : DnsResolver.DnsResolveCallback {
                override fun onDnsResolved(results: List<DnsResolver.DnsResult>) {
                    // Format and display DNS resolution results
                    val formattedResults = formatDnsResults(results)
                    Log.d(TAG, "DNS resolution results: $formattedResults")

                    // Save results to log file
                    saveDnsResultsToFile(results)

                    // Notify callback
                    testStatusCallback?.onDnsResultReceived(results)
                    testStatusCallback?.onDnsTestProgress("Resolved domain: ${results[0].domain}")
                }
            })
        }

        // Notify test completed after all domains are submitted for resolution
        testStatusCallback?.onDnsTestCompleted()
    }

    /**
     * Release resources
     */
    fun release() {
        closeDnsLogFile()
        DnsResolver.shutdown()
    }
} 