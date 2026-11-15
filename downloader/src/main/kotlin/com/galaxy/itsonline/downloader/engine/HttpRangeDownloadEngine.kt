package com.galaxy.itsonline.downloader.engine

import com.galaxy.itsonline.downloader.model.DownloadProgress
import com.galaxy.itsonline.downloader.model.DownloadStatus
import com.galaxy.itsonline.downloader.model.RecoveryManifest
import com.galaxy.itsonline.downloader.storage.PersistentStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException

sealed interface DownloadResult {
    data class Success(val partialFile: File, val totalBytes: Long) : DownloadResult
    data class Paused(val bytesDownloaded: Long) : DownloadResult
    data class Cancelled(val taskId: String) : DownloadResult
    data class Error(val reason: String, val isPermanent: Boolean = false) : DownloadResult
}

/**
 * Hardened RFC 9110 HTTP/HTTPS streaming download engine:
 * 1. Injectable URL Security Policy: Enforces SSRF and scheme security. Default policy rejects private/loopback/link-local.
 * 2. DNS Rebinding Protection: Validates every resolved IP address inside OkHttp's Dns lookup directly before connection.
 * 3. Safe Redirect Validation: Follows redirects step-by-step; blocks protocol downgrades (HTTPS->HTTP) and re-evaluates security policy.
 * 4. RFC 9110 Strict Range Handling:
 *    - 206 Partial Content: Validates Content-Range syntax, matching start offset, non-inverted bounds, consistent total, and body length.
 *    - Validator Consistency: Verifies matching strong ETag on resume; rejects weak ETags ("W/").
 *    - 200 OK: Safely truncates partial file and restarts from byte 0 if server ignores Range.
 *    - 416 Range Not Satisfiable: Confirms completion if partial length matches total, or restarts from byte 0 at most once (bounded).
 * 5. URL Persistence & Redacted Logging: Stores operational URL in manifest; redacts query parameters only for display/logging.
 */
