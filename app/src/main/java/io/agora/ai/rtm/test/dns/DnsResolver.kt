package io.agora.ai.rtm.test.dns

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DNS resolution utility class, supporting multiple resolution methods
 */
object DnsResolver {
    private const val TAG = "DnsResolver"

    // Use a single thread executor for asynchronous resolution
    private val dnsExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Resolution result callback interface
     */
    interface DnsResolveCallback {
        fun onDnsResolved(results: List<DnsResult>)
    }

    /**
     * DNS resolution result class
     */
    data class DnsResult(
        val method: String,        // Resolution method
        val domain: String,        // Domain name
        val ipAddresses: List<String>,  // Resolved IP address list
        val timeMs: Long,          // Resolution time (milliseconds)
        val isSuccess: Boolean,    // Whether successful
        val errorMessage: String?  // Error message
    )

    /**
     * Asynchronously perform DNS resolution, without blocking the main thread
     *
     * @param domain Domain to resolve
     * @param callback Completion callback
     */
    fun resolveDnsAsync(domain: String, callback: DnsResolveCallback) {
        dnsExecutor.execute {
            val results = mutableListOf<DnsResult>()

            // Resolve using Java standard API
            results.add(resolveWithJavaApi(domain))

            // Resolve using OkHttp DNS
            results.add(resolveWithOkHttpDns(domain))

            // Resolve using HttpDns
            results.add(resolveWithHttpDns(domain))

            // Return results to main thread
            mainHandler.post {
                callback.onDnsResolved(results)
            }
        }
    }

    /**
     * Resolve domain using Java standard API
     */
    private fun resolveWithJavaApi(domain: String): DnsResult {
        val startTime = System.currentTimeMillis()
        var isSuccess = false
        var errorMessage: String? = null
        val ipAddresses = mutableListOf<String>()

        try {
            val inetAddresses = InetAddress.getAllByName(domain)
            for (address in inetAddresses) {
                address.hostAddress?.let { ipAddresses.add(it) }
            }
            isSuccess = true
        } catch (e: UnknownHostException) {
            errorMessage = e.message
            Log.e(TAG, "Java API resolution failed: $domain", e)
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(TAG, "Java API resolution exception: $domain", e)
        }

        val timeMs = System.currentTimeMillis() - startTime
        return DnsResult(
            method = "Java Standard API",
            domain = domain,
            ipAddresses = ipAddresses,
            timeMs = timeMs,
            isSuccess = isSuccess,
            errorMessage = errorMessage
        )
    }

    /**
     * Resolve using OkHttp's DNS resolver
     */
    private fun resolveWithOkHttpDns(domain: String): DnsResult {
        val startTime = System.currentTimeMillis()
        var isSuccess = false
        var errorMessage: String? = null
        val ipAddresses = mutableListOf<String>()

        try {
            val addresses = Dns.SYSTEM.lookup(domain)
            for (address in addresses) {
                address.hostAddress?.let { ipAddresses.add(it) }
            }
            isSuccess = true
        } catch (e: UnknownHostException) {
            errorMessage = e.message
            Log.e(TAG, "OkHttp DNS resolution failed: $domain", e)
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(TAG, "OkHttp DNS resolution exception: $domain", e)
        }

        val timeMs = System.currentTimeMillis() - startTime
        return DnsResult(
            method = "OkHttp DNS",
            domain = domain,
            ipAddresses = ipAddresses,
            timeMs = timeMs,
            isSuccess = isSuccess,
            errorMessage = errorMessage
        )
    }

    /**
     * Resolve using HttpDns method (resolving domain through HTTP request)
     * Here we use OkHttp to initiate an HTTP request, letting the network library resolve the domain itself
     */
    private fun resolveWithHttpDns(domain: String): DnsResult {
        val startTime = System.currentTimeMillis()
        var isSuccess = false
        var errorMessage: String? = null
        val ipAddresses = mutableListOf<String>()

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .build()

            // Build an HTTP request, but we only care about the DNS resolution in the connection phase, not actually sending the request
            val request = Request.Builder()
                .url("https://$domain")
                .build()

            // Use a more compatible approach to get the IP address
            try {
                // First try to resolve using InetAddress directly
                val addresses = InetAddress.getAllByName(domain)
                for (address in addresses) {
                    address.hostAddress?.let { ipAddresses.add(it) }
                }

                // Then make the HTTP request to verify connectivity
                val response = client.newCall(request).execute()
                isSuccess = response.isSuccessful
                response.close()
            } catch (e: Exception) {
                errorMessage = "HTTP connection failed: ${e.message}"
                Log.e(TAG, "HTTP connection failed for domain: $domain", e)
            }

            if (ipAddresses.isEmpty() && errorMessage == null) {
                errorMessage = "No IP addresses found"
            } else if (ipAddresses.isNotEmpty()) {
                isSuccess = true
            }
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(TAG, "Http DNS resolution exception: $domain", e)
        }

        val timeMs = System.currentTimeMillis() - startTime
        return DnsResult(
            method = "HTTP DNS",
            domain = domain,
            ipAddresses = ipAddresses,
            timeMs = timeMs,
            isSuccess = isSuccess,
            errorMessage = errorMessage
        )
    }

    /**
     * Close resources
     */
    fun shutdown() {
        dnsExecutor.shutdown()
    }

    /**
     * Extract domain from URL
     *
     * @param url URL to extract domain from
     * @return Domain name or null if extraction failed
     */
    fun extractDomainFromUrl(url: String): String? {
        return try {
            // Handle different URL formats
            val domain = when {
                url.startsWith("wss://") -> url.removePrefix("wss://").split("/")[0].split(":")[0]
                url.startsWith("ws://") -> url.removePrefix("ws://").split("/")[0].split(":")[0]
                url.startsWith("https://") -> url.removePrefix("https://")
                    .split("/")[0].split(":")[0]

                url.startsWith("http://") -> url.removePrefix("http://").split("/")[0].split(":")[0]
                else -> url.split("/")[0].split(":")[0]
            }

            // Check if the domain is an IP address
            if (domain.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                null // Return null for IP addresses
            } else {
                domain
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract domain from URL: $url", e)
            null
        }
    }
} 