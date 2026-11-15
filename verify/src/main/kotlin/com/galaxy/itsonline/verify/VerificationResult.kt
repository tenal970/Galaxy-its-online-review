package com.galaxy.itsonline.verify

/**
 * Recognized media and payload formats for validation.
 */
enum class MediaFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    WEBM("webm", "video/webm"),
    MATROSKA("mkv", "video/x-matroska"),
    MP3("mp3", "audio/mpeg"),
    M4A("m4a", "audio/mp4"),
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    UNKNOWN("bin", "application/octet-stream")
}

/**
 * Detailed result of file verification distinguishing clean validation from
 * security violations and format mismatches.
 */
sealed interface VerificationResult {
    /**
     * File verified cleanly with matching container structure and signature.
     */
    data class Valid(
        val detectedFormat: MediaFormat,
        val details: String = ""
    ) : VerificationResult

    /**
     * File is well-formed or non-threatening, but does not match expected format or is truncated.
     */
    data class FormatMismatch(
        val expectedFormat: MediaFormat?,
        val detectedFormat: MediaFormat,
        val reason: String
    ) : VerificationResult

    /**
     * Critical security hazard: executable headers, scripts, or dangerous payloads disguised as media.
     */
    data class SecurityViolation(
        val threatType: String,
        val details: String
    ) : VerificationResult
}

/**
 * Extensible interface for media file verification.
 */
interface MediaVerifier {
    fun verify(bytes: ByteArray, expectedFormat: MediaFormat? = null): VerificationResult
}