class HttpRangeDownloadEngine(
    private val client: OkHttpClient,
    private val storageManager: PersistentStorageManager,
    private val securityPolicy: UrlSecurityPolicy = DefaultUrlSecurityPolicy
) {

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
        private const val CHECKPOINT_INTERVAL_BYTES = 1024 * 1024 // 1 MB persistence flush heuristic
        private const val MAX_REDIRECTS = 5

        // Process-wide active task tracking across all engine instances,
        // keyed by canonical storage directory path + ":" + taskId.
        internal val activeTasks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }

    // DNS lookup wrapper ensuring the exact IP OkHttp connects to is validated against security policy (anti-rebinding)
    private val validatingDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val addresses = client.dns.lookup(hostname)
            if (addresses.isEmpty()) {
                throw UnknownHostException("No IP addresses found for host '$hostname'")
            }
            for (addr in addresses) {
                securityPolicy.validateAddress(addr)
            }
            return addresses
        }
    }

    // Secured OkHttpClient with custom validating Dns and explicit redirect handling
    private val secureClient = client.newBuilder()
        .dns(validatingDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Internal hook for testing FileDescriptor.sync() failures
    internal var syncHook: ((java.io.FileDescriptor) -> Unit)? = null
    internal var truncateHook: ((File, Long) -> Unit)? = null
    internal var truncateSyncHook: ((java.io.FileDescriptor) -> Unit)? = null

    private fun syncFileDescriptor(fd: java.io.FileDescriptor) {
        val hook = syncHook
        if (hook != null) {
            hook.invoke(fd)
        } else {
            fd.sync()
        }
    }

    internal fun safeTruncatePartialFile(file: File, targetLength: Long) {
        if (targetLength < 0L) {
            throw IllegalArgumentException("Target length cannot be negative: $targetLength")
        }
        val actualLength = if (file.exists()) file.length() else 0L
        if (actualLength < targetLength) {
            throw IOException("Cannot truncate: actual length $actualLength is shorter than target length $targetLength")
        }
        if (actualLength > targetLength) {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                truncateHook?.invoke(file, targetLength)
                raf.setLength(targetLength)
                val syncH = truncateSyncHook
                if (syncH != null) {
                    syncH.invoke(raf.fd)
                } else {
                    raf.fd.sync()
                }
            }
        }
        val verifiedLength = if (file.exists()) file.length() else 0L
        if (verifiedLength != targetLength) {
            throw IOException("Truncation verification failed: expected length $targetLength, actual length $verifiedLength")
        }
    }

    suspend fun executeDownload(
        taskId: String,
        url: String,
        destinationFileName: String,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val storageKey = "${storageManager.progressDirCanonicalPath}:$taskId"
        if (activeTasks.putIfAbsent(storageKey, true) != null) {
            return@withContext DownloadResult.Error("Task '$taskId' is already active in another operation", isPermanent = false)
        }
        val lease = storageManager.acquireTaskLease(taskId)
        if (lease == null) {
            activeTasks.remove(storageKey)
            return@withContext DownloadResult.Error("Task '$taskId' is already active in another operation", isPermanent = false)
        }
        try {
            executeDownloadInternal(taskId, url, destinationFileName, onProgress, isRetryAfter416 = false, lease = lease)
        } finally {
            activeTasks.remove(storageKey)
            lease.release()
        }
    }

    private suspend fun executeDownloadInternal(
        taskId: String,
        url: String,
        destinationFileName: String,
        onProgress: (DownloadProgress) -> Unit,
        isRetryAfter416: Boolean,
        lease: PersistentStorageManager.TaskLease
    ): DownloadResult {
        // 0. Validate Task ID
        try {
            storageManager.validateTaskId(taskId)
        } catch (e: IllegalArgumentException) {
            return DownloadResult.Error("Invalid taskId '$taskId': ${e.message}", isPermanent = true)
        }

        // 1. URL Syntax & Scheme Security Check
        var currentUri = try {
            securityPolicy.validateUrl(url)
        } catch (e: SecurityException) {
            return DownloadResult.Error(e.message ?: "URL validation failed", isPermanent = true)
        }
        var currentUrl = currentUri.toString()

        // 2. Prepare or reconcile persistent state
        var manifest: RecoveryManifest? = null
        try {
            manifest = storageManager.validatePartialState(taskId, lease)
        } catch (e: Exception) {
            return DownloadResult.Error("Storage recovery failed for task '$taskId': ${e.message}", isPermanent = false)
        }
        val partialFile = storageManager.getPartialFile(taskId)
        var localBytes = if (partialFile.exists()) partialFile.length() else 0L

        // Resource Identity Verification:
        // Before resuming, verify that the manifest belongs to the exact requested resource URL.
        // Never append bytes from a different URL to an existing partial file.
        // If the resource identity cannot be safely established, restart from byte zero.
        if (manifest != null && manifest.url != url) {
            try {
                storageManager.removeTaskData(taskId, lease)
            } catch (e: Exception) {
                return DownloadResult.Error("Failed to invalidate mismatched task data: ${e.message}", isPermanent = false)
            }
            manifest = null
            localBytes = 0L
        } else if (manifest == null && partialFile.exists() && localBytes > 0L) {
            // Do not append to a partial file if its manifest is missing or invalid.
            try {
                storageManager.removeTaskData(taskId, lease)
            } catch (e: Exception) {
                return DownloadResult.Error("Failed to remove orphaned partial file: ${e.message}", isPermanent = false)
            }
            localBytes = 0L
        }

        // Check if we can attempt resume (only if not already a 416 retry)
        val isResumeAttempt = (!isRetryAfter416) && localBytes > 0L && manifest != null && !manifest.strongETag.isNullOrEmpty() && !manifest.strongETag.startsWith("W/")

        var redirectCount = 0

        while (true) {
            val requestBuilder = Request.Builder().url(currentUrl)

            if (isResumeAttempt) {
                requestBuilder.addHeader("Range", "bytes=$localBytes-")
                manifest?.strongETag?.let { tag ->
                    requestBuilder.addHeader("If-Range", tag)
                }
            }

            val request = requestBuilder.build()
            val response: Response
            try {
                response = secureClient.newCall(request).execute()
            } catch (e: SecurityException) {
                return DownloadResult.Error("Security policy violation: ${e.message}", isPermanent = true)
            } catch (e: IOException) {
                val cause = e.cause
                if (cause is SecurityException) {
                    return DownloadResult.Error("Security policy violation: ${cause.message}", isPermanent = true)
                }
                manifest?.let {
                    storageManager.saveManifest(it.copy(bytesDownloaded = localBytes, status = DownloadStatus.INTERRUPTED))
                }
                return DownloadResult.Error("Network transfer interrupted: ${e.message}", isPermanent = false)
            }

            // 3. Step-by-Step Redirect Validation
            if (response.isRedirect || response.code in setOf(301, 302, 303, 307, 308)) {
                redirectCount++
                if (redirectCount > MAX_REDIRECTS) {
                    response.close()
                    return DownloadResult.Error("Too many redirects (max $MAX_REDIRECTS)", isPermanent = true)
                }
                val location = response.header("Location")
                if (location.isNullOrBlank()) {
                    response.close()
                    return DownloadResult.Error("Redirect response ${response.code} missing Location header", isPermanent = true)
                }
                response.close()

                val nextUri = try {
                    currentUri.resolve(location)
                } catch (e: Exception) {
                    return DownloadResult.Error("Malformed redirect URL: ${sanitizeLogUrl(location)}", isPermanent = true)
                }

                // Security Policy check for redirect target
                try {
                    securityPolicy.validateUrl(nextUri.toString())
                } catch (e: SecurityException) {
                    return DownloadResult.Error("Redirect destination rejected: ${e.message}", isPermanent = true)
                }

                // Prevent HTTPS -> HTTP downgrade
                if (currentUri.scheme.equals("https", ignoreCase = true) && nextUri.scheme.equals("http", ignoreCase = true)) {
                    return DownloadResult.Error("Insecure redirect downgrade from HTTPS to HTTP blocked", isPermanent = true)
                }

                currentUri = nextUri
                currentUrl = nextUri.toString()
                continue
            }

            val code = response.code

            if (code == 404 || code == 403 || code == 401) {
                response.close()
                return DownloadResult.Error("HTTP Error $code: ${response.message}", isPermanent = true)
            }

            // 4. HTTP 416 Handling
            if (code == 416) {
                val contentRange = response.header("Content-Range")
                val rawETag = response.header("ETag")
                // Close the previous response before performing any retry or return
                response.close()

                // Reject unsolicited 416 if request was not a resume request
                if (!isResumeAttempt) {
                    return DownloadResult.Error(
                        "Unsolicited HTTP 416 Range Not Satisfiable received on non-resume request",
                        isPermanent = true
                    )
                }

                val totalFromRange = parseTotalFromUnsatisfiedRange(contentRange)
                val actualFileLength = if (partialFile.exists()) partialFile.length() else 0L

                // Validate strong ETag consistency with stored manifest before confirming completion
                val storedETag = manifest?.strongETag
                val isStrongETagMatch = storedETag != null && rawETag != null && rawETag == storedETag && !rawETag.startsWith("W/")

                if (totalFromRange != null && totalFromRange == actualFileLength && totalFromRange == localBytes && actualFileLength > 0L && isStrongETagMatch) {
                    // Only report completion when actual file length equals N, matches localBytes, and ETag is validated
                    val completedManifest = (manifest ?: RecoveryManifest(
                        taskId = taskId,
                        url = url,
                        destinationFileName = destinationFileName,
                        bytesDownloaded = actualFileLength,
                        totalBytes = totalFromRange,
                        strongETag = storedETag,
                        lastModified = manifest?.lastModified,
                        status = DownloadStatus.COMPLETED
                    )).copy(bytesDownloaded = actualFileLength, totalBytes = totalFromRange, status = DownloadStatus.COMPLETED)
                    storageManager.saveManifest(completedManifest)
                    return DownloadResult.Success(partialFile, actualFileLength)
                } else if (!isRetryAfter416) {
                    // Local file length doesn't equal N, ETag mismatch, or malformed range:
                    // Restart safely from byte 0 at most ONCE (bounded retry)
                    storageManager.removeTaskData(taskId, lease)
                    return executeDownloadInternal(taskId, url, destinationFileName, onProgress, isRetryAfter416 = true, lease = lease)
                } else {
                    return DownloadResult.Error(
                        "HTTP 416 Range Not Satisfiable: completion rejected (local=$actualFileLength, total=$totalFromRange, etag=$rawETag)",
                        isPermanent = true
                    )
                }
            }

            // Accept ONLY HTTP 200 and HTTP 206
            // Reject HTTP 201, 202, 204, 205 and any other unsupported 2xx or status codes
            if (code != 200 && code != 206) {
                response.close()
                val isPermanent = code in 400..499 && code != 408 && code != 429
                return DownloadResult.Error("Unsupported HTTP status $code: ${response.message}", isPermanent = isPermanent)
            }

            response.use { resp ->
                val rawETag = resp.header("ETag")
                val isStrongETag = rawETag != null && !rawETag.startsWith("W/")
                val strongETag = if (isStrongETag) rawETag else null
                val lastModified = resp.header("Last-Modified")

                val body = resp.body ?: return DownloadResult.Error("Response body is null", isPermanent = false)
                val contentLength = body.contentLength()

                val shouldAppend: Boolean
                val totalBytes: Long
                val parsedRange: ParsedContentRange?

                if (code == 206) {
                    // 1. Reject unsolicited 206 responses when no valid resume request was made
                    if (!isResumeAttempt) {
                        return DownloadResult.Error(
                            "Unsolicited HTTP 206 Partial Content received without a valid Range request",
                            isPermanent = false
                        )
                    }

                    // 2. Validate Content-Range header
                    val contentRange = resp.header("Content-Range")
                    val range = parseContentRange(contentRange)
                        ?: return DownloadResult.Error(
                            "Malformed or missing Content-Range header in 206 response: '${contentRange ?: ""}'",
                            isPermanent = false
                        )
                    parsedRange = range

                    // Range start must match existing partial file length
                    if (range.firstByte != localBytes) {
                        return DownloadResult.Error(
                            "Mismatched Content-Range start: server sent ${range.firstByte}, expected $localBytes",
                            isPermanent = false
                        )
                    }

                    // Range end must be valid and not smaller than start
                    if (range.lastByte < range.firstByte) {
                        return DownloadResult.Error(
                            "Invalid Content-Range end: lastByte ${range.lastByte} < firstByte ${range.firstByte}",
                            isPermanent = false
                        )
                    }

                    // Declared total must be consistent with returned range
                    if (range.completeLength != null && range.lastByte >= range.completeLength) {
                        return DownloadResult.Error(
                            "Inconsistent Content-Range total: lastByte ${range.lastByte} >= completeLength ${range.completeLength}",
                            isPermanent = false
                        )
                    }

                    // Expected range length with overflow protection
                    val expectedRangeLength = try {
                        Math.addExact(Math.subtractExact(range.lastByte, range.firstByte), 1L)
                    } catch (e: ArithmeticException) {
                        return DownloadResult.Error("Integer overflow in declared range length", isPermanent = false)
                    }

                    // Response body length must match declared range length when known
                    if (contentLength != -1L && contentLength != expectedRangeLength) {
                        return DownloadResult.Error(
                            "Response body length ($contentLength) does not match declared Content-Range length ($expectedRangeLength)",
                            isPermanent = false
                        )
                    }

                    // Validator consistency on resume: reject missing, changed, or weak ETags
                    val storedETag = manifest?.strongETag
                    if (storedETag != null) {
                        if (rawETag == null) {
                            return DownloadResult.Error(
                                "Missing ETag on 206 resume when resume operation depends on stored strong ETag",
                                isPermanent = false
                            )
                        }
                        if (rawETag != storedETag) {
                            return DownloadResult.Error(
                                "ETag mismatch on resume: stored=$storedETag, received=$rawETag",
                                isPermanent = false
                            )
                        }
                        if (rawETag.startsWith("W/")) {
                            return DownloadResult.Error(
                                "Server returned weak ETag on 206 Partial Content: $rawETag",
                                isPermanent = false
                            )
                        }
                    }

                    shouldAppend = true
                    totalBytes = range.completeLength ?: -1L
                } else {
                    // 200 OK: Full content
                    parsedRange = null
                    shouldAppend = false
                    if (partialFile.exists() && partialFile.length() > 0L) {
                        try {
                            safeTruncatePartialFile(partialFile, 0L)
                        } catch (truncEx: Exception) {
                            return DownloadResult.Error("Failed to truncate partial file on 200 restart: ${truncEx.message}", isPermanent = false)
                        }
                    }
                    localBytes = 0L
                    totalBytes = if (contentLength > 0) contentLength else -1L
                }

                // Prepare output file stream
                val fileOut = FileOutputStream(partialFile, shouldAppend)
                var bytesWrittenSinceCheckpoint = 0L
                var bytesReceivedInThisResponse = 0L
                var lastDurableBytes = localBytes

                var currentManifest = RecoveryManifest(
                    taskId = taskId,
                    url = url, // Operational URL preserved for resume
                    destinationFileName = destinationFileName,
                    bytesDownloaded = lastDurableBytes,
                    totalBytes = totalBytes,
                    strongETag = strongETag,
                    lastModified = lastModified,
                    status = DownloadStatus.ACTIVE
                )
                manifest = currentManifest
                storageManager.saveManifest(currentManifest)

                try {
                    fileOut.use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int

                            while (input.read(buffer).also { read = it } != -1) {
                                var offset = 0
                                while (offset < read) {
                                    val neededForCheckpoint = CHECKPOINT_INTERVAL_BYTES - bytesWrittenSinceCheckpoint
                                    val bytesToWrite = Math.min((read - offset).toLong(), neededForCheckpoint).toInt()
                                    output.write(buffer, offset, bytesToWrite)
                                    localBytes += bytesToWrite
                                    bytesReceivedInThisResponse += bytesToWrite
                                    bytesWrittenSinceCheckpoint += bytesToWrite
                                    offset += bytesToWrite

                                    if (bytesWrittenSinceCheckpoint >= CHECKPOINT_INTERVAL_BYTES) {
                                        output.flush()
                                        syncFileDescriptor(fileOut.fd)
                                        lastDurableBytes = localBytes
                                        currentManifest = currentManifest.copy(bytesDownloaded = lastDurableBytes)
                                        manifest = currentManifest
                                        storageManager.saveManifest(currentManifest)
                                        bytesWrittenSinceCheckpoint = 0L
                                    }
                                }

                                onProgress(
                                    DownloadProgress(
                                        taskId = taskId,
                                        bytesDownloaded = localBytes,
                                        totalBytes = totalBytes
                                    )
                                )
                            }
                            output.flush()
                            syncFileDescriptor(fileOut.fd)
                            lastDurableBytes = localBytes
                        }
                    }
                } catch (e: CancellationException) {
                    try {
                        safeTruncatePartialFile(partialFile, lastDurableBytes)
                        manifest?.let {
                            storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.PAUSED))
                        }
                        return DownloadResult.Paused(lastDurableBytes)
                    } catch (truncEx: Exception) {
                        manifest?.let {
                            try {
                                storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                            } catch (_: Exception) {}
                        }
                        return DownloadResult.Error("Cancellation cleanup failed: ${truncEx.message}", isPermanent = false)
                    }
                } catch (e: IOException) {
                    var cleanupError: Exception? = null
                    try {
                        safeTruncatePartialFile(partialFile, lastDurableBytes)
                    } catch (truncEx: Exception) {
                        cleanupError = truncEx
                    }
                    manifest?.let {
                        try {
                            storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                        } catch (_: Exception) {}
                    }
                    val reason = if (cleanupError != null) {
                        "Network transfer interrupted and truncation failed: ${cleanupError.message}"
                    } else {
                        "Network transfer interrupted: ${e.message}"
                    }
                    return DownloadResult.Error(reason, isPermanent = false)
                }

                // Completion checks
                if (code == 206 && parsedRange != null) {
                    val expectedRangeLength = try {
                        Math.addExact(Math.subtractExact(parsedRange.lastByte, parsedRange.firstByte), 1L)
                    } catch (e: ArithmeticException) {
                        return DownloadResult.Error("Integer overflow in declared range length", isPermanent = false)
                    }

                    // Verify actual bytes received against declared range length even when Content-Length was unknown (-1)
                    if (bytesReceivedInThisResponse != expectedRangeLength) {
                        try {
                            safeTruncatePartialFile(partialFile, lastDurableBytes)
                            manifest?.let {
                                storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                            }
                        } catch (truncEx: Exception) {
                            return DownloadResult.Error(
                                "Truncated response body and cleanup failed: ${truncEx.message}",
                                isPermanent = false
                            )
                        }
                        return DownloadResult.Error(
                            "Truncated response body: received $bytesReceivedInThisResponse bytes, expected range length $expectedRangeLength",
                            isPermanent = false
                        )
                    }

                    // Verify that returned range reaches the actual end of representation
                    if (parsedRange.completeLength == null) {
                        try {
                            safeTruncatePartialFile(partialFile, lastDurableBytes)
                            manifest?.let {
                                storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                            }
                        } catch (truncEx: Exception) {
                            return DownloadResult.Error("Unknown complete length and cleanup failed: ${truncEx.message}", isPermanent = false)
                        }
                        return DownloadResult.Error(
                            "Cannot establish completion: 206 response has unknown complete length (*)",
                            isPermanent = false
                        )
                    }

                    if (parsedRange.lastByte < parsedRange.completeLength - 1) {
                        try {
                            safeTruncatePartialFile(partialFile, lastDurableBytes)
                            manifest?.let {
                                storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                            }
                        } catch (truncEx: Exception) {
                            return DownloadResult.Error("Intermediate range and cleanup failed: ${truncEx.message}", isPermanent = false)
                        }
                        return DownloadResult.Error(
                            "Intermediate range received: end offset ${parsedRange.lastByte} did not reach end of representation (${parsedRange.completeLength - 1})",
                            isPermanent = false
                        )
                    }
                }

                // Verify final file length against known complete representation length
                val finalFileLength = partialFile.length()
                if (totalBytes > 0L && finalFileLength != totalBytes) {
                    try {
                        safeTruncatePartialFile(partialFile, lastDurableBytes)
                        manifest?.let {
                            storageManager.saveManifest(it.copy(bytesDownloaded = lastDurableBytes, status = DownloadStatus.INTERRUPTED))
                        }
                    } catch (truncEx: Exception) {
                        return DownloadResult.Error("Final file length mismatch and cleanup failed: ${truncEx.message}", isPermanent = false)
                    }
                    return DownloadResult.Error(
                        "Final file length $finalFileLength does not match known complete representation length $totalBytes",
                        isPermanent = false
                    )
                }

                // Finalize Manifest as COMPLETED
                currentManifest = currentManifest.copy(
                    bytesDownloaded = lastDurableBytes,
                    status = DownloadStatus.COMPLETED
                )
                manifest = currentManifest
                storageManager.saveManifest(currentManifest)

                return DownloadResult.Success(partialFile, lastDurableBytes)
            }
        }
    }

    data class ParsedContentRange(
        val firstByte: Long,
        val lastByte: Long,
        val completeLength: Long?
    )

    private fun parseContentRange(contentRange: String?): ParsedContentRange? {
        if (contentRange == null) return null
        val regex = Regex("""^bytes\s+(\d+)-(\d+)/((\d+)|\*)$""", RegexOption.IGNORE_CASE)
        val match = regex.find(contentRange.trim()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        if (first < 0L || last < 0L) return null
        val total = if (match.groupValues[3] == "*") null else match.groupValues[4].toLongOrNull() ?: return null
        if (total != null && total <= 0L) return null
        return ParsedContentRange(first, last, total)
    }

    internal fun parseTotalFromUnsatisfiedRange(contentRange: String?): Long? {
        if (contentRange == null) return null
        val regex = Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)
        val match = regex.find(contentRange.trim()) ?: return null
        val total = match.groupValues[1].toLongOrNull()
        return if (total != null && total >= 0L) total else null
    }

    fun sanitizeLogUrl(rawUrl: String): String {
        return try {
            val u = URI(rawUrl)
            val scheme = u.scheme ?: "http"
            val host = u.host ?: ""
            val portStr = if (u.port != -1 && u.port != 80 && u.port != 443) ":${u.port}" else ""
            val path = u.rawPath ?: ""
            if (u.rawQuery != null) {
                "$scheme://$host$portStr$path?[REDACTED]"
            } else {
                "$scheme://$host$portStr$path"
            }
        } catch (e: Exception) {
            "URL[REDACTED]"
        }
    }
}
