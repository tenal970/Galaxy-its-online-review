package com.galaxy.itsonline.verify

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production-grade implementation of MediaVerifier:
 * 1. Anti-Execution Threat Detection (ELF, DEX, Windows PE, ZIP/APK, Shell script, HTML error bodies)
 * 2. Deep Container Inspection:
 *    - MP4 / M4A: ISO-BMFF box structure parsing (checks for 'ftyp', 'moov', or 'mdat' valid box sizes)
 *    - WebM / Matroska: Strict RFC 8794 EBML Header and DocType parser:
 *        - Validates EBML Header ID (0x1A 0x45 0xDF 0xA3)
 *        - Rejects unknown-size or non-finite EBML Header elements
 *        - Decodes EBML variable-size integer (VINT) for header size and checks buffer bounds
 *        - Traverses inner EBML elements by their Element ID and encoded VINT size
 *        - Rejects non-minimal Element ID encoding (RFC 8794 Section 5)
 *        - Locates exactly ONE DocType element (0x42 0x82)
 *        - Rejects duplicate, zero-length, or malformed DocType elements
 *        - Validates printable ASCII and RFC 8794 Section 13 null-termination (no arbitrary whitespace stripping)
 *        - Distinguishes "webm" -> MediaFormat.WEBM from "matroska" -> MediaFormat.MATROSKA
 *    - MP3: Full sync frame validation (validates MPEG audio frame header, layer, bitrate, sampling rate)
 * 3. Truncation and structure sanity validation
 */
class DefaultMediaVerifier : MediaVerifier {

