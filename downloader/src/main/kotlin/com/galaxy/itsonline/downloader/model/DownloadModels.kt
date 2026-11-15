package com.galaxy.itsonline.downloader.model

/**
 * Lifecycle states of a download task.
 */
enum class DownloadStatus {
    QUEUED,
    ACTIVE,
    PAUSED,
    INTERRUPTED,
    COMPLETED,
    CANCELLED,
    FAILED,
    CORRUPTED
}

/**
 * Download task definition.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val destinationFileName: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val strongETag: String? = null,
    val lastModified: String? = null,
    val failureReason: String? = null
)

/**
 * Real-time progress snapshot.
 */
data class DownloadProgress(
    val taskId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Float = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) * 100f else -1f,
    val isIndeterminate: Boolean = totalBytes <= 0
)

/**
 * Persistent manifest stored in persistent storage (filesDir) alongside partial download files.
 */
data class RecoveryManifest(
    val taskId: String,
    val url: String,
    val destinationFileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val strongETag: String?,
    val lastModified: String?,
    val status: DownloadStatus
)
