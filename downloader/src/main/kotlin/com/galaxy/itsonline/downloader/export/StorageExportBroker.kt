package com.galaxy.itsonline.downloader.export

import com.galaxy.itsonline.downloader.storage.PersistentStorageManager
import com.galaxy.itsonline.verify.DefaultMediaVerifier
import com.galaxy.itsonline.verify.MediaFormat
import com.galaxy.itsonline.verify.VerificationResult
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

sealed interface ExportResult {
    data class Success(val bytesExported: Long) : ExportResult
    data class VerificationFailed(val violation: VerificationResult) : ExportResult
    data class Failure(val reason: String) : ExportResult
}

/**
 * Storage Export Broker for Android Storage Access Framework (SAF) integration:
 * 1. Verifies partial file signature and threat rules before exporting.
 * 2. Streams partial bytes safely to destination OutputStream.
 * 3. Verifies exact bytes written.
 * 4. Deletes persistent partial file and manifest ONLY after export is confirmed successful.
 */
class StorageExportBroker(
    private val storageManager: PersistentStorageManager,
    private val verifier: DefaultMediaVerifier = DefaultMediaVerifier()
) {

    fun exportToDestination(
        taskId: String,
        destinationStreamProvider: () -> OutputStream,
        expectedFormat: MediaFormat? = null
    ): ExportResult {
        val partialFile = storageManager.getPartialFile(taskId)
        if (!partialFile.exists() || partialFile.length() == 0L) {
            return ExportResult.Failure("Partial file does not exist or is empty")
        }

        // 1. Verification Gate
        val verification = verifier.verifyFile(partialFile, expectedFormat)
        if (verification !is VerificationResult.Valid) {
            return ExportResult.VerificationFailed(verification)
        }

        val totalSourceBytes = partialFile.length()
        var bytesWritten = 0L

        try {
            val destinationStream = destinationStreamProvider()
            destinationStream.use { out ->
                FileInputStream(partialFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        bytesWritten += read
                    }
                    out.flush()
                }
            }

            // 2. Exact bytes check
            if (bytesWritten != totalSourceBytes) {
                return ExportResult.Failure("Partial export write mismatch: source had $totalSourceBytes bytes, wrote $bytesWritten bytes")
            }

            // 3. Remove task state ONLY upon confirmed successful export
            storageManager.removeTaskData(taskId)

            return ExportResult.Success(bytesWritten)
        } catch (e: Exception) {
            return ExportResult.Failure("Export stream write failed: ${e.message}")
        }
    }
}
