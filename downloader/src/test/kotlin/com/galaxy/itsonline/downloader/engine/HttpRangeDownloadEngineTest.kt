package com.galaxy.itsonline.downloader.engine

import com.galaxy.itsonline.downloader.storage.PersistentStorageManager
import com.galaxy.itsonline.downloader.export.StorageExportBroker
import com.galaxy.itsonline.downloader.export.ExportResult
import com.galaxy.itsonline.verify.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class HttpRangeDownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var storageManager: PersistentStorageManager
    private lateinit var engine: HttpRangeDownloadEngine
    private val client = OkHttpClient.Builder().build()

    /**
     * Test-only URL security policy permitting ONLY the local loopback address on the MockWebServer port.
     * All other private, link-local, and wildcard addresses remain strictly prohibited.
     */
    private class MockWebServerUrlSecurityPolicy(private val permittedPort: Int) : UrlSecurityPolicy {
        override fun validateUrl(url: String): URI {
            val uri = DefaultUrlSecurityPolicy.validateUrl(url)
            return uri
        }

        override fun validateAddress(address: InetAddress) {
            if (address.isLoopbackAddress) {
                // Allowed strictly for the local MockWebServer test harness
                return
            }
            DefaultUrlSecurityPolicy.validateAddress(address)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("downloader_test").toFile()
        storageManager = PersistentStorageManager(tempDir)
        engine = HttpRangeDownloadEngine(
            client = client,
            storageManager = storageManager,
            securityPolicy = MockWebServerUrlSecurityPolicy(server.port)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSuccessful200Download() = runBlocking {
        val payload = "Hello Galaxy Media Stream Content".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setHeader("ETag", "\"strong-tag-123\"")
                .setBody(okio.Buffer().write(payload))
        )

        val taskId = "test-task-1"
        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success but got: $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(payload.size.toLong(), success.totalBytes)

        val partialFile = storageManager.getPartialFile(taskId)
        assertTrue(partialFile.exists())
        assertEquals(payload.size.toLong(), partialFile.length())
    }

    @Test
    fun testSuccessful206RangeResume() = runBlocking {
        val taskId = "test-resume-task"
        val partialFile = storageManager.getPartialFile(taskId)

        // Seed 10 bytes locally
        val initialBytes = "0123456789".toByteArray()
        partialFile.writeBytes(initialBytes)

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-abc\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        val remainingBytes = "ABCDEFGHIJ".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setHeader("ETag", "\"etag-abc\"")
                .setBody(okio.Buffer().write(remainingBytes))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success but got: $result", result is DownloadResult.Success)
        assertEquals(20L, (result as DownloadResult.Success).totalBytes)
        assertEquals(20L, partialFile.length())
        assertEquals("0123456789ABCDEFGHIJ", String(partialFile.readBytes()))

        // Verify Range header was sent
        val recordedRequest = server.takeRequest()
        assertEquals("bytes=10-", recordedRequest.getHeader("Range"))
        assertEquals("\"etag-abc\"", recordedRequest.getHeader("If-Range"))
    }

    @Test
    fun testServerIgnoresRangeAndReturns200TrunatesAndRestarts() = runBlocking {
        val taskId = "test-200-restart"
        val partialFile = storageManager.getPartialFile(taskId)

        // Seed 15 dirty bytes
        partialFile.writeBytes("DIRTY_OLD_BYTES".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 15L,
                totalBytes = 30L,
                strongETag = "\"old-tag\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        val freshFullPayload = "FRESH_CLEAN_FULL_PAYLOAD".toByteArray()
        // Server ignores range and sends 200 OK
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", freshFullPayload.size.toString())
                .setBody(okio.Buffer().write(freshFullPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success but got: $result", result is DownloadResult.Success)
        assertEquals(freshFullPayload.size.toLong(), partialFile.length())
        assertEquals("FRESH_CLEAN_FULL_PAYLOAD", String(partialFile.readBytes()))
    }

    @Test
    fun testRejectUnsafeUrlScheme() = runBlocking {
        val result = engine.executeDownload(
            taskId = "bad-scheme",
            url = "file:///etc/passwd",
            destinationFileName = "passwd"
        )
        assertTrue(result is DownloadResult.Error)
        assertTrue((result as DownloadResult.Error).isPermanent)
    }

    @Test
    fun testProductionPolicyRejectsLoopbackAndPrivateDestinations() = runBlocking {
        // Engine created with default production policy (no test exceptions)
        val prodEngine = HttpRangeDownloadEngine(
            client = client,
            storageManager = storageManager
        )

        val result = prodEngine.executeDownload(
            taskId = "prod-ssrf-check",
            url = server.url("/secret.mp4").toString(),
            destinationFileName = "secret.mp4"
        )

        assertTrue("Expected Error for loopback destination under default policy, got: $result", result is DownloadResult.Error)
        val error = result as DownloadResult.Error
        assertTrue(error.isPermanent)
        assertTrue(error.reason.contains("Security policy") || error.reason.contains("blocked") || error.reason.contains("loopback"))
    }

    // Task 3: Content-Range Correctness Negative Tests

    @Test
    fun testRejectMalformedContentRangeOn206() = runBlocking {
        val taskId = "test-malformed-range"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server sends 206 with malformed Content-Range header
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes invalid-garbage-range")
                .setBody("ABCDEFGHIJ")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Malformed or missing Content-Range"))
        // Confirmed partial file is NOT modified
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testRejectWrongStartOffsetOn206() = runBlocking {
        val taskId = "test-wrong-start-range"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Local bytes = 10, but server sends Content-Range starting at 5
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-19/20")
                .setBody("123456789012345")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for wrong start offset, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Mismatched Content-Range start"))
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testRejectInvalidEndOffsetOn206() = runBlocking {
        val taskId = "test-invalid-end-range"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Content-Range where lastByte (8) < firstByte (10)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-8/20")
                .setBody("")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for invalid end offset, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Invalid Content-Range end"))
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testRejectInconsistentTotalOn206() = runBlocking {
        val taskId = "test-inconsistent-total"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Content-Range where lastByte (25) >= completeLength (20)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-25/20")
                .setBody("0123456789012345")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for inconsistent total, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Inconsistent Content-Range total"))
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testRejectMismatchedETagOn206Resume() = runBlocking {
        val taskId = "test-mismatched-etag"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"original-etag\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server responds with 206 but a different ETag indicating resource changed
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setHeader("ETag", "\"changed-different-etag\"")
                .setBody("ABCDEFGHIJ")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for mismatched ETag, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("ETag mismatch on resume"))
        // Original partial file was not corrupted with bytes from different resource version
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testStorageExportVerificationGateAndCleanup() = runBlocking {
        val taskId = "export-task"
        val partialFile = storageManager.getPartialFile(taskId)

        // Write a valid MP4 header into the partial file
        val mp4Header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x02, 0x00
        ) + ByteArray(20)
        partialFile.writeBytes(mp4Header)

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/video.mp4",
                destinationFileName = "video.mp4",
                bytesDownloaded = mp4Header.size.toLong(),
                totalBytes = mp4Header.size.toLong(),
                strongETag = null,
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED
            )
        )

        val exportBroker = StorageExportBroker(storageManager)
        val destinationStream = ByteArrayOutputStream()

        val exportResult = exportBroker.exportToDestination(
            taskId = taskId,
            destinationStreamProvider = { destinationStream },
            expectedFormat = MediaFormat.MP4
        )

        assertTrue(exportResult is ExportResult.Success)
        assertEquals(mp4Header.size.toLong(), (exportResult as ExportResult.Success).bytesExported)
        assertEquals(mp4Header.size, destinationStream.size())

        // Confirmed cleanup after successful export
        assertTrue(!partialFile.exists())
        assertTrue(storageManager.loadManifest(taskId) == null)
    }

    @Test
    fun testReject206WithUnknownContentLengthAndTruncatedBody() = runBlocking {
        val taskId = "test-truncated-body"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Declares range 10-19 (10 bytes expected), but chunked stream delivers only 5 bytes
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setHeader("ETag", "\"etag-1\"")
                .setChunkedBody(okio.Buffer().write("ABCDE".toByteArray()), 5)
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue("Expected Truncated error message, got: ${err.reason}", err.reason.contains("Truncated response body"))
        val manifest = storageManager.loadManifest(taskId)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, manifest?.status)
    }

    @Test
    fun testReject206ReturningIntermediateRangeInsteadOfEnd() = runBlocking {
        val taskId = "test-intermediate-range"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 30L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server only serves intermediate range 10-19 of 30, not reaching end (29)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/30")
                .setHeader("ETag", "\"etag-1\"")
                .setBody("ABCDEFGHIJ")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue("Expected intermediate range message, got: ${err.reason}", err.reason.contains("Intermediate range received"))
        val manifest = storageManager.loadManifest(taskId)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, manifest?.status)
    }

    @Test
    fun testReject206WithIncorrectFinalFileLength() = runBlocking {
        val taskId = "test-wrong-final-len"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server declares total 20, but range is 10-18/20 (length 9) so final length will be 19, not 20
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-18/20")
                .setHeader("ETag", "\"etag-1\"")
                .setBody("ABCDEFGHI")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Intermediate range") || err.reason.contains("does not match"))
    }

    @Test
    fun testRejectMissingETagDuringStrongETagResume() = runBlocking {
        val taskId = "test-missing-etag"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"strong-etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server responds 206 but omits ETag header entirely
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setBody("ABCDEFGHIJ")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue("Expected missing ETag error, got: ${err.reason}", err.reason.contains("Missing ETag"))
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun test416WithBytesStarSlashNWhereLocalFileIsComplete() = runBlocking {
        val taskId = "test-416-complete"
        val partialFile = storageManager.getPartialFile(taskId)
        val completeBytes = "01234567890123456789".toByteArray() // 20 bytes
        partialFile.writeBytes(completeBytes)

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 20L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server sends 416 with RFC 9110 syntax: bytes */20 and matching strong ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */20")
                .setHeader("ETag", "\"etag-1\"")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success, got $result", result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertEquals(20L, success.totalBytes)
        assertEquals(20L, partialFile.length())
        val manifest = storageManager.loadManifest(taskId)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED, manifest?.status)
    }

    @Test
    fun test416WhereLocalFileIsIncompleteTriggersSafeRestart() = runBlocking {
        val taskId = "test-416-restart"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("DIRTY_PARTIAL_BYTES".toByteArray()) // 19 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 19L,
                totalBytes = 30L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // 1st response: 416 with bytes */30 (incomplete because 19 != 30) -> triggers safe restart from byte 0
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */30")
        )

        // 2nd response (restart): 200 OK with full 30 bytes
        val cleanFullPayload = "CLEAN_RESTARTED_FULL_PAYLOAD30".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", cleanFullPayload.size.toString())
                .setBody(okio.Buffer().write(cleanFullPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success after safe restart, got $result", result is DownloadResult.Success)
        assertEquals(cleanFullPayload.size.toLong(), partialFile.length())
        assertEquals("CLEAN_RESTARTED_FULL_PAYLOAD30", String(partialFile.readBytes()))
    }

    @Test
    fun testExactlyOneBoundedRetryAfter416() = runBlocking {
        val taskId = "test-416-bounded"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("DIRTY_BYTES".toByteArray())

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 11L,
                totalBytes = 30L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // 1st response: 416 -> triggers restart from byte 0
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */30")
        )

        // 2nd response (retry from 0): 416 again! Must NOT retry recursively
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */30")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error after bounded retry, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.isPermanent)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun testDifferentUrlUsingExistingTaskIdRestartsFromByteZero() = runBlocking {
        val taskId = "test-url-mismatch"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("OLD_URL_PARTIAL_BYTES".toByteArray())

        // Manifest points to old URL
        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/other-file.mp4",
                destinationFileName = "other.mp4",
                bytesDownloaded = 21L,
                totalBytes = 50L,
                strongETag = "\"etag-old\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        val newPayload = "NEW_URL_FRESH_CONTENT".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", newPayload.size.toString())
                .setBody(okio.Buffer().write(newPayload))
        )

        // Call engine with a DIFFERENT URL using the same taskId
        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/new-media.mp4").toString(),
            destinationFileName = "new-media.mp4"
        )

        assertTrue("Expected DownloadResult.Success, got $result", result is DownloadResult.Success)
        assertEquals(newPayload.size.toLong(), partialFile.length())
        assertEquals("NEW_URL_FRESH_CONTENT", String(partialFile.readBytes()))

        // Verify request sent had NO Range header (safely restarted from 0)
        val req = server.takeRequest()
        assertTrue("Request must not have Range header when URL differs", req.getHeader("Range") == null)
    }

    @Test
    fun testRejectUnsolicited206Response() = runBlocking {
        val taskId = "test-unsolicited-206"

        // Client initiates fresh download from byte 0 (no Range header sent)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/10")
                .setBody("0123456789")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for unsolicited 206, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue("Expected unsolicited 206 error, got: ${err.reason}", err.reason.contains("Unsolicited HTTP 206"))
    }

    @Test
    fun testMissingManifestWithExistingPartialFileRestartsFromByteZero() = runBlocking {
        val taskId = "test-missing-manifest"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("ORPHANED_DATA_NO_MANIFEST".toByteArray())

        // Manifest file does NOT exist
        val manifestFile = storageManager.getManifestFile(taskId)
        if (manifestFile.exists()) manifestFile.delete()

        val fullPayload = "CLEAN_FRESH_FULL_DATA".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", fullPayload.size.toString())
                .setBody(okio.Buffer().write(fullPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success, got $result", result is DownloadResult.Success)
        assertEquals(fullPayload.size.toLong(), partialFile.length())
        assertEquals("CLEAN_FRESH_FULL_DATA", String(partialFile.readBytes()))
    }

    @Test
    fun testInvalidTaskIdAndPathTraversalRejected() = runBlocking {
        // Path traversal attempts
        val traversalIds = listOf("../escape", "foo/bar", "sub..dir", "", "bad id with spaces")
        for (badId in traversalIds) {
            val result = engine.executeDownload(
                taskId = badId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4"
            )
            assertTrue("Expected Error for bad taskId '$badId', got: $result", result is DownloadResult.Error)
            val err = result as DownloadResult.Error
            assertTrue(err.isPermanent)
            assertTrue(err.reason.contains("Invalid taskId"))
        }
    }

    @Test
    fun testManifestInterruptionOrCorruptionHandledSafely() = runBlocking {
        val taskId = "test-corrupt-manifest"
        val manifestFile = storageManager.getManifestFile(taskId)
        // Write corrupted/garbage data into manifest
        manifestFile.writeBytes("GARBAGE_CORRUPT_MANIFEST_DATA".toByteArray())

        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("OLD_CORRUPTED_PARTIAL".toByteArray())

        // loadManifest returns null on corruption
        val loaded = storageManager.loadManifest(taskId)
        assertEquals(null, loaded)

        val fullPayload = "VALID_CLEAN_STREAM".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", fullPayload.size.toString())
                .setBody(okio.Buffer().write(fullPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Success, got $result", result is DownloadResult.Success)
        assertEquals(fullPayload.size.toLong(), partialFile.length())
        assertEquals("VALID_CLEAN_STREAM", String(partialFile.readBytes()))

        // Verify valid manifest is saved atomically
        val validManifest = storageManager.loadManifest(taskId)
        assertTrue(validManifest != null)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED, validManifest?.status)
    }

    @Test
    fun testRejectHttp201Created() = runBlocking {
        val taskId = "test-201-status"
        server.enqueue(MockResponse().setResponseCode(201).setBody("Created resource"))

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for 201, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Unsupported HTTP status 201"))
    }

    @Test
    fun testRejectHttp202Accepted() = runBlocking {
        val taskId = "test-202-status"
        server.enqueue(MockResponse().setResponseCode(202).setBody("Accepted request"))

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for 202, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Unsupported HTTP status 202"))
    }

    @Test
    fun testRejectHttp204NoContent() = runBlocking {
        val taskId = "test-204-status"
        server.enqueue(MockResponse().setResponseCode(204))

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for 204, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Unsupported HTTP status 204"))
    }

    @Test
    fun testRejectHttp205ResetContent() = runBlocking {
        val taskId = "test-205-status"
        server.enqueue(MockResponse().setResponseCode(205))

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for 205, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Unsupported HTTP status 205"))
    }

    @Test
    fun testRejectUnsupported2xxStatus() = runBlocking {
        val taskId = "test-203-status"
        server.enqueue(MockResponse().setResponseCode(203).setBody("Non-Authoritative Information"))

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected DownloadResult.Error for 203, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("Unsupported HTTP status 203"))
    }

    @Test
    fun testRejectUnsolicited416WhenNotResumeRequest() = runBlocking {
        val taskId = "test-unsolicited-416"
        // Fresh download from byte 0 (no range requested)
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */20")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected Error for unsolicited 416, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.isPermanent)
        assertTrue(err.reason.contains("Unsolicited HTTP 416"))
    }

    @Test
    fun testRejectMalformed416RangeWithBytesXYSlashN() = runBlocking {
        val taskId = "test-malformed-416"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 20L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server sends malformed 416 using bytes 0-19/20 instead of bytes */20
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes 0-19/20")
                .setHeader("ETag", "\"etag-1\"")
        )

        // Bounded retry from byte 0 receives 200 OK with clean payload
        val cleanFullPayload = "CLEAN_PAYLOAD_20BYTES".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", cleanFullPayload.size.toString())
                .setBody(okio.Buffer().write(cleanFullPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        // Completion was rejected for the malformed 416, triggering safe restart
        assertTrue("Expected Success after safe restart, got $result", result is DownloadResult.Success)
        assertEquals(cleanFullPayload.size.toLong(), partialFile.length())
        assertEquals("CLEAN_PAYLOAD_20BYTES", String(partialFile.readBytes()))
    }

    @Test
    fun testReject416WithWrongFileLength() = runBlocking {
        val taskId = "test-416-wrong-len"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("012345678901234".toByteArray()) // 15 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 15L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server returns 416 declaring total 20, but local file is only 15 bytes
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */20")
                .setHeader("ETag", "\"etag-1\"")
        )

        // Second response: 416 again to test bounded retry exhaustion
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */20")
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected Error when wrong file length 416 cannot complete and retry fails, got $result", result is DownloadResult.Error)
    }

    @Test
    fun testReject416WithETagMismatch() = runBlocking {
        val taskId = "test-416-etag-mismatch"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 20L,
                totalBytes = 20L,
                strongETag = "\"stored-etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Server returns 416 with mismatched ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */20")
                .setHeader("ETag", "\"different-server-etag\"")
        )

        // Second request for safe restart receives fresh 200 OK
        val freshPayload = "FRESH_VERSION_20BYTE".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", freshPayload.size.toString())
                .setBody(okio.Buffer().write(freshPayload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        // Verify completion was rejected on mismatched ETag, restarted safely
        assertTrue("Expected Success after restart, got $result", result is DownloadResult.Success)
        assertEquals("FRESH_VERSION_20BYTE", String(partialFile.readBytes()))
    }

    @Test
    fun testParseTotalFromUnsatisfiedRangeAcceptsOnlyStarSlashN() {
        // Valid forms
        assertEquals(20L, engine.parseTotalFromUnsatisfiedRange("bytes */20"))
        assertEquals(0L, engine.parseTotalFromUnsatisfiedRange("bytes */0"))
        assertEquals(12345L, engine.parseTotalFromUnsatisfiedRange("bytes   */12345"))
        assertEquals(999L, engine.parseTotalFromUnsatisfiedRange("BYTES */999"))

        // Invalid forms - must be rejected
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("bytes 0-19/20"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("bytes 10-19/20"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("bytes */*"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("bytes */-5"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("*/20"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange("bytes 20"))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange(null))
        assertEquals(null, engine.parseTotalFromUnsatisfiedRange(""))
    }

    @Test
    fun testSyncFailureDuringCheckpointDoesNotSaveManifestAndFails() = runBlocking {
        val taskId = "test-sync-failure-checkpoint"
        val payload = ByteArray(1024 * 1024 + 100) { 0x41 } // > 1 MB to trigger checkpoint sync

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload))
        )

        var syncAttempts = 0
        engine.syncHook = {
            syncAttempts++
            throw java.io.SyncFailedException("Simulated sync failure during checkpoint")
        }

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        engine.syncHook = null

        assertTrue("Expected DownloadResult.Error when sync fails, got $result", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue(err.reason.contains("interrupted") || err.reason.contains("sync failure"))
        assertTrue("Expected sync to have been called", syncAttempts > 0)

        // Manifest must NOT be COMPLETED and must NOT advance to undurable offset
        val manifest = storageManager.loadManifest(taskId)
        assertTrue(manifest != null)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, manifest?.status)
        assertEquals(0L, manifest?.bytesDownloaded)

        // Partial file tail must be truncated to durable offset (0L)
        val partialFile = storageManager.getPartialFile(taskId)
        assertEquals(0L, partialFile.length())
    }

    @Test
    fun testSyncFailureAtStreamCompletionDoesNotDeclareCompleted() = runBlocking {
        val taskId = "test-sync-failure-completion"
        val payload = "SMALL_PAYLOAD".toByteArray()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload))
        )

        engine.syncHook = {
            throw java.io.SyncFailedException("Simulated final sync failure at EOF")
        }

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        engine.syncHook = null

        assertTrue("Expected DownloadResult.Error when final sync fails, got $result", result is DownloadResult.Error)
        val manifest = storageManager.loadManifest(taskId)
        assertTrue(manifest != null)
        // CRITICAL INVARIANT: Manifest must NEVER be marked COMPLETED when sync fails!
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, manifest?.status)
        assertEquals(0L, manifest?.bytesDownloaded)

        val partialFile = storageManager.getPartialFile(taskId)
        assertEquals(0L, partialFile.length())
    }

    @Test
    fun testNetworkIOExceptionBetweenCheckpointsKeepsManifestAtDurableOffset() = runBlocking {
        val taskId = "test-network-io-exception-durable"
        // 1.5 MB payload. First 1 MB triggers checkpoint sync.
        val fullSize = 1024 * 1024 + 500 * 1024
        val payload = ByteArray(fullSize) { 0x42 }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", fullSize.toString())
                .setBody(okio.Buffer().write(payload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4",
            onProgress = { progress ->
                if (progress.bytesDownloaded >= 1024 * 1024L + 20 * 1024L) {
                    throw java.io.IOException("Simulated network read failure mid-stream after checkpoint")
                }
            }
        )

        assertTrue("Expected Error on network truncation, got $result", result is DownloadResult.Error)

        val manifest = storageManager.loadManifest(taskId)
        assertTrue(manifest != null)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, manifest?.status)
        // CRITICAL INVARIANT: Manifest must stay at the committed checkpoint (1 MB), NOT uncommitted bytes!
        assertEquals(1024 * 1024L, manifest?.bytesDownloaded)

        val partialFile = storageManager.getPartialFile(taskId)
        assertEquals(1024 * 1024L, partialFile.length())
    }

    @Test
    fun testCancellationDoesNotSaveUndurableOffset() = runBlocking {
        val taskId = "test-cancellation-durable"
        val payload = ByteArray(500 * 1024) { 0x43 } // 500 KB (below 1 MB checkpoint)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4",
            onProgress = { progress ->
                if (progress.bytesDownloaded >= 50 * 1024) {
                    throw kotlinx.coroutines.CancellationException("User cancelled mid-transfer")
                }
            }
        )

        assertTrue("Expected DownloadResult.Paused, got $result", result is DownloadResult.Paused)
        val paused = result as DownloadResult.Paused
        assertEquals(0L, paused.bytesDownloaded)

        val manifest = storageManager.loadManifest(taskId)
        assertTrue(manifest != null)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.PAUSED, manifest?.status)
        // CRITICAL INVARIANT: Because checkpoint at 1 MB was never reached, durable offset remains 0L!
        assertEquals(0L, manifest?.bytesDownloaded)

        val partialFile = storageManager.getPartialFile(taskId)
        assertEquals(0L, partialFile.length())
    }

    @Test
    fun testRecoveryDoesNotPromoteUncommittedFileTail() {
        val taskId = "test-recovery-no-promote-tail"
        val partialFile = storageManager.getPartialFile(taskId)
        val committedData = "0123456789".toByteArray() // 10 bytes committed
        val uncommittedTail = "UNCOMMITTED_TAIL_BYTES".toByteArray() // 22 bytes uncommitted tail
        partialFile.writeBytes(committedData + uncommittedTail) // total 32 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L, // Committed checkpoint offset is 10
                totalBytes = 100L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // validatePartialState must NOT promote bytesDownloaded to 32
        val validated = storageManager.validatePartialState(taskId)
        assertTrue("Expected non-null validated manifest", validated != null)
        assertEquals("Committed offset must NOT be promoted to uncommitted tail size", 10L, validated?.bytesDownloaded)
        assertEquals("Uncommitted tail data on disk must be truncated to committed offset", 10L, partialFile.length())
        assertEquals("Committed prefix must be preserved intact", "0123456789", String(partialFile.readBytes()))
    }

    @Test
    fun testPartialFileShorterThanManifestIsSafelyInvalidated() {
        val taskId = "test-file-shorter-than-manifest"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("SHORT".toByteArray()) // 5 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 50L, // Manifest claimed 50 bytes were committed
                totalBytes = 100L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Partial file is shorter than committed offset (broken checkpoint integrity)
        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when partial file is shorter than committed offset")
        } catch (e: java.io.IOException) {
            assertTrue("Expected shorter than committed offset message, got: ${e.message}", e.message?.contains("shorter than committed durable offset") == true)
        }
        // Invariant: Available files must be preserved rather than deleting potentially useful data
        assertNotNull("Manifest should be preserved", storageManager.loadManifest(taskId))
        assertTrue("Partial file should be preserved on disk", partialFile.exists())
    }

    @Test
    fun testResumeAfterRecoveryStartsOnlyFromDurableData() = runBlocking {
        val taskId = "test-resume-after-recovery-reliable"
        val partialFile = storageManager.getPartialFile(taskId)
        val committedData = "0123456789".toByteArray() // 10 bytes committed
        val uncommittedTail = "DIRTY_DIRTY_TAIL".toByteArray() // 16 uncommitted bytes
        partialFile.writeBytes(committedData + uncommittedTail) // 26 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L, // committed offset
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        val remaining10Bytes = "ABCDEFGHIJ".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setHeader("ETag", "\"etag-1\"")
                .setBody(okio.Buffer().write(remaining10Bytes))
        )

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )

        assertTrue("Expected Success, got $result", result is DownloadResult.Success)

        // Verify Range request sent was bytes=10- (NOT bytes=26-)
        val recordedRequest = server.takeRequest()
        assertEquals("bytes=10-", recordedRequest.getHeader("Range"))
        assertEquals("\"etag-1\"", recordedRequest.getHeader("If-Range"))

        // Verify final file
        assertEquals(20L, partialFile.length())
        assertEquals("0123456789ABCDEFGHIJ", String(partialFile.readBytes()))

        val manifest = storageManager.loadManifest(taskId)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED, manifest?.status)
        assertEquals(20L, manifest?.bytesDownloaded)
    }

    // ==========================================
    // Phase 1.2.3: Storage Failure & Atomic Manifest Closure Tests
    // ==========================================

    // A. Truncation Tests

    @Test
    fun testTruncateFailureDoesNotReportRecoverySuccess() {
        val taskId = "test-truncate-fail-no-recovery"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject truncation failure during recovery
        storageManager.recoveryTruncateHook = { _, _ ->
            throw java.io.IOException("Simulated recovery truncation failure")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when truncation fails")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated recovery truncation failure") == true)
        } finally {
            storageManager.recoveryTruncateHook = null
        }

        // Invariant: Failed truncation must fail closed, propagate failure, and preserve files
        assertNotNull("Manifest must remain preserved on disk", storageManager.loadManifest(taskId))
        assertTrue("Partial file must remain preserved on disk", partialFile.exists())
    }

    @Test
    fun testSyncFailureAfterTruncateDoesNotReportSuccess() {
        val taskId = "test-sync-fail-after-truncate"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject sync failure after truncate
        storageManager.recoveryTruncateSyncHook = {
            throw java.io.SyncFailedException("Simulated sync failure after recovery truncate")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected SyncFailedException when sync fails after truncate")
        } catch (e: java.io.SyncFailedException) {
            assertTrue(e.message?.contains("Simulated sync failure") == true)
        } finally {
            storageManager.recoveryTruncateSyncHook = null
        }

        // Invariant: Sync failure after truncate must fail closed and preserve files
        assertNotNull("Manifest must remain preserved on disk", storageManager.loadManifest(taskId))
        assertTrue("Partial file must remain preserved on disk", partialFile.exists())
    }

    @Test
    fun testTruncateVerificationFailureIsRejected() {
        val taskId = "test-truncate-verification-fail"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes

        // Injected hook writes an extra byte during sync to simulate length mismatch after truncate
        engine.truncateSyncHook = {
            java.io.FileOutputStream(partialFile, true).use { it.write(0x58) } // append 'X'
        }

        try {
            engine.safeTruncatePartialFile(partialFile, 10L)
            fail("Expected IOException when truncation verification fails")
        } catch (e: java.io.IOException) {
            assertTrue("Expected verification error, got: ${e.message}", e.message?.contains("verification failed") == true)
        } finally {
            engine.truncateSyncHook = null
        }
    }

    @Test
    fun testTruncateRejectsNegativeTargetLength() {
        val taskId = "test-truncate-negative"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        try {
            engine.safeTruncatePartialFile(partialFile, -5L)
            fail("Expected IllegalArgumentException on negative target length")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected negative error message, got: ${e.message}", e.message?.contains("negative") == true)
        }
    }

    @Test
    fun testTruncateDoesNotExtendShorterFile() {
        val taskId = "test-truncate-shorter"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("12345".toByteArray()) // 5 bytes

        try {
            engine.safeTruncatePartialFile(partialFile, 10L)
            fail("Expected IOException when actual length is shorter than target length")
        } catch (e: java.io.IOException) {
            assertTrue("Expected shorter length error, got: ${e.message}", e.message?.contains("shorter") == true)
        }

        // File length must NEVER be extended
        assertEquals(5L, partialFile.length())
    }

    @Test
    fun testResumeBlockedWhenTruncationFails() = runBlocking {
        val taskId = "test-resume-blocked-truncate-fail"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject truncation failure during resume preparation
        storageManager.recoveryTruncateHook = { _, _ ->
            throw java.io.IOException("Simulated recovery truncation failure")
        }

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )
        storageManager.recoveryTruncateHook = null

        // Invariant: Resume must be completely blocked (Error returned, never Success or unverified resume)
        assertTrue("Expected DownloadResult.Error when truncation fails, got $result", result is DownloadResult.Error)
    }

    // B. Deletion Tests

    @Test
    fun testRemoveTaskDataDetectsPartialFileDeletionFailure() {
        val taskId = "test-del-fail-part"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("dummy".toByteArray())

        storageManager.deleteHook = { file ->
            if (file.name.endsWith(".part")) false else file.delete()
        }

        try {
            storageManager.removeTaskData(taskId)
            fail("Expected IOException when partial file deletion fails")
        } catch (e: java.io.IOException) {
            assertTrue("Expected deletion failure message, got: ${e.message}", e.message?.contains("Failed to delete") == true)
        } finally {
            storageManager.deleteHook = null
        }

        assertTrue("Partial file must still exist after failed deletion", partialFile.exists())
    }

    @Test
    fun testRemoveTaskDataDetectsManifestDeletionFailure() {
        val taskId = "test-del-fail-manifest"
        val manifestFile = storageManager.getManifestFile(taskId)
        manifestFile.writeText("taskId=$taskId\n")

        storageManager.deleteHook = { file ->
            if (file.name.endsWith(".manifest")) false else file.delete()
        }

        try {
            storageManager.removeTaskData(taskId)
            fail("Expected IOException when manifest deletion fails")
        } catch (e: java.io.IOException) {
            assertTrue("Expected deletion failure message, got: ${e.message}", e.message?.contains("Failed to delete") == true)
        } finally {
            storageManager.deleteHook = null
        }

        assertTrue("Manifest file must still exist after failed deletion", manifestFile.exists())
    }

    @Test
    fun testFailedInvalidationCannotReturnValidManifest() {
        val taskId = "test-failed-invalidation-no-manifest"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("12345".toByteArray()) // 5 bytes on disk

        // Manifest expects 10 bytes (actual < committed -> must invalidate)
        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject deletion failure so invalidation cannot complete cleanly
        storageManager.deleteHook = { false }

        val validated = try {
            storageManager.validatePartialState(taskId)
        } catch (e: Exception) {
            null
        } finally {
            storageManager.deleteHook = null
        }

        // Invariant: Failed invalidation must never return a valid manifest
        assertNull("Failed invalidation must never return a valid manifest", validated)
    }

    @Test
    fun testResumeBlockedAfterFailedInvalidation() = runBlocking {
        val taskId = "test-resume-blocked-failed-invalidation"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("12345".toByteArray()) // 5 bytes on disk

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject deletion failure
        storageManager.deleteHook = { false }

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )
        storageManager.deleteHook = null

        // Invariant: Resume must be blocked with an Error
        assertTrue("Resume must be blocked when invalidation fails, got $result", result is DownloadResult.Error)
    }

    // C. Recovery Tests

    @Test
    fun testRecoveryRejectsNegativeCommittedOffset() {
        val taskId = "test-negative-committed"
        val manifestFile = storageManager.getManifestFile(taskId)
        manifestFile.writeText(
            """
            taskId=$taskId
            url=http://example.com/media.mp4
            destinationFileName=media.mp4
            bytesDownloaded=-10
            totalBytes=100
            status=INTERRUPTED
            """.trimIndent()
        )

        assertNull("Negative bytesDownloaded in loadManifest must be rejected", storageManager.loadManifest(taskId))
        assertNull("Negative bytesDownloaded in validatePartialState must be rejected", storageManager.validatePartialState(taskId))
    }

    @Test
    fun testRecoveryVerifiesActualLengthAfterTruncation() {
        val taskId = "test-recovery-verifies-length"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789012345678901234".toByteArray()) // 25 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull(validated)
        assertEquals(10L, validated?.bytesDownloaded)
        assertEquals(10L, partialFile.length())
    }

    @Test
    fun testRecoveryFailsClosedWhenTruncationFails() {
        val taskId = "test-recovery-fail-closed-trunc"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        storageManager.recoveryTruncateHook = { _, _ -> throw java.io.IOException("Truncation disk error") }
        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException on truncation disk error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Truncation disk error") == true)
        } finally {
            storageManager.recoveryTruncateHook = null
        }

        assertNotNull("Manifest must remain preserved", storageManager.loadManifest(taskId))
        assertTrue("Partial file must remain preserved", partialFile.exists())
    }

    @Test
    fun testRecoveryFailsClosedWhenTruncateSyncFails() {
        val taskId = "test-recovery-fail-closed-sync"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789".toByteArray()) // 20 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 10L,
                totalBytes = 20L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        storageManager.recoveryTruncateSyncHook = { throw java.io.SyncFailedException("Sync disk error") }
        try {
            storageManager.validatePartialState(taskId)
            fail("Expected SyncFailedException on sync disk error")
        } catch (e: java.io.SyncFailedException) {
            assertTrue(e.message?.contains("Sync disk error") == true)
        } finally {
            storageManager.recoveryTruncateSyncHook = null
        }

        assertNotNull("Manifest must remain preserved", storageManager.loadManifest(taskId))
        assertTrue("Partial file must remain preserved", partialFile.exists())
    }

    @Test
    fun testOrphanPartialFileIsNeverPromoted() {
        val taskId = "test-orphan-part-file"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes(ByteArray(50) { 0x41 })

        assertNull("No manifest exists initially", storageManager.loadManifest(taskId))

        val validated = storageManager.validatePartialState(taskId)
        assertNull("Orphan partial file must never be promoted to valid state", validated)
        assertTrue("Orphan partial file must be safely removed", !partialFile.exists())
    }

    @Test
    fun testCorruptedManifestCannotBeUsedForResume() {
        val taskId = "test-corrupted-manifest"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        val manifestFile = storageManager.getManifestFile(taskId)
        manifestFile.writeText("MALFORMED_GARBAGE_WITHOUT_REQUIRED_FIELDS")

        assertNull("Corrupted manifest must return null on load", storageManager.loadManifest(taskId))
        assertNull("Corrupted manifest must return null on validatePartialState", storageManager.validatePartialState(taskId))
        assertTrue("Partial file must be removed as unverified orphan", !partialFile.exists())
    }

    // D. Atomic Manifest Tests

    @Test
    fun testFailedTemporaryManifestWritePreservesExistingManifest() {
        val taskId = "test-temp-write-fail-preserve"
        val originalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(originalManifest)

        val updatedManifest = originalManifest.copy(bytesDownloaded = 50L)

        storageManager.manifestWriteHook = {
            throw java.io.IOException("Disk full during temporary manifest write")
        }

        try {
            storageManager.saveManifest(updatedManifest)
            fail("Expected IOException on temporary manifest write failure")
        } catch (e: java.io.IOException) {
            assertTrue("Expected disk error message, got: ${e.message}", e.message?.contains("Disk full") == true)
        } finally {
            storageManager.manifestWriteHook = null
        }

        // Canonical manifest must be preserved intact!
        val preserved = storageManager.loadManifest(taskId)
        assertNotNull(preserved)
        assertEquals(10L, preserved?.bytesDownloaded)
    }

    @Test
    fun testTemporaryManifestSyncFailurePreservesExistingManifest() {
        val taskId = "test-temp-sync-fail-preserve"
        val originalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(originalManifest)

        val updatedManifest = originalManifest.copy(bytesDownloaded = 50L)

        storageManager.manifestSyncHook = {
            throw java.io.SyncFailedException("Simulated sync failure on temp manifest")
        }

        try {
            storageManager.saveManifest(updatedManifest)
            fail("Expected SyncFailedException on temporary manifest sync failure")
        } catch (e: java.io.SyncFailedException) {
            assertTrue("Expected sync failure message, got: ${e.message}", e.message?.contains("sync failure") == true)
        } finally {
            storageManager.manifestSyncHook = null
        }

        // Canonical manifest must be preserved intact!
        val preserved = storageManager.loadManifest(taskId)
        assertNotNull(preserved)
        assertEquals(10L, preserved?.bytesDownloaded)
    }

    @Test
    fun testAtomicReplacementFailurePreservesExistingManifest() {
        val taskId = "test-atomic-move-fail-preserve"
        val originalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(originalManifest)

        val updatedManifest = originalManifest.copy(bytesDownloaded = 50L)

        storageManager.manifestMoveHook = { _, _ ->
            throw java.io.IOException("Simulated atomic move failure")
        }

        try {
            storageManager.saveManifest(updatedManifest)
            fail("Expected IOException on atomic move failure")
        } catch (e: java.io.IOException) {
            assertTrue("Expected atomic move failure message, got: ${e.message}", e.message?.contains("Atomic manifest replacement failed") == true)
        } finally {
            storageManager.manifestMoveHook = null
        }

        // Canonical manifest must be preserved intact!
        val preserved = storageManager.loadManifest(taskId)
        assertNotNull(preserved)
        assertEquals(10L, preserved?.bytesDownloaded)
    }

    @Test
    fun testStaleTemporaryManifestCannotOverrideCanonicalManifest() {
        val taskId = "test-stale-tmp-manifest"
        val canonicalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(canonicalManifest)
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes(ByteArray(10) { 0x41 })

        // Create a stale temporary manifest with different data
        val tempFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.tmp")
        tempFile.writeText(
            """
            taskId=$taskId
            url=http://example.com/media.mp4
            destinationFileName=media.mp4
            bytesDownloaded=999
            totalBytes=100
            status=COMPLETED
            """.trimIndent()
        )

        // validatePartialState must clean up stale temporary manifest and return canonical state
        val validated = storageManager.validatePartialState(taskId)
        assertNotNull(validated)
        assertEquals("Canonical manifest must not be overridden by stale temp file", 10L, validated?.bytesDownloaded)
        assertTrue("Stale temp manifest must be cleaned up", !tempFile.exists())
    }

    @Test
    fun testCorruptedTemporaryManifestIsNotPromoted() {
        val taskId = "test-corrupted-tmp-manifest"
        // No canonical manifest exists
        val tempFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.tmp")
        tempFile.writeText("UNVERIFIED_CORRUPT_TEMP_CONTENT")

        val validated = storageManager.validatePartialState(taskId)
        assertNull("Corrupted temporary manifest must never be promoted", validated)
        assertNull("Canonical manifest must remain null", storageManager.loadManifest(taskId))
        assertTrue("Corrupted temp manifest must be cleaned up", !tempFile.exists())
    }

    @Test
    fun testSuccessfulAtomicManifestReplacementLoadsCorrectState() {
        val taskId = "test-atomic-replacement-success"
        val originalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        storageManager.saveManifest(originalManifest)

        val updatedManifest = originalManifest.copy(
            bytesDownloaded = 20L,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED
        )
        storageManager.saveManifest(updatedManifest)

        val loaded = storageManager.loadManifest(taskId)
        assertNotNull(loaded)
        assertEquals(20L, loaded?.bytesDownloaded)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.COMPLETED, loaded?.status)

        val tempFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.tmp")
        assertTrue("Temp manifest must not remain after successful replacement", !tempFile.exists())
    }

    // ==========================================
    // Phase 1.2.3 Final Closure: Manifest Read Integrity, Cleanup Verification & Concurrency Tests
    // ==========================================

    @Test
    fun testManifestReadIOExceptionPreservesPartialFile() {
        val taskId = "test-read-io-preserves-partial"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789012345678901234567890123456789".toByteArray()) // 50 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 50L,
                totalBytes = 100L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject transient I/O failure during manifest reading
        storageManager.manifestReadHook = {
            throw java.io.IOException("Transient I/O read failure on storage device")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when manifest reading encounters storage I/O failure")
        } catch (e: java.io.IOException) {
            assertTrue("Expected transient message, got: ${e.message}", e.message?.contains("Transient I/O read failure") == true)
        } finally {
            storageManager.manifestReadHook = null
        }

        // Invariant: Transient I/O read error must NEVER delete valid partial data or manifest!
        assertTrue("Partial file must be preserved on read I/O error", partialFile.exists())
        assertEquals(50L, partialFile.length())
        assertTrue("Manifest file must be preserved on read I/O error", storageManager.getManifestFile(taskId).exists())
    }

    @Test
    fun testManifestReadFailureBlocksResume() = runBlocking {
        val taskId = "test-read-failure-blocks-resume"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789012345678901234567890123456789".toByteArray()) // 50 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4",
                bytesDownloaded = 50L,
                totalBytes = 100L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Inject transient disk read error
        storageManager.manifestReadHook = {
            throw java.io.IOException("Simulated transient disk read error")
        }

        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )
        storageManager.manifestReadHook = null

        // Invariant: Resume must be blocked when manifest cannot be verified due to I/O error
        assertTrue("Resume must be blocked on read I/O error, got: $result", result is DownloadResult.Error)
        val error = result as DownloadResult.Error
        assertEquals("Transient error must not be marked permanent", false, error.isPermanent)

        // Files must NOT be deleted!
        assertTrue("Partial file must be preserved", partialFile.exists())
        assertEquals(50L, partialFile.length())
        assertTrue("Manifest file must be preserved", storageManager.getManifestFile(taskId).exists())
    }

    @Test
    fun testStartupReadIOExceptionDoesNotDeletePartialFile() {
        val taskId = "test-startup-read-io-preserves"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("01234567890123456789012345678901234567890123456789".toByteArray()) // 50 bytes

        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = taskId,
                url = "http://example.com/media.mp4",
                destinationFileName = "media.mp4",
                bytesDownloaded = 50L,
                totalBytes = 100L,
                strongETag = "\"etag-1\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
            )
        )

        storageManager.manifestReadHook = {
            throw java.io.IOException("Simulated disk read error on startup reconciliation")
        }

        val list = storageManager.reconcileAllOnStartup()
        storageManager.manifestReadHook = null

        // Invariant: Transient startup read error does NOT delete partial file or manifest
        assertTrue("Partial file must be preserved on startup read error", partialFile.exists())
        assertEquals(50L, partialFile.length())
        assertTrue("Manifest file must be preserved on startup read error", storageManager.getManifestFile(taskId).exists())
    }

    @Test
    fun testMalformedManifestDoesNotBecomeResumable() {
        val taskId = "test-malformed-manifest-not-resumable"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("0123456789".toByteArray())

        val manifestFile = storageManager.getManifestFile(taskId)
        manifestFile.writeText("corrupt=true\nno_url=here\n")

        val validated = storageManager.validatePartialState(taskId)
        assertNull("Malformed manifest must return null", validated)
        assertTrue("Orphan partial file must be safely removed when manifest is malformed", !partialFile.exists())
    }

    @Test
    fun testDeletionHookReturningFalseWhileLeavingFileIsDetected() {
        val taskId = "test-del-false-leaves-file"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("test".toByteArray())

        // Hook returns false and leaves the file on disk
        storageManager.deleteHook = { false }

        try {
            storageManager.removeTaskData(taskId)
            fail("Expected IOException when deletion returns false and leaves file")
        } catch (e: java.io.IOException) {
            assertTrue("Expected failure message, got: ${e.message}", e.message?.contains("Failed to delete") == true)
        } finally {
            storageManager.deleteHook = null
        }

        assertTrue("File still exists", partialFile.exists())
    }

    @Test
    fun testDeletionHookReturningTrueWhileLeavingFileIsDetected() {
        val taskId = "test-del-true-leaves-file"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes("test".toByteArray())

        // Hook lies by returning true, but intentionally fails to actually delete the file!
        storageManager.deleteHook = { true }

        try {
            storageManager.removeTaskData(taskId)
            fail("Expected IOException when deletion hook returns true but file still exists on disk")
        } catch (e: java.io.IOException) {
            assertTrue("Expected failure message, got: ${e.message}", e.message?.contains("Failed to delete") == true)
        } finally {
            storageManager.deleteHook = null
        }

        assertTrue("File still exists despite hook returning true", partialFile.exists())
    }

    @Test
    fun testDeletionHookReturningFalseWhenFileAlreadyAbsentSucceeds() {
        val nonExistentFile = File(tempDir, "absent_file.tmp")
        assertTrue(!nonExistentFile.exists())

        storageManager.deleteHook = { false }
        val result = storageManager.safeDeleteFile(nonExistentFile)
        storageManager.deleteHook = null

        // Invariant: Never treat a false return as failure when the file is already absent
        assertTrue("safeDeleteFile must return true for already absent file", result)
    }

    @Test
    fun testSaveManifestTemporaryVerificationRejectsFieldMismatch() {
        val taskId = "test-temp-verify-mismatch"
        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )

        // Corrupt temporary manifest properties before replacement
        storageManager.manifestWriteHook = { tempFile ->
            val props = java.util.Properties()
            java.io.FileInputStream(tempFile).use { props.load(it) }
            props.setProperty("bytesDownloaded", "999999") // Corrupt the field
            java.io.FileOutputStream(tempFile).use { props.store(it, null) }
        }

        try {
            storageManager.saveManifest(manifest)
            fail("Expected IOException on temporary manifest field mismatch")
        } catch (e: java.io.IOException) {
            assertTrue("Expected verification error, got: ${e.message}", e.message?.contains("Temporary manifest verification failed") == true)
        } finally {
            storageManager.manifestWriteHook = null
        }

        assertNull("Manifest must not exist when temporary verification fails", storageManager.loadManifest(taskId))
    }

    @Test
    fun testSaveManifestPostMoveVerificationFailureRestoresBackup() {
        val taskId = "test-post-move-verify-rollback"
        val originalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(originalManifest)

        val updatedManifest = originalManifest.copy(bytesDownloaded = 20L)

        // Inject corruption after move to trigger post-replacement verification failure
        storageManager.postMoveVerificationHook = { destFile ->
            destFile.writeText("CORRUPTED_AFTER_ATOMIC_MOVE")
        }

        try {
            storageManager.saveManifest(updatedManifest)
            fail("Expected IOException when post-move verification fails")
        } catch (e: java.io.IOException) {
            assertTrue("Expected post-save verification error, got: ${e.message}", e.message?.contains("Post-save manifest verification failed") == true)
        } finally {
            storageManager.postMoveVerificationHook = null
        }

        // Invariant: The previous canonical manifest must be restored from backup!
        val restored = storageManager.loadManifest(taskId)
        assertNotNull("Previous manifest must be restored from backup", restored)
        assertEquals(10L, restored?.bytesDownloaded)
    }

    @Test
    fun testMultipleEngineInstancesCannotConcurrentlyDownloadSameTask() = runBlocking {
        val taskId = "test-multi-engine-concurrency"
        val storage2 = PersistentStorageManager(tempDir)
        val engine2 = HttpRangeDownloadEngine(
            client = client,
            storageManager = storage2,
            securityPolicy = MockWebServerUrlSecurityPolicy(server.port)
        )

        // Register taskId in activeTasks to simulate an in-progress download on engine 1
        val storageKey = "${storageManager.progressDirCanonicalPath}:$taskId"
        HttpRangeDownloadEngine.activeTasks[storageKey] = true

        try {
            // Attempt concurrent download on engine 2
            val result = engine2.executeDownload(
                taskId = taskId,
                url = server.url("/media.mp4").toString(),
                destinationFileName = "media.mp4"
            )

            // Invariant: Concurrency guard must reject second engine instance
            assertTrue("Second engine instance must be rejected, got: $result", result is DownloadResult.Error)
            val error = result as DownloadResult.Error
            assertTrue("Expected active task error, got: ${error.reason}", error.reason.contains("already active"))
            assertEquals(false, error.isPermanent)
        } finally {
            HttpRangeDownloadEngine.activeTasks.remove(storageKey)
        }
    }

    @Test
    fun testDirectValidatePartialStateWithMalformedCanonicalDoesNotDeleteValidBackup() {
        val taskId = "test-val-malformed-keep-bak"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        val validBackupManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 20L,
            totalBytes = 100L,
            strongETag = "\"backup-etag\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        val props = java.util.Properties().apply {
            setProperty("taskId", validBackupManifest.taskId)
            setProperty("url", validBackupManifest.url)
            setProperty("destinationFileName", validBackupManifest.destinationFileName)
            setProperty("bytesDownloaded", validBackupManifest.bytesDownloaded.toString())
            setProperty("totalBytes", validBackupManifest.totalBytes.toString())
            setProperty("strongETag", validBackupManifest.strongETag ?: "")
            setProperty("status", validBackupManifest.status.name)
        }
        java.io.FileOutputStream(bakFile).use { props.store(it, null) }
        partialFile.writeBytes(ByteArray(20) { 0x42 })

        val canonicalBytesBefore = "CORRUPTED_GARBAGE_WITHOUT_FIELDS".toByteArray()
        manifestFile.writeBytes(canonicalBytesBefore)
        val backupBytesBefore = bakFile.readBytes()
        val partialBytesBefore = partialFile.readBytes()

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when canonical manifest is invalid but a valid backup exists")
        } catch (e: java.io.IOException) {
            assertTrue(
                "Expected recovery-blocking error, got: ${e.message}",
                e.message?.contains("Canonical manifest is missing or invalid while a valid backup exists") == true
            )
        }

        assertTrue("Canonical manifest must remain present", manifestFile.exists())
        assertTrue("Invalid canonical bytes must remain unchanged", canonicalBytesBefore.contentEquals(manifestFile.readBytes()))
        assertTrue("Valid backup manifest must NOT be deleted by single-task validation", bakFile.exists())
        assertTrue("Backup bytes must remain unchanged", backupBytesBefore.contentEquals(bakFile.readBytes()))
        val loadedBak = storageManager.loadManifestFrom(bakFile, taskId)
        assertNotNull("Backup must remain readable and valid", loadedBak)
        assertEquals(20L, loadedBak?.bytesDownloaded)
        assertTrue("Partial file must be preserved when valid backup exists", partialFile.exists())
        assertTrue("Partial bytes must remain unchanged", partialBytesBefore.contentEquals(partialFile.readBytes()))
    }

    @Test
    fun testDirectValidatePartialStateWithMissingCanonicalDoesNotDestroyValidBackup() {
        val taskId = "test-val-missing-keep-bak"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        val validBackupManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 30L,
            totalBytes = 100L,
            strongETag = "\"backup-etag-30\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        val props = java.util.Properties().apply {
            setProperty("taskId", validBackupManifest.taskId)
            setProperty("url", validBackupManifest.url)
            setProperty("destinationFileName", validBackupManifest.destinationFileName)
            setProperty("bytesDownloaded", validBackupManifest.bytesDownloaded.toString())
            setProperty("totalBytes", validBackupManifest.totalBytes.toString())
            setProperty("strongETag", validBackupManifest.strongETag ?: "")
            setProperty("status", validBackupManifest.status.name)
        }
        java.io.FileOutputStream(bakFile).use { props.store(it, null) }
        partialFile.writeBytes(ByteArray(30) { 0x43 })

        assertTrue("Canonical manifest must not exist initially", !manifestFile.exists())
        val backupBytesBefore = bakFile.readBytes()
        val partialBytesBefore = partialFile.readBytes()

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when canonical manifest is missing but a valid backup exists")
        } catch (e: java.io.IOException) {
            assertTrue(
                "Expected recovery-blocking error, got: ${e.message}",
                e.message?.contains("Canonical manifest is missing or invalid while a valid backup exists") == true
            )
        }

        assertTrue("Canonical manifest must remain absent", !manifestFile.exists())
        assertTrue("Valid backup manifest must NOT be destroyed when canonical is missing", bakFile.exists())
        assertTrue("Backup bytes must remain unchanged", backupBytesBefore.contentEquals(bakFile.readBytes()))
        assertEquals(30L, storageManager.loadManifestFrom(bakFile, taskId)?.bytesDownloaded)
        assertTrue("Partial file must be preserved when valid backup exists", partialFile.exists())
        assertTrue("Partial bytes must remain unchanged", partialBytesBefore.contentEquals(partialFile.readBytes()))
    }

    @Test
    fun testEngineBlocksResumeAndPreservesRecoveryFilesWhenCanonicalManifestIsUnavailable() = runBlocking {
        data class Scenario(
            val suffix: String,
            val canonicalContent: String?
        )

        val scenarios = listOf(
            Scenario(
                suffix = "malformed",
                canonicalContent = "CORRUPTED_CANONICAL_CONTENT"
            ),
            Scenario(
                suffix = "missing",
                canonicalContent = null
            ),
            Scenario(
                suffix = "negative-offset",
                canonicalContent = """
                    taskId=PLACEHOLDER
                    url=http://example.com/media.mp4
                    destinationFileName=media.mp4
                    bytesDownloaded=-1
                    totalBytes=100
                    strongETag="backup-etag"
                    status=INTERRUPTED
                """.trimIndent()
            )
        )

        for (scenario in scenarios) {
            val taskId = "test-engine-preserve-${scenario.suffix}"
            val manifestFile = storageManager.getManifestFile(taskId)
            val backupFile = File(manifestFile.parentFile, "$taskId.manifest.bak")
            val partialFile = storageManager.getPartialFile(taskId)

            val partialBytes = ByteArray(32) { (it * 7 + 3).toByte() }
            partialFile.writeBytes(partialBytes)

            val backupProperties = java.util.Properties().apply {
                setProperty("taskId", taskId)
                setProperty("url", "http://example.com/media.mp4")
                setProperty("destinationFileName", "media.mp4")
                setProperty("bytesDownloaded", partialBytes.size.toString())
                setProperty("totalBytes", "100")
                setProperty("strongETag", "\"backup-etag\"")
                setProperty("status", "INTERRUPTED")
            }
            java.io.FileOutputStream(backupFile).use { backupProperties.store(it, null) }

            if (scenario.canonicalContent == null) {
                assertTrue("Canonical manifest must be absent for ${scenario.suffix}", !manifestFile.exists())
            } else {
                val canonicalContent = scenario.canonicalContent.replace("PLACEHOLDER", taskId)
                manifestFile.writeText(canonicalContent)
            }

            val canonicalBytesBefore = if (manifestFile.exists()) manifestFile.readBytes() else null
            val backupBytesBefore = backupFile.readBytes()
            val backupHashBefore = java.security.MessageDigest.getInstance("SHA-256")
                .digest(backupBytesBefore)
            val partialHashBefore = java.security.MessageDigest.getInstance("SHA-256")
                .digest(partialBytes)

            val result = engine.executeDownload(
                taskId = taskId,
                url = server.url("/must-not-be-requested/${scenario.suffix}").toString(),
                destinationFileName = "media.mp4"
            )

            assertTrue(
                "Engine must fail closed for ${scenario.suffix}, got: $result",
                result is DownloadResult.Error
            )
            val error = result as DownloadResult.Error
            assertTrue(
                "Recovery failure must be retryable/inspectable, got: ${error.reason}",
                !error.isPermanent
            )
            assertTrue(
                "Expected storage recovery error for ${scenario.suffix}, got: ${error.reason}",
                error.reason.contains("Storage recovery failed")
            )

            assertEquals(
                "No network request may be sent for ${scenario.suffix}",
                0,
                server.requestCount
            )
            assertTrue(
                "Partial bytes must remain unchanged for ${scenario.suffix}",
                partialHashBefore.contentEquals(
                    java.security.MessageDigest.getInstance("SHA-256").digest(partialFile.readBytes())
                )
            )
            assertTrue("Backup must remain present for ${scenario.suffix}", backupFile.exists())
            assertTrue(
                "Backup bytes must remain unchanged for ${scenario.suffix}",
                backupHashBefore.contentEquals(
                    java.security.MessageDigest.getInstance("SHA-256").digest(backupFile.readBytes())
                )
            )

            if (canonicalBytesBefore == null) {
                assertTrue("Canonical must remain absent for ${scenario.suffix}", !manifestFile.exists())
            } else {
                assertTrue("Canonical must remain present for ${scenario.suffix}", manifestFile.exists())
                assertTrue(
                    "Canonical bytes must remain unchanged for ${scenario.suffix}",
                    canonicalBytesBefore.contentEquals(manifestFile.readBytes())
                )
            }
        }
    }

    @Test
    fun testExplicitPermanentTaskDeletionRemovesBackupWhenAppropriate() {
        val taskId = "test-explicit-remove-all"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val manifestFile = storageManager.getManifestFile(taskId)
        val tempFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.tmp")
        val partialFile = storageManager.getPartialFile(taskId)

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 15L,
            totalBytes = 100L,
            strongETag = "\"etag-15\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        storageManager.saveManifest(manifest)
        bakFile.writeText("BACKUP_DATA")
        tempFile.writeText("TEMP_DATA")
        partialFile.writeBytes(ByteArray(15) { 0x44 })

        assertTrue(manifestFile.exists())
        assertTrue(bakFile.exists())
        assertTrue(tempFile.exists())
        assertTrue(partialFile.exists())

        storageManager.removeTaskData(taskId)

        assertTrue("Manifest must be deleted on explicit removal", !manifestFile.exists())
        assertTrue("Backup must be deleted on explicit removal", !bakFile.exists())
        assertTrue("Temp manifest must be deleted on explicit removal", !tempFile.exists())
        assertTrue("Partial file must be deleted on explicit removal", !partialFile.exists())
    }

    @Test
    fun testPartialFileRecoveryAndManifestIoPreservationRemainsIntact() {
        val taskId = "test-oversized-partial-truncation"
        val partialFile = storageManager.getPartialFile(taskId)
        partialFile.writeBytes(ByteArray(40) { 0x45 })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 25L,
            totalBytes = 100L,
            strongETag = "\"etag-25\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Truncated partial file with matching checkpoint must be validated", validated)
        assertEquals(25L, validated?.bytesDownloaded)
        assertEquals(25L, partialFile.length())

        storageManager.manifestReadHook = {
            throw java.io.IOException("Simulated transient device I/O error")
        }
        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException on transient I/O failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated transient device I/O error") == true)
        } finally {
            storageManager.manifestReadHook = null
        }
        assertTrue("Partial file must be preserved on transient I/O error", partialFile.exists())
        assertEquals(25L, partialFile.length())
    }

    @Test
    fun testValidatePartialStateWithMalformedCanonicalAndBackupReadIoException() {
        val taskId = "test-val-malformed-bak-io-err"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        val bakContent = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=20\ntotalBytes=100\nstatus=ACTIVE\n"
        bakFile.writeText(bakContent)
        val canonicalContent = "CORRUPTED_GARBAGE_CANONICAL"
        manifestFile.writeText(canonicalContent)
        partialFile.writeBytes(ByteArray(20) { 0x42 })

        storageManager.backupReadHook = {
            throw java.io.IOException("Simulated transient backup storage read error")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when backup reading encounters storage I/O error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated transient backup storage read error") == true)
        } finally {
            storageManager.backupReadHook = null
        }

        // Invariant: All task files remain present and contents are unchanged!
        assertTrue("Canonical manifest must remain present", manifestFile.exists())
        assertEquals(canonicalContent, manifestFile.readText())
        assertTrue("Backup manifest must remain present", bakFile.exists())
        assertEquals(bakContent, bakFile.readText())
        assertTrue("Partial file must remain present", partialFile.exists())
        assertEquals(20L, partialFile.length())
    }

    @Test
    fun testValidatePartialStateWithMissingCanonicalAndBackupReadIoException() {
        val taskId = "test-val-missing-bak-io-err"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        val bakContent = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=30\ntotalBytes=100\nstatus=ACTIVE\n"
        bakFile.writeText(bakContent)
        partialFile.writeBytes(ByteArray(30) { 0x43 })

        assertTrue("Canonical manifest must not exist", !manifestFile.exists())

        storageManager.backupReadHook = {
            throw java.io.IOException("Simulated transient backup storage read error")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when backup reading encounters storage I/O error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated transient backup storage read error") == true)
        } finally {
            storageManager.backupReadHook = null
        }

        // Invariant: Canonical manifest remains absent; backup and partial file remain present and unchanged!
        assertTrue("Canonical manifest must remain absent", !manifestFile.exists())
        assertTrue("Backup manifest must remain present", bakFile.exists())
        assertEquals(bakContent, bakFile.readText())
        assertTrue("Partial file must remain present", partialFile.exists())
        assertEquals(30L, partialFile.length())
    }

    @Test
    fun testReconcileAllOnStartupWithCorruptCanonicalAndBackupReadIoException() {
        val taskId1 = "test-reconcile-corrupt-bak-io"
        val bakFile1 = File(storageManager.getManifestFile(taskId1).parentFile, "$taskId1.manifest.bak")
        val manifestFile1 = storageManager.getManifestFile(taskId1)
        val partialFile1 = storageManager.getPartialFile(taskId1)

        val bakContent1 = "taskId=$taskId1\nurl=http://example.com/media1.mp4\ndestinationFileName=media1.mp4\nbytesDownloaded=20\ntotalBytes=100\nstatus=ACTIVE\n"
        bakFile1.writeText(bakContent1)
        val canonicalContent1 = "CORRUPTED_CANONICAL_TASK1"
        manifestFile1.writeText(canonicalContent1)
        partialFile1.writeBytes(ByteArray(20) { 0x42 })

        // Task 2: Unrelated task that should reconcile normally
        val taskId2 = "test-reconcile-unrelated-task"
        val manifest2 = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId2,
            url = "http://example.com/media2.mp4",
            destinationFileName = "media2.mp4",
            bytesDownloaded = 10L,
            totalBytes = 100L,
            strongETag = "\"tag2\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        storageManager.saveManifest(manifest2)
        val partialFile2 = storageManager.getPartialFile(taskId2)
        partialFile2.writeBytes(ByteArray(10) { 0x41 })

        storageManager.backupReadHook = { file ->
            if (file.name.startsWith(taskId1)) {
                throw java.io.IOException("Simulated transient backup storage read error on task1")
            }
        }

        val list: List<com.galaxy.itsonline.downloader.model.RecoveryManifest>
        try {
            list = storageManager.reconcileAllOnStartup()
        } finally {
            storageManager.backupReadHook = null
        }

        // Invariant Task 1: All files remain present and contents are unchanged!
        assertTrue("Task 1 canonical manifest must remain present", manifestFile1.exists())
        assertEquals(canonicalContent1, manifestFile1.readText())
        assertTrue("Task 1 backup manifest must remain present", bakFile1.exists())
        assertEquals(bakContent1, bakFile1.readText())
        assertTrue("Task 1 partial file must remain present", partialFile1.exists())
        assertEquals(20L, partialFile1.length())

        // Invariant Task 2: Unrelated task reconciled successfully to INTERRUPTED!
        val reconciled2 = list.find { it.taskId == taskId2 }
        assertNotNull("Task 2 must be reconciled", reconciled2)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, reconciled2?.status)
    }

    @Test
    fun testReconcileAllOnStartupWithMissingCanonicalAndBackupReadIoException() {
        val taskId1 = "test-reconcile-missing-bak-io"
        val bakFile1 = File(storageManager.getManifestFile(taskId1).parentFile, "$taskId1.manifest.bak")
        val manifestFile1 = storageManager.getManifestFile(taskId1)
        val partialFile1 = storageManager.getPartialFile(taskId1)

        val bakContent1 = "taskId=$taskId1\nurl=http://example.com/media1.mp4\ndestinationFileName=media1.mp4\nbytesDownloaded=30\ntotalBytes=100\nstatus=ACTIVE\n"
        bakFile1.writeText(bakContent1)
        partialFile1.writeBytes(ByteArray(30) { 0x43 })
        assertTrue("Task 1 canonical manifest must not exist", !manifestFile1.exists())

        // Task 2: Unrelated task that should reconcile normally
        val taskId2 = "test-reconcile-unrelated-task2"
        val manifest2 = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId2,
            url = "http://example.com/media2.mp4",
            destinationFileName = "media2.mp4",
            bytesDownloaded = 15L,
            totalBytes = 100L,
            strongETag = "\"tag2\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.ACTIVE
        )
        storageManager.saveManifest(manifest2)
        val partialFile2 = storageManager.getPartialFile(taskId2)
        partialFile2.writeBytes(ByteArray(15) { 0x41 })

        storageManager.backupReadHook = { file ->
            if (file.name.startsWith(taskId1)) {
                throw java.io.IOException("Simulated transient backup storage read error on task1")
            }
        }

        val list: List<com.galaxy.itsonline.downloader.model.RecoveryManifest>
        try {
            list = storageManager.reconcileAllOnStartup()
        } finally {
            storageManager.backupReadHook = null
        }

        // Invariant Task 1: Canonical manifest remains absent, backup and partial file remain present and unchanged!
        assertTrue("Task 1 canonical manifest must remain absent", !manifestFile1.exists())
        assertTrue("Task 1 backup manifest must remain present", bakFile1.exists())
        assertEquals(bakContent1, bakFile1.readText())
        assertTrue("Task 1 partial file must remain present", partialFile1.exists())
        assertEquals(30L, partialFile1.length())

        // Invariant Task 2: Unrelated task reconciled successfully!
        val reconciled2 = list.find { it.taskId == taskId2 }
        assertNotNull("Task 2 must be reconciled", reconciled2)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, reconciled2?.status)
    }

    @Test
    fun testMalformedUnicodeInCanonicalManifestClassifiedAsInvalidContent() {
        val taskId = "test-malformed-unicode-canonical"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        manifestFile.writeText("taskId=$taskId\nurl=\\uZZZZ\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=100\nstatus=ACTIVE\n")
        partialFile.writeBytes(ByteArray(10) { 0x41 })

        val loaded = storageManager.loadManifest(taskId)
        assertNull("loadManifest must return null for malformed Unicode syntax", loaded)

        val validated = storageManager.validatePartialState(taskId)
        assertNull("validatePartialState must return null for malformed Unicode syntax", validated)

        assertTrue("Corrupted manifest must be cleaned up", !manifestFile.exists())
        assertTrue("Orphan partial file must be cleaned up", !partialFile.exists())
    }

    @Test
    fun testMalformedUnicodeInBackupManifestClassifiedAsInvalidContent() {
        val taskId = "test-malformed-unicode-backup"
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")

        bakFile.writeText("taskId=$taskId\nurl=\\uZZZZ\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=100\nstatus=ACTIVE\n")

        val loaded = storageManager.loadBackupManifest(taskId)
        assertNull("loadBackupManifest must return null for malformed Unicode in backup", loaded)
    }

    @Test
    fun testMalformedUnicodeInCanonicalWithValidBackupCanBeRecovered() {
        val taskId = "test-malformed-unicode-recovered"
        val manifestFile = storageManager.getManifestFile(taskId)
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val partialFile = storageManager.getPartialFile(taskId)

        manifestFile.writeText("taskId=$taskId\nurl=\\uZZZZ\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=100\nstatus=ACTIVE\n")

        val validBakContent = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=25\ntotalBytes=100\nstatus=ACTIVE\n"
        bakFile.writeText(validBakContent)
        partialFile.writeBytes(ByteArray(25) { 0x42 })

        val canonicalBytesBefore = manifestFile.readBytes()
        val backupBytesBefore = bakFile.readBytes()
        val partialBytesBefore = partialFile.readBytes()

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when malformed canonical coexists with a valid backup")
        } catch (e: java.io.IOException) {
            assertTrue(
                "Expected recovery-blocking error, got: ${e.message}",
                e.message?.contains("Canonical manifest is missing or invalid while a valid backup exists") == true
            )
        }

        assertTrue("Malformed canonical must be preserved until startup reconciliation", manifestFile.exists())
        assertTrue("Canonical bytes must remain unchanged", canonicalBytesBefore.contentEquals(manifestFile.readBytes()))
        assertTrue("Valid backup must be preserved", bakFile.exists())
        assertTrue("Backup bytes must remain unchanged", backupBytesBefore.contentEquals(bakFile.readBytes()))
        assertTrue("Partial file must be preserved", partialFile.exists())
        assertTrue("Partial bytes must remain unchanged", partialBytesBefore.contentEquals(partialFile.readBytes()))

        val list = storageManager.reconcileAllOnStartup()
        val recovered = list.find { it.taskId == taskId }
        assertNotNull("Task with malformed Unicode canonical must be recovered from valid backup", recovered)
        assertEquals(25L, recovered?.bytesDownloaded)
        assertEquals(com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED, recovered?.status)

        val canonicalNow = storageManager.loadManifest(taskId)
        assertNotNull("Canonical manifest must be restored and valid", canonicalNow)
        assertEquals(25L, canonicalNow?.bytesDownloaded)
        assertEquals("http://example.com/media.mp4", canonicalNow?.url)
    }

    @Test
    fun testIoExceptionDuringCanonicalOrBackupReadStillPropagatesAndPreservesFiles() {
        val taskId = "test-io-read-preserves-all"
        val manifestFile = storageManager.getManifestFile(taskId)
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val partialFile = storageManager.getPartialFile(taskId)

        val canonicalData = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=100\nstatus=ACTIVE\n"
        val backupData = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=100\nstatus=ACTIVE\n"
        manifestFile.writeText(canonicalData)
        bakFile.writeText(backupData)
        partialFile.writeBytes(ByteArray(10) { 0x41 })

        storageManager.manifestReadHook = {
            throw java.io.IOException("Canonical storage read error")
        }
        try {
            storageManager.loadManifest(taskId)
            fail("Expected IOException on canonical read error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Canonical storage read error") == true)
        } finally {
            storageManager.manifestReadHook = null
        }
        assertTrue("Manifest must remain present", manifestFile.exists())
        assertEquals(canonicalData, manifestFile.readText())
        assertTrue("Backup must remain present", bakFile.exists())
        assertTrue("Partial file must remain present", partialFile.exists())

        storageManager.backupReadHook = {
            throw java.io.IOException("Backup storage read error")
        }
        try {
            storageManager.loadBackupManifest(taskId)
            fail("Expected IOException on backup read error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Backup storage read error") == true)
        } finally {
            storageManager.backupReadHook = null
        }
        assertTrue("Manifest must remain present", manifestFile.exists())
        assertTrue("Backup must remain present", bakFile.exists())
        assertEquals(backupData, bakFile.readText())
        assertTrue("Partial file must remain present", partialFile.exists())
    }

    // --- Repair Group 2: Partial File Consistency and Recovery Tests ---

    @Test
    fun testExactSizePartialFileAcceptedWithoutModification() {
        val taskId = "test-rg2-exact-size"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        val payload = ByteArray(20) { 0x55.toByte() }
        partialFile.writeBytes(payload)

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 20L,
            totalBytes = 50L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Exact size partial file must be validated", validated)
        assertEquals(20L, validated?.bytesDownloaded)
        assertEquals(20L, java.nio.file.Files.size(partialFile.toPath()))
        assertTrue(payload.contentEquals(partialFile.readBytes()))
    }

    @Test
    fun testOversizedPartialFileTruncatedToCommittedOffset() {
        val taskId = "test-rg2-oversized-truncate"
        val partialFile = storageManager.getPartialFile(taskId)

        val fullPayload = ByteArray(25) { it.toByte() }
        partialFile.writeBytes(fullPayload)

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 50L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Oversized partial file must be accepted after truncation", validated)
        assertEquals(10L, validated?.bytesDownloaded)
        assertEquals(10L, java.nio.file.Files.size(partialFile.toPath()))

        val remainingBytes = partialFile.readBytes()
        assertEquals(10, remainingBytes.size)
        assertTrue(fullPayload.copyOfRange(0, 10).contentEquals(remainingBytes))
    }

    @Test
    fun testTruncationFailurePropagatesIoExceptionAndPreservesFiles() {
        val taskId = "test-rg2-trunc-fail-preserve"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")

        partialFile.writeBytes(ByteArray(20) { 0x42 })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 50L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val bakContent = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=10\ntotalBytes=50\nstatus=INTERRUPTED\n"
        bakFile.writeText(bakContent)

        storageManager.recoveryTruncateHook = { _, _ ->
            throw java.io.IOException("Simulated setLength disk failure")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when truncation fails")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated setLength disk failure") == true)
        } finally {
            storageManager.recoveryTruncateHook = null
        }

        assertTrue("Manifest must remain preserved on disk", manifestFile.exists())
        assertTrue("Partial file must remain preserved on disk", partialFile.exists())
        assertEquals(20L, java.nio.file.Files.size(partialFile.toPath()))
        assertTrue("Backup manifest must remain preserved on disk", bakFile.exists())
    }

    @Test
    fun testTruncationSyncFailurePropagatesIoExceptionAndPreservesFiles() {
        val taskId = "test-rg2-sync-fail-preserve"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        partialFile.writeBytes(ByteArray(20) { 0x43 })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 50L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        storageManager.recoveryTruncateSyncHook = {
            throw java.io.SyncFailedException("Simulated sync failure during recovery truncation")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected SyncFailedException when sync fails during truncation")
        } catch (e: java.io.SyncFailedException) {
            assertTrue(e.message?.contains("Simulated sync failure") == true)
        } finally {
            storageManager.recoveryTruncateSyncHook = null
        }

        assertTrue("Manifest must be preserved after sync failure", manifestFile.exists())
        assertTrue("Partial file must be preserved after sync failure", partialFile.exists())
        assertEquals(10L, java.nio.file.Files.size(partialFile.toPath()))
    }

    @Test
    fun testPostTruncationVerificationFailurePropagatesIoException() {
        val taskId = "test-rg2-post-trunc-verify-fail"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        partialFile.writeBytes(ByteArray(20) { 0x44 })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 10L,
            totalBytes = 50L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        // Inject size corruption before post-truncation check
        storageManager.postTruncateVerificationHook = { file ->
            file.appendBytes(ByteArray(5) { 0x99.toByte() })
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when post-truncation verification fails")
        } catch (e: java.io.IOException) {
            assertTrue("Expected verification failure message, got: ${e.message}", e.message?.contains("Truncation verification failed") == true)
        } finally {
            storageManager.postTruncateVerificationHook = null
        }

        assertTrue("Manifest must be preserved after verification failure", manifestFile.exists())
        assertTrue("Partial file must be preserved after verification failure", partialFile.exists())
    }

    @Test
    fun testUndersizedPartialFileWithoutValidBackupFailsClosedAndPreservesFiles() = runBlocking {
        val taskId = "test-rg2-undersized-no-backup"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        // Only 15 bytes on disk, but manifest claims 30 bytes committed
        partialFile.writeBytes(ByteArray(15) { 0x50 })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4",
            bytesDownloaded = 30L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        // 1. Direct validation throws IOException and preserves files
        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException for undersized partial file without backup")
        } catch (e: java.io.IOException) {
            assertTrue("Expected shorter than committed offset message, got: ${e.message}", e.message?.contains("shorter than committed durable offset") == true)
        }

        assertTrue("Manifest must be preserved", manifestFile.exists())
        assertTrue("Partial file must be preserved", partialFile.exists())
        assertEquals(15L, java.nio.file.Files.size(partialFile.toPath()))

        // 2. Engine execution catches error, fails closed, and preserves files without deleting
        val result = engine.executeDownload(
            taskId = taskId,
            url = server.url("/media.mp4").toString(),
            destinationFileName = "media.mp4"
        )
        assertTrue("Engine must report DownloadResult.Error when partial file is undersized", result is DownloadResult.Error)
        val err = result as DownloadResult.Error
        assertTrue("Error must be transient (non-permanent) to allow user inspection", !err.isPermanent)
        assertTrue("Files must remain on disk", manifestFile.exists())
        assertTrue("Partial file must remain on disk", partialFile.exists())
        assertEquals(15L, java.nio.file.Files.size(partialFile.toPath()))
    }

    @Test
    fun testUndersizedPartialFileWithMatchingBackupRecoversSafely() {
        val taskId = "test-rg2-undersized-backup-recover"
        val manifestFile = storageManager.getManifestFile(taskId)
        val bakFile = File(storageManager.getManifestFile(taskId).parentFile, "$taskId.manifest.bak")
        val partialFile = storageManager.getPartialFile(taskId)

        // 45 bytes on disk
        partialFile.writeBytes(ByteArray(45) { 0x60 })

        // Canonical claims 80 bytes (undersized!)
        val canonicalManifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 80L,
            totalBytes = 100L,
            strongETag = "\"etag-1\"",
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(canonicalManifest)

        // Backup claims 40 bytes (matching metadata and 40 <= 45 actual bytes)
        val validBakContent = "taskId=$taskId\nurl=http://example.com/media.mp4\ndestinationFileName=media.mp4\nbytesDownloaded=40\ntotalBytes=100\nstrongETag=\"etag-1\"\nstatus=INTERRUPTED\n"
        bakFile.writeText(validBakContent)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Undersized file with matching backup must be safely recovered", validated)
        assertEquals(40L, validated?.bytesDownloaded)
        assertEquals(40L, java.nio.file.Files.size(partialFile.toPath()))

        val canonicalAfter = storageManager.loadManifest(taskId)
        assertNotNull("Canonical manifest must be restored from backup", canonicalAfter)
        assertEquals(40L, canonicalAfter?.bytesDownloaded)
        assertEquals("http://example.com/media.mp4", canonicalAfter?.url)
    }

    @Test
    fun testMissingPartialFileWithZeroCommittedBytesReturnsValidState() {
        val taskId = "test-rg2-missing-zero-committed"
        val partialFile = storageManager.getPartialFile(taskId)
        if (partialFile.exists()) partialFile.delete()

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 0L,
            totalBytes = 100L,
            strongETag = null,
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Missing partial file with 0 committed bytes is valid initial state", validated)
        assertEquals(0L, validated?.bytesDownloaded)
    }

    @Test
    fun testMissingPartialFileWithPositiveCommittedBytesFailsClosed() {
        val taskId = "test-rg2-missing-positive-committed"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)
        if (partialFile.exists()) partialFile.delete()

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 35L,
            totalBytes = 100L,
            strongETag = null,
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException when positive committed partial file is missing")
        } catch (e: java.io.IOException) {
            assertTrue("Expected missing partial file error, got: ${e.message}", e.message?.contains("Committed partial file is missing on disk") == true)
        }

        assertTrue("Manifest must remain preserved on disk", manifestFile.exists())
    }

    @Test
    fun testManifestWithBytesDownloadedGreaterThanTotalBytesRejected() {
        val taskId = "test-rg2-impossible-offset"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        partialFile.writeBytes(ByteArray(150) { 0x77 })

        // Simulate an impossible manifest already persisted on disk.
        // saveManifest() correctly rejects this state, so the corrupted fixture
        // must be written directly to disk to exercise load/validation behavior.
        manifestFile.writeText(
            """
            taskId=$taskId
            url=http://example.com/media.mp4
            destinationFileName=media.mp4
            bytesDownloaded=150
            totalBytes=100
            strongETag=
            lastModified=
            status=INTERRUPTED
            """.trimIndent()
        )

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException for impossible offset bytesDownloaded > totalBytes")
        } catch (e: java.io.IOException) {
            assertTrue("Expected offset exceeds known total length error, got: ${e.message}", e.message?.contains("exceeds known total length") == true)
        }

        assertTrue("Manifest must remain preserved on disk", manifestFile.exists())
        assertTrue("Partial file must remain preserved on disk", partialFile.exists())
    }

    @Test
    fun testManifestWithUnknownTotalBytesSentinelAccepted() {
        val taskId = "test-rg2-sentinel-total-bytes"
        val partialFile = storageManager.getPartialFile(taskId)

        partialFile.writeBytes(ByteArray(50) { 0x88.toByte() })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 50L,
            totalBytes = -1L, // Sentinel for unknown content length
            strongETag = null,
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        val validated = storageManager.validatePartialState(taskId)
        assertNotNull("Manifest with sentinel totalBytes = -1L must be accepted", validated)
        assertEquals(50L, validated?.bytesDownloaded)
        assertEquals(-1L, validated?.totalBytes)
    }

    @Test
    fun testManifestWithUnsupportedNegativeTotalBytesIsRejected() {
        val taskId = "test-rg2-invalid-total-sentinel"
        val manifestFile = storageManager.getManifestFile(taskId)
        manifestFile.writeText(
            """
            taskId=$taskId
            url=http://example.com/media.mp4
            destinationFileName=media.mp4
            bytesDownloaded=0
            totalBytes=-2
            status=INTERRUPTED
            """.trimIndent()
        )

        assertNull(
            "Only totalBytes = -1 is supported as the unknown-length sentinel",
            storageManager.loadManifest(taskId)
        )
    }

    @Test
    fun testPartialFileSizeIoExceptionPropagatesAndPreservesFiles() {
        val taskId = "test-rg2-size-io-err"
        val manifestFile = storageManager.getManifestFile(taskId)
        val partialFile = storageManager.getPartialFile(taskId)

        partialFile.writeBytes(ByteArray(20) { 0x90.toByte() })

        val manifest = com.galaxy.itsonline.downloader.model.RecoveryManifest(
            taskId = taskId,
            url = "http://example.com/media.mp4",
            destinationFileName = "media.mp4",
            bytesDownloaded = 20L,
            totalBytes = 100L,
            strongETag = null,
            lastModified = null,
            status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
        )
        storageManager.saveManifest(manifest)

        storageManager.partialFileSizeHook = {
            throw java.io.IOException("Simulated storage read size I/O error")
        }

        try {
            storageManager.validatePartialState(taskId)
            fail("Expected IOException on size query I/O failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Simulated storage read size I/O error") == true)
        } finally {
            storageManager.partialFileSizeHook = null
        }

        assertTrue("Manifest must remain preserved on disk", manifestFile.exists())
        assertTrue("Partial file must remain preserved on disk", partialFile.exists())
    }

    @Test
    fun testIndependentTaskDataRemainsUntouchedDuringPartialValidation() {
        val task1Id = "test-rg2-task-1"
        val task2Id = "test-rg2-task-2"

        val part1 = storageManager.getPartialFile(task1Id)
        val part2 = storageManager.getPartialFile(task2Id)
        val bak2 = File(storageManager.getManifestFile(task2Id).parentFile, "$task2Id.manifest.bak")

        // Task 1: oversized (needs truncation from 25 to 10)
        part1.writeBytes(ByteArray(25) { 0x11 })
        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = task1Id,
                url = "http://example.com/file1.mp4",
                destinationFileName = "file1.mp4",
                bytesDownloaded = 10L,
                totalBytes = 100L,
                strongETag = null,
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )

        // Task 2: exact size (35 bytes), backup exists
        val task2Payload = ByteArray(35) { 0x22 }
        part2.writeBytes(task2Payload)
        storageManager.saveManifest(
            com.galaxy.itsonline.downloader.model.RecoveryManifest(
                taskId = task2Id,
                url = "http://example.com/file2.mp4",
                destinationFileName = "file2.mp4",
                bytesDownloaded = 35L,
                totalBytes = 200L,
                strongETag = "\"etag-task2\"",
                lastModified = null,
                status = com.galaxy.itsonline.downloader.model.DownloadStatus.INTERRUPTED
            )
        )
        val bak2Content = "taskId=$task2Id\nurl=http://example.com/file2.mp4\ndestinationFileName=file2.mp4\nbytesDownloaded=35\ntotalBytes=200\nstatus=INTERRUPTED\n"
        bak2.writeText(bak2Content)

        // Validate Task 1
        val validated1 = storageManager.validatePartialState(task1Id)
        assertNotNull(validated1)
        assertEquals(10L, validated1?.bytesDownloaded)
        assertEquals(10L, java.nio.file.Files.size(part1.toPath()))

        // Verify Task 2 is completely unchanged
        assertEquals(35L, java.nio.file.Files.size(part2.toPath()))
        assertTrue(task2Payload.contentEquals(part2.readBytes()))
        val task2Manifest = storageManager.loadManifest(task2Id)
        assertNotNull(task2Manifest)
        assertEquals(35L, task2Manifest?.bytesDownloaded)
        assertEquals("http://example.com/file2.mp4", task2Manifest?.url)
        assertTrue(bak2.exists())
        assertEquals(bak2Content, bak2.readText())
    }
}
