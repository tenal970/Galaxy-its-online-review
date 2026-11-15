package com.galaxy.itsonline.downloader.storage

import com.galaxy.itsonline.downloader.model.DownloadStatus
import com.galaxy.itsonline.downloader.model.RecoveryManifest
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Manages persistent resumable partial files and manifest records under app-private persistent
 * storage (e.g. context.filesDir/downloads_in_progress).
 *
 * Rules:
 * 1. Resumable files are NEVER stored in cacheDir (preventing low-storage OS eviction).
 * 2. Manifest validates consistency with actual partial file length on disk.
 * 3. Pause preserves partial files; Permanent Cancel deletes both partial file and manifest.
 * 4. Startup reconciliation clears only corrupted/orphaned tasks or marks tasks as INTERRUPTED.
 */
class PersistentStorageManager(
    private val baseDir: File
) {
    class TaskLease internal constructor(
        val progressDirCanonicalPath: String,
        val taskId: String,
        private val onRelease: () -> Unit
    ) : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                onRelease()
            }
        }

        override fun close() = release()
    }

    companion object {
        private val SAFE_TASK_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")

        // Active task coordination across all engine and storage manager instances,
        // keyed by "$progressDirCanonicalPath:$taskId"
        internal val activeLeases = java.util.concurrent.ConcurrentHashMap<String, TaskLease>()

        // Per-task locks to prevent concurrent mutations on the same task ID without holding
        // a global directory lock that would block unrelated tasks
        private val taskLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun isTaskActive(progressDirCanonicalPath: String, taskId: String): Boolean {
            val key = if (progressDirCanonicalPath.isEmpty()) taskId else "$progressDirCanonicalPath:$taskId"
            return if (progressDirCanonicalPath.isEmpty()) {
                activeLeases.containsKey(key) || activeLeases.keys.any { it.endsWith(":$taskId") }
            } else {
                activeLeases.containsKey(key)
            }
        }

        fun acquireTaskLease(progressDirCanonicalPath: String, taskId: String): TaskLease? {
            validateTaskId(taskId)
            val key = if (progressDirCanonicalPath.isEmpty()) taskId else "$progressDirCanonicalPath:$taskId"
            var lease: TaskLease? = null
            activeLeases.compute(key) { _, existing ->
                if (existing != null) {
                    existing
                } else {
                    val newLease = TaskLease(progressDirCanonicalPath, taskId) {
                        activeLeases.remove(key)
                    }
                    lease = newLease
                    newLease
                }
            }
            return lease
        }

        fun releaseTask(progressDirCanonicalPath: String, taskId: String) {
            val key = if (progressDirCanonicalPath.isEmpty()) taskId else "$progressDirCanonicalPath:$taskId"
            activeLeases.remove(key)?.release()
            if (progressDirCanonicalPath.isEmpty()) {
                activeLeases.entries.removeIf { it.key.endsWith(":$taskId") }
            }
        }

        fun validateTaskId(taskId: String) {
            if (taskId.isBlank()) {
                throw IllegalArgumentException("Task ID cannot be blank")
            }
            if (!SAFE_TASK_ID_REGEX.matches(taskId)) {
                throw IllegalArgumentException("Invalid task ID '$taskId': contains prohibited characters or path traversal elements")
            }
        }
    }

    private val progressDir = File(baseDir, "downloads_in_progress").apply {
        if (!exists()) mkdirs()
    }

    val progressDirCanonicalPath: String
        get() = progressDir.canonicalPath

    private fun getTaskLock(taskId: String): Any =
        taskLocks.computeIfAbsent("$progressDirCanonicalPath:$taskId") { Any() }

    fun acquireTaskLease(taskId: String): TaskLease? {
        return Companion.acquireTaskLease(progressDirCanonicalPath, taskId)
    }

    fun isTaskActive(taskId: String): Boolean {
        validateTaskId(taskId)
        return Companion.isTaskActive(progressDirCanonicalPath, taskId)
    }

    fun validateTaskId(taskId: String) {
        Companion.validateTaskId(taskId)
    }

    fun getPartialFile(taskId: String): File {
        validateTaskId(taskId)
        val file = File(progressDir, "$taskId.part")
        if (file.canonicalFile.parentFile != progressDir.canonicalFile) {
            throw IllegalArgumentException("Path traversal attempt in taskId '$taskId'")
        }
        return file
    }

    fun getManifestFile(taskId: String): File {
        validateTaskId(taskId)
        val file = File(progressDir, "$taskId.manifest")
        if (file.canonicalFile.parentFile != progressDir.canonicalFile) {
            throw IllegalArgumentException("Path traversal attempt in taskId '$taskId'")
        }
        return file
    }

    // Internal failure injection hooks for unit testing
    internal var deleteHook: ((File) -> Boolean)? = null
    internal var manifestReadHook: ((File) -> Unit)? = null
    internal var backupReadHook: ((File) -> Unit)? = null
    internal var manifestWriteHook: ((File) -> Unit)? = null
    internal var manifestSyncHook: ((FileDescriptor) -> Unit)? = null
    internal var backupSyncHook: ((FileDescriptor) -> Unit)? = null
    internal var backupWriteHook: ((File) -> Unit)? = null
    internal var manifestMoveHook: ((File, File) -> Unit)? = null
    internal var rollbackMoveHook: ((File, File) -> Unit)? = null
    internal var postMoveVerificationHook: ((File) -> Unit)? = null
    internal var recoveryTruncateHook: ((File, Long) -> Unit)? = null
    internal var recoveryTruncateSyncHook: ((FileDescriptor) -> Unit)? = null
    internal var partialFileSizeHook: ((File) -> Unit)? = null
    internal var postTruncateVerificationHook: ((File) -> Unit)? = null

    /**
     * Strictly verifies file deletion by inspecting actual file existence on disk.
     * Never assumes successful deletion merely because delete() or a hook returned true.
     * Never treats a false return as failure when the file is already absent.
     */
    internal fun safeDeleteFile(file: File): Boolean {
        val path = file.toPath()

        // File.exists() can report false when the path cannot be inspected.
        // Use NIO attributes so an I/O or permission failure is not mistaken for absence.
        try {
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS
            )
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }

        val hook = deleteHook
        try {
            if (hook != null) {
                hook.invoke(file)
            } else {
                Files.deleteIfExists(path)
            }
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }

        // Verify the actual on-disk state; never trust a hook or delete return value alone.
        return try {
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS
            )
            false
        } catch (_: NoSuchFileException) {
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun saveManifest(manifest: RecoveryManifest) = synchronized(getTaskLock(manifest.taskId)) {
        validateTaskId(manifest.taskId)
        if (manifest.bytesDownloaded < 0L) {
            throw IllegalArgumentException("Cannot save manifest with negative bytesDownloaded: ${manifest.bytesDownloaded}")
        }
        if (manifest.totalBytes < -1L) {
            throw IllegalArgumentException("Cannot save manifest with invalid totalBytes: ${manifest.totalBytes}; only -1 represents unknown length")
        }
        if (manifest.totalBytes >= 0L && manifest.bytesDownloaded > manifest.totalBytes) {
            throw IllegalArgumentException(
                "Cannot save manifest: bytesDownloaded ${manifest.bytesDownloaded} exceeds totalBytes ${manifest.totalBytes}"
            )
        }

        val destFile = getManifestFile(manifest.taskId)
        val tempFile = File(progressDir, "${manifest.taskId}.manifest.tmp")
        val bakFile = File(progressDir, "${manifest.taskId}.manifest.bak")

        var previousManifest: RecoveryManifest? = null
        var backupVerified = false
        var replacementCompleted = false
        var rollbackVerified = false
        var primaryException: Exception? = null

        val props = Properties().apply {
            setProperty("taskId", manifest.taskId)
            setProperty("url", manifest.url)
            setProperty("destinationFileName", manifest.destinationFileName)
            setProperty("bytesDownloaded", manifest.bytesDownloaded.toString())
            setProperty("totalBytes", manifest.totalBytes.toString())
            setProperty("strongETag", manifest.strongETag ?: "")
            setProperty("lastModified", manifest.lastModified ?: "")
            setProperty("status", manifest.status.name)
        }

        try {
            // 1. Write to temporary file in the same directory
            FileOutputStream(tempFile).use { out ->
                props.store(out, "Recovery Manifest for ${manifest.taskId}")
                out.flush()
                val syncHook = manifestSyncHook
                if (syncHook != null) {
                    syncHook.invoke(out.fd)
                } else {
                    out.fd.sync()
                }
            }
            manifestWriteHook?.invoke(tempFile)

            // 2. Validate ALL properties in the temporary manifest before replacement
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IOException("Temporary manifest file is empty or missing after write: ${tempFile.name}")
            }
            val tempProps = Properties()
            FileInputStream(tempFile).use { tempProps.load(it) }
            if (tempProps.getProperty("taskId") != manifest.taskId ||
                tempProps.getProperty("url") != manifest.url ||
                tempProps.getProperty("destinationFileName") != manifest.destinationFileName ||
                tempProps.getProperty("bytesDownloaded")?.toLongOrNull() != manifest.bytesDownloaded ||
                tempProps.getProperty("totalBytes")?.toLongOrNull() != manifest.totalBytes ||
                tempProps.getProperty("strongETag")?.ifEmpty { null } != manifest.strongETag ||
                tempProps.getProperty("lastModified")?.ifEmpty { null } != manifest.lastModified ||
                tempProps.getProperty("status") != manifest.status.name
            ) {
                throw IOException("Temporary manifest verification failed: property mismatch or corrupted content")
            }

            // 3. Backup existing canonical manifest before atomic replacement
            // Never swallow backup creation exceptions!
            if (destFile.exists()) {
                previousManifest = loadManifest(manifest.taskId)
                if (previousManifest != null) {
                    try {
                        FileOutputStream(bakFile).use { out ->
                            FileInputStream(destFile).use { it.copyTo(out) }
                            out.flush()
                            val bSync = backupSyncHook
                            if (bSync != null) {
                                bSync.invoke(out.fd)
                            } else {
                                out.fd.sync()
                            }
                        }
                        backupWriteHook?.invoke(bakFile)

                        // Verify all backup manifest fields against previous canonical manifest
                        val loadedBackup = loadManifestFrom(bakFile, manifest.taskId)
                        if (loadedBackup == null ||
                            loadedBackup.taskId != previousManifest.taskId ||
                            loadedBackup.url != previousManifest.url ||
                            loadedBackup.destinationFileName != previousManifest.destinationFileName ||
                            loadedBackup.bytesDownloaded != previousManifest.bytesDownloaded ||
                            loadedBackup.totalBytes != previousManifest.totalBytes ||
                            loadedBackup.strongETag != previousManifest.strongETag ||
                            loadedBackup.lastModified != previousManifest.lastModified ||
                            loadedBackup.status != previousManifest.status
                        ) {
                            throw IOException("Backup manifest verification failed: content mismatch")
                        }
                        backupVerified = true
                    } catch (e: Exception) {
                        // Abort save and preserve original canonical manifest intact!
                        safeDeleteFile(bakFile)
                        throw IOException("Failed to create verified backup of canonical manifest before replacement: ${e.message}", e)
                    }
                }
            }

            // 4. Atomic replacement
            try {
                manifestMoveHook?.invoke(tempFile, destFile)
                Files.move(
                    tempFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                replacementCompleted = true
            } catch (e: Exception) {
                // Do NOT silently fall back to an unsafe direct overwrite!
                // The previous valid manifest is preserved intact.
                throw IOException("Atomic manifest replacement failed: ${e.message}", e)
            }

            // 5. Post-replacement verification: verify ALL fields against requested RecoveryManifest
            var postMoveError: Exception? = null
            try {
                postMoveVerificationHook?.invoke(destFile)
                val verified = loadManifest(manifest.taskId)
                val isVerified = verified != null &&
                    verified.taskId == manifest.taskId &&
                    verified.url == manifest.url &&
                    verified.destinationFileName == manifest.destinationFileName &&
                    verified.bytesDownloaded == manifest.bytesDownloaded &&
                    verified.totalBytes == manifest.totalBytes &&
                    verified.strongETag == manifest.strongETag &&
                    verified.lastModified == manifest.lastModified &&
                    verified.status == manifest.status

                if (!isVerified) {
                    postMoveError = IOException("Field mismatch or unreadable after replacement")
                }
            } catch (e: Exception) {
                postMoveError = e
            }

            if (postMoveError != null) {
                // Post-replacement verification failure!
                // Replacement already succeeded on disk. Rollback MUST be attempted!
                var rollbackAttempted = false
                if (backupVerified && bakFile.exists() && previousManifest != null) {
                    rollbackAttempted = true
                    try {
                        rollbackMoveHook?.invoke(bakFile, destFile)
                        Files.move(
                            bakFile.toPath(),
                            destFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        // Never mark rollback successful merely because Files.move() did not throw!
                        // Verify the restored manifest against the original backup contents!
                        val restored = loadManifest(manifest.taskId)
                        if (restored != null &&
                            restored.taskId == previousManifest.taskId &&
                            restored.url == previousManifest.url &&
                            restored.destinationFileName == previousManifest.destinationFileName &&
                            restored.bytesDownloaded == previousManifest.bytesDownloaded &&
                            restored.totalBytes == previousManifest.totalBytes &&
                            restored.strongETag == previousManifest.strongETag &&
                            restored.lastModified == previousManifest.lastModified &&
                            restored.status == previousManifest.status
                        ) {
                            rollbackVerified = true
                        }
                    } catch (_: Exception) {}
                }

                val rollbackMsg = when {
                    rollbackVerified -> "previous manifest successfully restored and verified from backup"
                    rollbackAttempted -> "rollback was attempted but restored manifest verification failed; backup preserved"
                    backupVerified -> "backup existed but rollback could not be performed; backup preserved"
                    else -> "no valid backup was available; canonical manifest was replaced"
                }
                throw IOException("Post-save manifest verification failed for task '${manifest.taskId}': ${postMoveError.message} ($rollbackMsg)", postMoveError)
            }
        } catch (e: Exception) {
            primaryException = e
            throw e
        } finally {
            // Temporary file cleanup. safeDeleteFile distinguishes absence from I/O failure,
            // so do not gate it with File.exists().
            if (!safeDeleteFile(tempFile)) {
                val cleanupEx = IOException("Failed to clean up temporary manifest file: ${tempFile.name}")
                if (primaryException != null) {
                    primaryException.addSuppressed(cleanupEx)
                } else {
                    throw cleanupEx
                }
            }

            // Backup file cleanup:
            // - Success path: safeDeleteFile(bakFile)
            // - Failed before replacement: safeDeleteFile(bakFile)
            // - Verified rollback: safeDeleteFile(bakFile)
            // - Failed verification & unverified/failed rollback: NEVER delete the only valid backup!
            if (primaryException == null || rollbackVerified || !replacementCompleted) {
                if (!safeDeleteFile(bakFile)) {
                    val cleanupEx = IOException("Failed to clean up backup manifest file: ${bakFile.name}")
                    if (primaryException != null) {
                        primaryException.addSuppressed(cleanupEx)
                    } else {
                        throw cleanupEx
                    }
                }
            }
        }
    }

    internal fun loadManifestFrom(file: File, expectedTaskId: String): RecoveryManifest? {
        val props = Properties()
        try {
            Files.newInputStream(file.toPath()).use { props.load(it) }
        } catch (_: NoSuchFileException) {
            return null
        } catch (_: IllegalArgumentException) {
            // Syntactically malformed manifest content (e.g. invalid \uZZZZ Unicode escape)
            return null
        }

        val loadedTaskId = props.getProperty("taskId")
        val url = props.getProperty("url")
        val destinationFileName = props.getProperty("destinationFileName")

        if (loadedTaskId.isNullOrBlank() || loadedTaskId != expectedTaskId) return null
        if (url.isNullOrBlank() || destinationFileName.isNullOrBlank()) return null

        val bytesDownloaded = props.getProperty("bytesDownloaded")?.toLongOrNull() ?: return null
        val totalBytes = props.getProperty("totalBytes")?.toLongOrNull() ?: return null
        if (bytesDownloaded < 0L || totalBytes < -1L) return null

        val statusStr = props.getProperty("status") ?: return null
        val status = try {
            DownloadStatus.valueOf(statusStr)
        } catch (_: Exception) {
            return null
        }

        return RecoveryManifest(
            taskId = loadedTaskId,
            url = url,
            destinationFileName = destinationFileName,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            strongETag = props.getProperty("strongETag")?.ifEmpty { null },
            lastModified = props.getProperty("lastModified")?.ifEmpty { null },
            status = status
        )
    }

    fun loadManifest(taskId: String): RecoveryManifest? = synchronized(getTaskLock(taskId)) {
        validateTaskId(taskId)
        val file = getManifestFile(taskId)
        manifestReadHook?.invoke(file)
        loadManifestFrom(file, taskId)
    }

    internal fun loadBackupManifest(taskId: String): RecoveryManifest? = synchronized(getTaskLock(taskId)) {
        validateTaskId(taskId)
        val file = File(progressDir, "$taskId.manifest.bak")
        backupReadHook?.invoke(file)
        loadManifestFrom(file, taskId)
    }

    fun validatePartialState(taskId: String, lease: TaskLease? = null): RecoveryManifest? = synchronized(getTaskLock(taskId)) {
        validateTaskId(taskId)
        if (isTaskActive(taskId) && (lease == null || lease.taskId != taskId || lease.progressDirCanonicalPath != progressDirCanonicalPath)) {
            throw IllegalStateException("Cannot validate partial state: task '$taskId' is currently active in another operation")
        }

        // 1. Clean up stale temporary manifest without overriding canonical manifest
        val tempFile = File(progressDir, "$taskId.manifest.tmp")
        if (!safeDeleteFile(tempFile)) {
            throw IOException("Failed to clean up stale temporary manifest: ${tempFile.name}")
        }

        // 2. Load manifest: distinguish I/O error from malformed data
        val manifest = try {
            loadManifest(taskId)
        } catch (e: IOException) {
            // Transient read I/O failure!
            // NEVER delete partial downloads solely because manifest reading failed due to I/O errors!
            // Block Resume while manifest state cannot be verified.
            // Preserve existing files during transient storage failures.
            throw e
        }

        val partialFile = getPartialFile(taskId)

        val bakFile = File(progressDir, "$taskId.manifest.bak")
        val backupManifest = try {
            loadBackupManifest(taskId)
        } catch (e: IOException) {
            // Backup read IOException must abort validation immediately.
            // Do not delete .manifest.bak, .part, or canonical manifest.
            throw e
        }
        val hasValidBackup = backupManifest != null

        // 3. Reject missing or corrupted manifest
        if (manifest == null) {
            if (hasValidBackup) {
                // Returning null would make HttpRangeDownloadEngine treat an existing partial
                // file as orphaned and call removeTaskData(), deleting the valid backup too.
                // Startup reconciliation owns restoration of this backup. Block single-task
                // resume so every recovery file remains untouched.
                throw IOException(
                    "Canonical manifest is missing or invalid while a valid backup exists for task '$taskId'; preserving recovery files"
                )
            }

            // No valid backup can recover this task. Remove the malformed/orphaned task state
            // through the checked deletion path. This also detects I/O failures that File.exists()
            // could otherwise conceal.
            removeTaskData(taskId, lease)
            return null
        }

        // 4. Reject negative committed offset
        if (manifest.bytesDownloaded < 0L) {
            if (hasValidBackup) {
                throw IOException(
                    "Negative committed offset with a valid backup for task '$taskId'; preserving recovery files"
                )
            }
            removeTaskData(taskId, lease)
            return null
        }

        // 5. Reject impossible offset: bytesDownloaded > totalBytes when totalBytes is known (>= 0L)
        // Sentinel value totalBytes = -1L represents unknown content length and is permitted.
        if (manifest.totalBytes >= 0L && manifest.bytesDownloaded > manifest.totalBytes) {
            throw IOException("Corrupted manifest for task '$taskId': committed offset ${manifest.bytesDownloaded} exceeds known total length ${manifest.totalBytes}")
        }

        // 6. Query partial file size using Files.size() to avoid concealing storage errors
        var actualSize = 0L
        var partialFileMissing = false
        try {
            partialFileSizeHook?.invoke(partialFile)
            actualSize = Files.size(partialFile.toPath())
        } catch (_: NoSuchFileException) {
            actualSize = 0L
            partialFileMissing = true
        } catch (e: IOException) {
            throw e
        }

        if (partialFileMissing) {
            if (manifest.bytesDownloaded == 0L) {
                // A missing partial file with 0 committed bytes is a valid initial state
                return manifest
            } else {
                // Missing partial file with > 0 committed bytes fails closed and preserves manifest
                throw IOException("Committed partial file is missing on disk for task '$taskId' (${manifest.bytesDownloaded} bytes committed)")
            }
        }

        val committedOffset = manifest.bytesDownloaded

        if (actualSize > committedOffset) {
            // Partial file is longer than committed durable offset (uncommitted tail data present).
            // Do NOT promote uncommitted bytes to bytesDownloaded.
            // Safely truncate uncommitted tail to preserve only durable committed checkpoint data.
            RandomAccessFile(partialFile, "rw").use { raf ->
                recoveryTruncateHook?.invoke(partialFile, committedOffset)
                raf.setLength(committedOffset)
                val syncHook = recoveryTruncateSyncHook
                if (syncHook != null) {
                    syncHook.invoke(raf.fd)
                } else {
                    raf.fd.sync()
                }
            }
            postTruncateVerificationHook?.invoke(partialFile)
            val verifiedLength = Files.size(partialFile.toPath())
            if (verifiedLength != committedOffset) {
                throw IOException("Truncation verification failed for task '$taskId': expected length $committedOffset, actual length $verifiedLength")
            }
            return manifest
        } else if (actualSize < committedOffset) {
            // Partial file is shorter than committed durable offset.
            // Check if backup manifest is a valid recovery candidate.
            val candidate = backupManifest
            if (candidate != null &&
                candidate.taskId == manifest.taskId &&
                candidate.url == manifest.url &&
                candidate.destinationFileName == manifest.destinationFileName &&
                candidate.strongETag == manifest.strongETag &&
                candidate.lastModified == manifest.lastModified &&
                candidate.totalBytes == manifest.totalBytes &&
                candidate.bytesDownloaded >= 0L &&
                candidate.bytesDownloaded <= actualSize &&
                (candidate.totalBytes == -1L || candidate.bytesDownloaded <= candidate.totalBytes)
            ) {
                // Safe backup recovery: truncate uncommitted tail to backup offset if needed
                if (actualSize > candidate.bytesDownloaded) {
                    RandomAccessFile(partialFile, "rw").use { raf ->
                        recoveryTruncateHook?.invoke(partialFile, candidate.bytesDownloaded)
                        raf.setLength(candidate.bytesDownloaded)
                        val syncHook = recoveryTruncateSyncHook
                        if (syncHook != null) {
                            syncHook.invoke(raf.fd)
                        } else {
                            raf.fd.sync()
                        }
                    }
                    postTruncateVerificationHook?.invoke(partialFile)
                    val verifiedLength = Files.size(partialFile.toPath())
                    if (verifiedLength != candidate.bytesDownloaded) {
                        throw IOException("Truncation verification failed during backup recovery for task '$taskId': expected length ${candidate.bytesDownloaded}, actual length $verifiedLength")
                    }
                }
                Files.copy(bakFile.toPath(), getManifestFile(taskId).toPath(), StandardCopyOption.REPLACE_EXISTING)
                return candidate
            } else {
                // No safe recovery candidate: fail closed and preserve available files rather than deleting them
                throw IOException("Partial file length ($actualSize bytes) is shorter than committed durable offset ($committedOffset bytes) for task '$taskId' and no consistent backup exists")
            }
        }

        return manifest
    }

    fun removeTaskData(taskId: String, lease: TaskLease? = null) = synchronized(getTaskLock(taskId)) {
        validateTaskId(taskId)
        if (isTaskActive(taskId) && (lease == null || lease.taskId != taskId || lease.progressDirCanonicalPath != progressDirCanonicalPath)) {
            throw IllegalStateException("Cannot remove task data: task '$taskId' is currently active in another operation")
        }
        val filesToDelete = listOf(
            getPartialFile(taskId),
            getManifestFile(taskId),
            File(progressDir, "$taskId.manifest.tmp"),
            File(progressDir, "$taskId.manifest.bak")
        )
        val failedFiles = mutableListOf<String>()
        for (file in filesToDelete) {
            if (!safeDeleteFile(file)) {
                failedFiles.add(file.name)
            }
        }
        if (failedFiles.isNotEmpty()) {
            throw IOException("Failed to delete task data files for task '$taskId', files remaining: ${failedFiles.joinToString(", ")}")
        }
    }

    fun reconcileAllOnStartup(): List<RecoveryManifest> {
        val list = mutableListOf<RecoveryManifest>()

        // 1. Clean up stale .manifest.tmp files first
        val tmpFiles = progressDir.listFiles { _, name -> name.endsWith(".manifest.tmp") } ?: emptyArray()
        for (tmpFile in tmpFiles) {
            val taskId = tmpFile.name.removeSuffix(".manifest.tmp")
            if (isTaskActive(taskId)) {
                // Task is actively downloading in this process! Do NOT touch its files!
                continue
            }
            safeDeleteFile(tmpFile)
        }

        // 2. Discover task IDs from both .manifest and .manifest.bak
        val allTaskIds = mutableSetOf<String>()
        progressDir.listFiles { _, name -> name.endsWith(".manifest") || name.endsWith(".manifest.bak") }?.forEach { f ->
            val id = when {
                f.name.endsWith(".manifest") -> f.name.removeSuffix(".manifest")
                f.name.endsWith(".manifest.bak") -> f.name.removeSuffix(".manifest.bak")
                else -> ""
            }
            if (id.isNotEmpty()) allTaskIds.add(id)
        }

        for (taskId in allTaskIds) {
            if (isTaskActive(taskId)) {
                // Task is currently active in an ongoing download operation!
                // Skip startup reconciliation for this task.
                continue
            }

            synchronized(getTaskLock(taskId)) {
                if (isTaskActive(taskId)) return@synchronized

                val manifestFile = getManifestFile(taskId)
                val bakFile = File(progressDir, "$taskId.manifest.bak")

                var validated: RecoveryManifest? = null

                if (manifestFile.exists()) {
                    val canonical = try {
                        loadManifest(taskId)
                    } catch (e: IOException) {
                        // Transient I/O failure during startup:
                        // NEVER delete partial downloads solely because manifest reading failed due to I/O errors!
                        // Preserve data!
                        return@synchronized
                    }

                    if (canonical == null && bakFile.exists()) {
                        // Canonical manifest is corrupt or unreadable, but backup exists!
                        val backupManifest = try {
                            loadBackupManifest(taskId)
                        } catch (e: IOException) {
                            // Transient I/O failure reading backup! Skip recovery and cleanup for this task!
                            // Preserve canonical manifest, backup, and partial file!
                            return@synchronized
                        }
                        if (backupManifest != null) {
                            try {
                                Files.move(bakFile.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            } catch (_: Exception) {}
                        } else {
                            // Backup is genuinely corrupt (not an I/O error)
                            safeDeleteFile(bakFile)
                        }
                    }

                    try {
                        validated = validatePartialState(taskId)
                    } catch (e: IOException) {
                        return@synchronized
                    }

                    if (validated != null) {
                        safeDeleteFile(bakFile)
                    }
                } else if (bakFile.exists()) {
                    // Canonical manifest missing, but backup exists
                    val backupManifest = try {
                        loadBackupManifest(taskId)
                    } catch (e: IOException) {
                        // Transient I/O failure reading backup! Skip recovery and cleanup for this task!
                        // Preserve backup and partial file!
                        return@synchronized
                    }
                    if (backupManifest != null) {
                        try {
                            Files.move(bakFile.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            validated = validatePartialState(taskId)
                        } catch (_: Exception) {}
                    } else {
                        // Corrupt backup without canonical manifest -> safeDeleteFile(bakFile)
                        safeDeleteFile(bakFile)
                    }
                }

                if (validated != null) {
                    if (validated.status == DownloadStatus.ACTIVE || validated.status == DownloadStatus.QUEUED) {
                        val interrupted = validated.copy(status = DownloadStatus.INTERRUPTED)
                        try {
                            saveManifest(interrupted)
                            list.add(interrupted)
                        } catch (_: Exception) {
                            list.add(validated)
                        }
                    } else {
                        list.add(validated)
                    }
                }
            }
        }
        return list
    }
}