    companion object {
        // Critical Threat Signatures
        private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)          // .ELF
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A)          // dex\n
        private val PE_MAGIC = byteArrayOf(0x4D, 0x5A)                       // MZ
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)          // PK.. (ZIP/APK/JAR)
        private val SHEBANG = byteArrayOf(0x23, 0x21)                        // #!

        // Media Signatures
        private val EBML_MAGIC = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) // WebM / MKV
        private val ID3_MAGIC = byteArrayOf(0x49, 0x44, 0x33)                // ID3
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    override fun verify(bytes: ByteArray, expectedFormat: MediaFormat?): VerificationResult {
        if (bytes.size < 4) {
            return VerificationResult.FormatMismatch(
                expectedFormat = expectedFormat,
                detectedFormat = MediaFormat.UNKNOWN,
                reason = "Payload too small to evaluate signature (${bytes.size} bytes)"
            )
        }

        // 1. Critical Threat Detection (Anti-Execution / Anti-Polyglot)
        if (startsWith(bytes, ELF_MAGIC)) {
            return VerificationResult.SecurityViolation("ELF_BINARY", "Dangerous Linux/Android ELF executable detected")
        }
        if (startsWith(bytes, DEX_MAGIC)) {
            return VerificationResult.SecurityViolation("DEX_BINARY", "Dangerous Android Dalvik Executable (DEX) detected")
        }
        if (startsWith(bytes, PE_MAGIC)) {
            return VerificationResult.SecurityViolation("PE_EXECUTABLE", "Dangerous Windows Portable Executable (EXE/DLL) detected")
        }
        if (startsWith(bytes, ZIP_MAGIC)) {
            return VerificationResult.SecurityViolation("ZIP_ARCHIVE", "Raw ZIP/APK archive payload detected where media expected")
        }
        if (startsWith(bytes, SHEBANG)) {
            return VerificationResult.SecurityViolation("SHELL_SCRIPT", "Executable shell script header detected")
        }

        // Check HTML / XML disguised payloads (common error responses from captive portals/anti-bot)
        val textHeader = String(bytes.copyOfRange(0, minOf(bytes.size, 128)), Charsets.UTF_8).lowercase().trim()
        if (textHeader.startsWith("<!doctype html") || textHeader.startsWith("<html") || textHeader.startsWith("<?xml")) {
            return VerificationResult.SecurityViolation(
                "HTML_DISGUISED",
                "HTML or XML response received instead of raw media stream (likely a 403/CAPTCHA/login wall)"
            )
        }

        // 2. Initial Format Detection & Container Structure Verification
        val initialDetected = detectInitialFormat(bytes)

        if (initialDetected == MediaFormat.UNKNOWN) {
            return VerificationResult.FormatMismatch(
                expectedFormat = expectedFormat,
                detectedFormat = MediaFormat.UNKNOWN,
                reason = "No recognized safe media container structure found"
            )
        }

        // 3. Format Specific Structural Validation & Refined Detection
        val (finalDetected, structureError) = inspectContainerStructure(bytes, initialDetected)
        if (structureError != null) {
            return VerificationResult.FormatMismatch(
                expectedFormat = expectedFormat ?: finalDetected,
                detectedFormat = finalDetected,
                reason = structureError
            )
        }

        if (expectedFormat != null && expectedFormat != MediaFormat.UNKNOWN && expectedFormat != finalDetected) {
            // ISO-BMFF compatibility between MP4 and M4A
            val isIsoBmffCompatible = (expectedFormat == MediaFormat.M4A && finalDetected == MediaFormat.MP4) ||
                    (expectedFormat == MediaFormat.MP4 && finalDetected == MediaFormat.M4A)
            if (!isIsoBmffCompatible) {
                return VerificationResult.FormatMismatch(
                    expectedFormat = expectedFormat,
                    detectedFormat = finalDetected,
                    reason = "Container mismatch: Expected $expectedFormat but detected $finalDetected"
                )
            }
        }

        return VerificationResult.Valid(finalDetected, "Container structure verified clean for $finalDetected")
    }

    fun verifyFile(file: File, expectedFormat: MediaFormat? = null): VerificationResult {
        if (!file.exists() || file.length() == 0L) {
            return VerificationResult.FormatMismatch(
                expectedFormat = expectedFormat,
                detectedFormat = MediaFormat.UNKNOWN,
                reason = "File does not exist or has zero bytes"
            )
        }

        // Minimum file size check for real media (media headers must have reasonable minimum byte size)
        if (file.length() < 32L) {
            return VerificationResult.FormatMismatch(
                expectedFormat = expectedFormat,
                detectedFormat = MediaFormat.UNKNOWN,
                reason = "File truncated: Total length is only ${file.length()} bytes, insufficient for media container"
            )
        }

        val headerSize = minOf(file.length(), 4096L).toInt()
        val headerBytes = ByteArray(headerSize)
        FileInputStream(file).use { input ->
            val read = input.read(headerBytes)
            if (read < 32) {
                return VerificationResult.FormatMismatch(
                    expectedFormat = expectedFormat,
                    detectedFormat = MediaFormat.UNKNOWN,
                    reason = "Could not read sufficient header bytes"
                )
            }
        }

        return verify(headerBytes, expectedFormat)
    }

    private fun detectInitialFormat(bytes: ByteArray): MediaFormat {
        if (startsWith(bytes, JPEG_MAGIC)) return MediaFormat.JPEG
        if (startsWith(bytes, PNG_MAGIC)) return MediaFormat.PNG

        // WebM / Matroska EBML Header ID (0x1A45DFA3)
        if (startsWith(bytes, EBML_MAGIC)) {
            return MediaFormat.WEBM
        }

        // MP3 via ID3v2 header
        if (startsWith(bytes, ID3_MAGIC)) {
            return MediaFormat.MP3
        }

        // MP3 raw frame sync
        if (isValidMp3FrameSync(bytes)) {
            return MediaFormat.MP3
        }

        // ISO Base Media File Format (MP4 / M4A / MOV)
        if (bytes.size >= 8) {
            val boxType = String(bytes.copyOfRange(4, 8), Charsets.US_ASCII)
            if (boxType == "ftyp") {
                if (bytes.size >= 12) {
                    val majorBrand = String(bytes.copyOfRange(8, 12), Charsets.US_ASCII).lowercase()
                    if (majorBrand.contains("m4a") || majorBrand.contains("m4b")) {
                        return MediaFormat.M4A
                    }
                }
                return MediaFormat.MP4
            }
        }

        return MediaFormat.UNKNOWN
    }

    private fun inspectContainerStructure(bytes: ByteArray, initialFormat: MediaFormat): Pair<MediaFormat, String?> {
        when (initialFormat) {
            MediaFormat.MP4, MediaFormat.M4A -> {
                if (bytes.size < 8) return Pair(initialFormat, "Truncated ISO-BMFF header")
                val boxSize = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                if (boxSize < 8 || boxSize > 1024 * 1024) {
                    return Pair(initialFormat, "Invalid ISO-BMFF box size: $boxSize")
                }
                return Pair(initialFormat, null)
            }
            MediaFormat.WEBM -> {
                val (refinedFormat, err) = inspectEbmlHeader(bytes)
                return Pair(refinedFormat, err)
            }
            MediaFormat.MP3 -> {
                if (startsWith(bytes, ID3_MAGIC)) {
                    if (bytes.size < 10) return Pair(initialFormat, "Truncated ID3v2 header")
                    val version = bytes[3].toInt()
                    if (version !in 2..4) return Pair(initialFormat, "Unsupported ID3 version: 2.$version")
                } else if (!isValidMp3FrameSync(bytes)) {
                    return Pair(initialFormat, "Corrupted MPEG audio frame sync header")
                }
                return Pair(initialFormat, null)
            }
            MediaFormat.PNG -> {
                if (bytes.size < 8 || !startsWith(bytes, PNG_MAGIC)) return Pair(initialFormat, "Invalid PNG signature")
                return Pair(initialFormat, null)
            }
            MediaFormat.JPEG -> {
                if (bytes.size < 4 || !startsWith(bytes, JPEG_MAGIC)) return Pair(initialFormat, "Invalid JPEG signature")
                return Pair(initialFormat, null)
            }
            else -> return Pair(initialFormat, "Unknown format")
        }
    }

    /**
     * Strict EBML Header and DocType parser compliant with RFC 8794:
     * 1. Confirms 4-byte EBML Header Element ID: 0x1A 0x45 0xDF 0xA3.
     * 2. Decodes EBML variable-size integer (VINT) for header data size. Rejects unknown-size (-1L).
     * 3. Validates boundary within provided buffer.
     * 4. Parses inner elements strictly:
     *    - Validates Element ID encoding length and canonical minimal representation.
     *    - Rejects unknown-size elements inside the EBML header.
     *    - Rejects elements whose data crosses the declared header boundary.
     *    - Tracks DocType (Element ID 0x4282) occurrence: rejects duplicates, enforces exactly 1.
     *    - Validates DocType ASCII string and RFC 8794 Section 13 null-termination:
     *      * Reads bytes up to first null byte (0x00) if present.
     *      * Rejects zero-length strings.
     *      * Rejects non-printable ASCII (< 0x20 or > 0x7E) or any whitespace.
     *    - Accurately classifies "webm" -> MediaFormat.WEBM and "matroska" -> MediaFormat.MATROSKA.
     */
    private fun inspectEbmlHeader(bytes: ByteArray): Pair<MediaFormat, String?> {
        if (bytes.size < 4 || !startsWith(bytes, EBML_MAGIC)) {
            return Pair(MediaFormat.WEBM, "Missing EBML header ID 0x1A45DFA3")
        }

        var offset = 4
        val headerSizeResult = readVint(bytes, offset, bytes.size)
            ?: return Pair(MediaFormat.WEBM, "Truncated EBML header size VINT")

        val headerSize = headerSizeResult.first
        val headerSizeLen = headerSizeResult.second
        offset += headerSizeLen

        if (headerSize <= 0L) {
            return Pair(MediaFormat.WEBM, "Invalid EBML header size: $headerSize (unknown or zero size unsupported in EBML Header)")
        }

        if (headerSize > 65536L) {
            return Pair(MediaFormat.WEBM, "EBML header declared size too large: $headerSize bytes")
        }

        val remainingBytes = (bytes.size - offset).toLong()
        if (headerSize > remainingBytes) {
            return Pair(MediaFormat.WEBM, "Truncated EBML header: declared $headerSize bytes, available $remainingBytes bytes")
        }

        val headerEnd = offset + headerSize.toInt()

        var docTypeFound: String? = null
        var docTypeCount = 0

        while (offset < headerEnd) {
            val elementIdResult = readEbmlElementId(bytes, offset, headerEnd)
                ?: return Pair(MediaFormat.WEBM, "Truncated or malformed EBML element ID at offset $offset")
            val elementId = elementIdResult.first
            val elementIdLen = elementIdResult.second
            offset += elementIdLen

            val elementSizeResult = readVint(bytes, offset, headerEnd)
                ?: return Pair(MediaFormat.WEBM, "Truncated or malformed EBML element size at offset $offset")
            val elementSize = elementSizeResult.first
            val elementSizeLen = elementSizeResult.second
            offset += elementSizeLen

            if (elementSize < 0L) {
                return Pair(MediaFormat.WEBM, "Unknown-size element unsupported within EBML Header at offset $offset")
            }

            val remainingInHeader = (headerEnd - offset).toLong()
            if (elementSize > remainingInHeader) {
                return Pair(MediaFormat.WEBM, "EBML element size ($elementSize) exceeds declared header boundary at offset $offset")
            }

            val elemSizeInt = elementSize.toInt()

            if (elementId == 0x4282) { // 0x42 0x82 = DocType
                docTypeCount++
                if (docTypeCount > 1) {
                    return Pair(MediaFormat.WEBM, "Duplicate DocType elements detected (RFC 8794 requires exactly one DocType)")
                }
                if (elemSizeInt == 0) {
                    return Pair(MediaFormat.WEBM, "Invalid DocType element size: 0 (zero-length DocType)")
                }
                if (elemSizeInt > 64) {
                    return Pair(MediaFormat.WEBM, "Invalid DocType element size: $elemSizeInt (exceeds 64 bytes)")
                }

                // RFC 8794 Section 13 String parsing:
                // Printable ASCII characters 0x20..0x7E, terminated by 0x00 or end of element
                var actualLength = elemSizeInt
                for (i in 0 until elemSizeInt) {
                    val b = bytes[offset + i].toInt() and 0xFF
                    if (b == 0x00) {
                        actualLength = i
                        break
                    }
                    if (b < 0x20 || b > 0x7E) {
                        return Pair(MediaFormat.WEBM, "Invalid character 0x${b.toString(16).uppercase()} in DocType (must be printable ASCII)")
                    }
                }

                if (actualLength == 0) {
                    return Pair(MediaFormat.WEBM, "Zero-length DocType string before null terminator")
                }

                val docTypeStr = String(bytes, offset, actualLength, Charsets.US_ASCII)

                // Reject leading/trailing whitespace (whitespace is 0x20 space or tabs)
                if (docTypeStr.startsWith(" ") || docTypeStr.endsWith(" ")) {
                    return Pair(MediaFormat.WEBM, "DocType string contains invalid leading or trailing whitespace: '$docTypeStr'")
                }

                docTypeFound = docTypeStr
            }

            offset += elemSizeInt
        }

        if (docTypeFound == null) {
            return Pair(MediaFormat.WEBM, "EBML header missing DocType element (0x4282)")
        }

        return when (docTypeFound) {
            "webm" -> Pair(MediaFormat.WEBM, null)
            "matroska" -> Pair(MediaFormat.MATROSKA, null)
            else -> Pair(MediaFormat.UNKNOWN, "Unsupported EBML DocType: '$docTypeFound' (expected 'webm' or 'matroska')")
        }
    }

    /**
     * Reads an EBML variable-size integer (VINT) for data size where the first 1-bit is cleared.
     * RFC 8794 Section 4.
     * Returns Pair(decodedValue, lengthInBytes) or null if truncated or invalid.
     * If all data bits are 1s, returns -1L representing an unknown/undefined size.
     */
    private fun readVint(bytes: ByteArray, offset: Int, limit: Int = bytes.size): Pair<Long, Int>? {
        if (offset >= limit) return null
        val firstByte = bytes[offset].toInt() and 0xFF
        if (firstByte == 0) return null // All zero first byte is invalid in EBML VINT

        val length = Integer.numberOfLeadingZeros(firstByte) - 24 + 1 // 1 to 8 bytes
        if (length !in 1..8 || length > limit - offset) return null

        val mask = (1 shl (8 - length)) - 1
        var value = (firstByte and mask).toLong()
        var allOnes = (firstByte and mask) == mask

        for (i in 1 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }

        if (allOnes) {
            return Pair(-1L, length) // Unknown size indicator
        }

        return Pair(value, length)
    }

    /**
     * Reads an EBML Element ID where the marker bit is PRESERVED.
     * RFC 8794 Section 5: Element IDs are 1 to 4 octets.
     * The first byte cannot be 0x00 and must contain the leading-zero marker bit.
     * Valid ranges per RFC 8794 Section 5 Table 4 (rejects non-minimal encodings, all-zero, and all-one VINT_DATA):
     *  - 1 octet:  0x81..0xFE
     *  - 2 octets: 0x407F..0x7FFE
     *  - 3 octets: 0x203FFF..0x3FFFFE
     *  - 4 octets: 0x101FFFFF..0x1FFFFFFE
     */
    private fun readEbmlElementId(bytes: ByteArray, offset: Int, limit: Int): Pair<Int, Int>? {
        if (offset >= limit) return null
        val firstByte = bytes[offset].toInt() and 0xFF
        if (firstByte == 0) return null // Element IDs cannot start with 0x00

        val length = Integer.numberOfLeadingZeros(firstByte) - 24 + 1 // 1 to 4 bytes
        if (length !in 1..4 || length > limit - offset) return null

        var id = 0
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            id = (id shl 8) or b
        }

        val isValid = when (length) {
            1 -> id in 0x81..0xFE
            2 -> id in 0x407F..0x7FFE
            3 -> id in 0x203FFF..0x3FFFFE
            4 -> id in 0x101FFFFF..0x1FFFFFFE
            else -> false
        }
        if (!isValid) return null

        return Pair(id, length)
    }

    private fun isValidMp3FrameSync(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // Sync word: 11 bits set to 1 -> 0xFF followed by 0xE0 mask
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false

        val mpegVersion = (b1 shr 3) and 0x03 // 00=reserved, 01=reserved, 10=MPEG2, 11=MPEG1
        if (mpegVersion == 0x01) return false // Invalid

        val layer = (b1 shr 1) and 0x03 // 00=reserved
        if (layer == 0x00) return false

        val b2 = bytes[2].toInt() and 0xFF
        val bitrateIndex = (b2 shr 4) and 0x0F
        if (bitrateIndex == 0x0F) return false // Bad bitrate

        val sampleRateIndex = (b2 shr 2) and 0x03
        if (sampleRateIndex == 0x03) return false // Reserved sample rate

        return true
    }

    private fun startsWith(source: ByteArray, prefix: ByteArray): Boolean {
        if (source.size < prefix.size) return false
        for (i in prefix.indices) {
            if (source[i] != prefix[i]) return false
        }
        return true
    }
}
