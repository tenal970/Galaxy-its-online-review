package com.galaxy.itsonline.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMediaVerifierTest {

    private val verifier = DefaultMediaVerifier()

    private fun buildEbmlHeader(docTypeBytes: ByteArray): ByteArray {
        // Element 0x42 0x82 (DocType) + size VINT (1 byte: 0x80 or size) + raw bytes
        val docTypeElement = byteArrayOf(0x42, 0x82.toByte(), (0x80 or docTypeBytes.size).toByte()) + docTypeBytes

        // Element 0x42 0x86 (EBMLVersion) + size 1 + value 1
        val versionElement = byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)

        val headerBody = versionElement + docTypeElement
        // EBML Header ID: 1A 45 DF A3 + header size VINT + headerBody
        return byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or headerBody.size).toByte()) + headerBody
    }

    private fun buildEbmlHeader(docTypeStr: String): ByteArray {
        return buildEbmlHeader(docTypeStr.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun formatDiagnostic(
        testName: String,
        expected: String,
        actual: VerificationResult,
        fixture: ByteArray
    ): String {
        val details = when (actual) {
            is VerificationResult.FormatMismatch -> "reason='${actual.reason}', detectedFormat=${actual.detectedFormat}, expectedFormat=${actual.expectedFormat}"
            is VerificationResult.SecurityViolation -> "threatType='${actual.threatType}', details='${actual.details}'"
            is VerificationResult.Valid -> "detectedFormat=${actual.detectedFormat}, details='${actual.details}'"
        }
        return "\n[$testName FAILURE DIAGNOSTIC]\n" +
                "  Expected: $expected\n" +
                "  Actual: $actual ($details)\n" +
                "  Fixture hex (${fixture.size} bytes): ${fixture.toHex()}\n"
    }

    @Test
    fun testValidMp4Signature() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x1C, // Box size = 28
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), // 'ftyp'
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), // major_brand = isom
            0x00, 0x00, 0x02, 0x00  // minor_version
        ) + ByteArray(16)

        val result = verifier.verify(header, MediaFormat.MP4)
        assertTrue(result is VerificationResult.Valid)
        assertEquals(MediaFormat.MP4, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testValidWebmSignature() {
        val webmHeader = buildEbmlHeader("webm")
        val result = verifier.verify(webmHeader, MediaFormat.WEBM)
        val diag = formatDiagnostic("testValidWebmSignature", "Valid(detectedFormat=WEBM)", result, webmHeader)
        assertTrue(diag, result is VerificationResult.Valid)
        assertEquals(diag, MediaFormat.WEBM, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testValidMatroskaSignature() {
        val mkvHeader = buildEbmlHeader("matroska")
        val result = verifier.verify(mkvHeader, MediaFormat.MATROSKA)
        val diag = formatDiagnostic("testValidMatroskaSignature", "Valid(detectedFormat=MATROSKA)", result, mkvHeader)
        assertTrue(diag, result is VerificationResult.Valid)
        assertEquals(diag, MediaFormat.MATROSKA, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testMatroskaRejectedWhenExpectedFormatIsWebm() {
        val mkvHeader = buildEbmlHeader("matroska")
        val result = verifier.verify(mkvHeader, MediaFormat.WEBM)
        val diag = formatDiagnostic("testMatroskaRejectedWhenExpectedFormatIsWebm", "FormatMismatch(expected=WEBM, detected=MATROSKA)", result, mkvHeader)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        val mismatch = result as VerificationResult.FormatMismatch
        assertEquals(diag, MediaFormat.WEBM, mismatch.expectedFormat)
        assertEquals(diag, MediaFormat.MATROSKA, mismatch.detectedFormat)
    }

    @Test
    fun testValidNullTerminatedDocType() {
        // RFC 8794 Section 13: DocType with null padding "webm\u0000"
        val nullPaddedDocType = "webm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x00)
        val header = buildEbmlHeader(nullPaddedDocType)
        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testValidNullTerminatedDocType", "Valid(detectedFormat=WEBM)", result, header)
        assertTrue(diag, result is VerificationResult.Valid)
        assertEquals(diag, MediaFormat.WEBM, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testRejectMissingDocType() {
        val versionElement = byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
        val headerWithoutDocType = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or versionElement.size).toByte()) + versionElement

        val result = verifier.verify(headerWithoutDocType, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectMissingDocType", "FormatMismatch with reason containing 'missing DocType'", result, headerWithoutDocType)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("missing DocType"))
    }

    @Test
    fun testRejectDuplicateDocType() {
        val docType1 = byteArrayOf(0x42, 0x82.toByte(), 0x84.toByte()) + "webm".toByteArray(Charsets.US_ASCII)
        val docType2 = byteArrayOf(0x42, 0x82.toByte(), 0x84.toByte()) + "webm".toByteArray(Charsets.US_ASCII)
        val body = docType1 + docType2
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or body.size).toByte()) + body

        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectDuplicateDocType", "FormatMismatch with reason containing 'Duplicate DocType'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("Duplicate DocType"))
    }

    @Test
    fun testRejectZeroLengthDocType() {
        val emptyDocTypeElement = byteArrayOf(0x42, 0x82.toByte(), 0x80.toByte()) // size 0
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or emptyDocTypeElement.size).toByte()) + emptyDocTypeElement

        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectZeroLengthDocType", "FormatMismatch with reason containing 'zero-length DocType'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("zero-length DocType"))
    }

    @Test
    fun testRejectDocTypeWithLeadingOrTrailingWhitespace() {
        val leadingSpaceHeader = buildEbmlHeader(" webm")
        val resultLeading = verifier.verify(leadingSpaceHeader, MediaFormat.WEBM)
        val diagLeading = formatDiagnostic("testRejectDocTypeWithLeadingOrTrailingWhitespace (leading)", "FormatMismatch with reason containing 'whitespace'", resultLeading, leadingSpaceHeader)
        assertTrue(diagLeading, resultLeading is VerificationResult.FormatMismatch)
        assertTrue(diagLeading, (resultLeading as VerificationResult.FormatMismatch).reason.contains("whitespace"))

        val trailingSpaceHeader = buildEbmlHeader("webm ")
        val resultTrailing = verifier.verify(trailingSpaceHeader, MediaFormat.WEBM)
        val diagTrailing = formatDiagnostic("testRejectDocTypeWithLeadingOrTrailingWhitespace (trailing)", "FormatMismatch with reason containing 'whitespace'", resultTrailing, trailingSpaceHeader)
        assertTrue(diagTrailing, resultTrailing is VerificationResult.FormatMismatch)
        assertTrue(diagTrailing, (resultTrailing as VerificationResult.FormatMismatch).reason.contains("whitespace"))
    }

    @Test
    fun testRejectInvalidAsciiInDocType() {
        val nonPrintableDocType = byteArrayOf('w'.code.toByte(), 0x07, 'b'.code.toByte(), 'm'.code.toByte()) // Bell character
        val header = buildEbmlHeader(nonPrintableDocType)
        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectInvalidAsciiInDocType", "FormatMismatch with reason containing 'Invalid character'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("Invalid character"))
    }

    @Test
    fun testRejectTruncatedVint() {
        // EBML Header ID followed by first byte of 4-byte VINT (0x10), but buffer ends immediately
        val truncatedVintHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x10)
        val result = verifier.verify(truncatedVintHeader, MediaFormat.WEBM)
        assertTrue(result is VerificationResult.FormatMismatch)
        assertTrue((result as VerificationResult.FormatMismatch).reason.contains("Truncated EBML header size VINT"))
    }

    @Test
    fun testRejectInvalidVintMarkerAllZero() {
        // First byte of VINT is 0x00, which has no leading 1-bit within 8 bytes
        val invalidVintHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x00)
        val result = verifier.verify(invalidVintHeader, MediaFormat.WEBM)
        assertTrue(result is VerificationResult.FormatMismatch)
        assertTrue((result as VerificationResult.FormatMismatch).reason.contains("Truncated EBML header size VINT"))
    }

    @Test
    fun testRejectUnknownSizeVintInHeader() {
        // VINT 0xFF (1 byte all 1s after marker bit removed) denotes undefined/unknown size, unsupported in Header
        val unknownSizeHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0xFF.toByte())
        val result = verifier.verify(unknownSizeHeader, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectUnknownSizeVintInHeader", "FormatMismatch with reason containing 'unknown or zero size'", result, unknownSizeHeader)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("unknown or zero size"))
    }

    @Test
    fun testRejectElementSizeExceedingHeaderBoundary() {
        // EBML Header declared size = 8 bytes.
        // Inner element declares size = 20 bytes, crossing the 8-byte header boundary.
        val innerElem = byteArrayOf(0x42, 0x82.toByte(), (0x80 or 20).toByte()) + ByteArray(20)
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x88.toByte()) + innerElem

        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectElementSizeExceedingHeaderBoundary", "FormatMismatch with reason containing 'exceeds declared header boundary'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("exceeds declared header boundary"))
    }

    @Test
    fun testRejectNonMinimalElementId() {
        // Element ID 0x40 0x3F has length 2, but its data bits (0x3F) could fit in 1 octet (0xBF).
        // Per RFC 8794 Section 5 and Table 3, non-minimal Element IDs MUST be rejected.
        val nonMinimalElem = byteArrayOf(0x40, 0x3F, 0x81.toByte(), 0x01)
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or nonMinimalElem.size).toByte()) + nonMinimalElem
        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectNonMinimalElementId", "FormatMismatch with reason containing 'element ID'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("element ID"))
    }

    @Test
    fun testRejectElementIdWithAllOnesDataBits() {
        // Element ID 0x7F 0xFF has length 2 with all data bits set to 1.
        // Per RFC 8794 Section 5, this is reserved/invalid.
        val allOnesElem = byteArrayOf(0x7F, 0xFF.toByte(), 0x81.toByte(), 0x01)
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), (0x80 or allOnesElem.size).toByte()) + allOnesElem
        val result = verifier.verify(header, MediaFormat.WEBM)
        val diag = formatDiagnostic("testRejectElementIdWithAllOnesDataBits", "FormatMismatch with reason containing 'element ID'", result, header)
        assertTrue(diag, result is VerificationResult.FormatMismatch)
        assertTrue(diag, (result as VerificationResult.FormatMismatch).reason.contains("element ID"))
    }

    @Test
    fun testValidMp3Id3Signature() {
        val id3Header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            0x03, 0x00, 0x00, 0x00, 0x00, 0x0F, 0x76
        )
        val result = verifier.verify(id3Header, MediaFormat.MP3)
        assertTrue(result is VerificationResult.Valid)
        assertEquals(MediaFormat.MP3, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testValidMp3RawFrameSyncSignature() {
        val syncHeader = byteArrayOf(
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()
        )
        val result = verifier.verify(syncHeader, MediaFormat.MP3)
        assertTrue(result is VerificationResult.Valid)
        assertEquals(MediaFormat.MP3, (result as VerificationResult.Valid).detectedFormat)
    }

    @Test
    fun testRejectElfExecutableDisguisedAsMedia() {
        val elfHeader = byteArrayOf(
            0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
            0x02, 0x01, 0x01, 0x00
        )
        val result = verifier.verify(elfHeader, MediaFormat.MP3)
        assertTrue(result is VerificationResult.SecurityViolation)
        assertEquals("ELF_BINARY", (result as VerificationResult.SecurityViolation).threatType)
    }

    @Test
    fun testRejectDexArchiveDisguisedAsMedia() {
        val dexHeader = byteArrayOf(
            'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0A,
            0x30, 0x33, 0x39, 0x00
        )
        val result = verifier.verify(dexHeader, MediaFormat.MP4)
        assertTrue(result is VerificationResult.SecurityViolation)
        assertEquals("DEX_BINARY", (result as VerificationResult.SecurityViolation).threatType)
    }

    @Test
    fun testRejectZipApkDisguisedAsMedia() {
        val zipHeader = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00
        )
        val result = verifier.verify(zipHeader, MediaFormat.WEBM)
        assertTrue(result is VerificationResult.SecurityViolation)
        assertEquals("ZIP_ARCHIVE", (result as VerificationResult.SecurityViolation).threatType)
    }

    @Test
    fun testRejectHtmlDisguisedPayload() {
        val htmlContent = "<!DOCTYPE html><html><head><title>403 Forbidden</title></head></html>".toByteArray(Charsets.UTF_8)
        val result = verifier.verify(htmlContent, MediaFormat.MP4)
        assertTrue(result is VerificationResult.SecurityViolation)
        assertEquals("HTML_DISGUISED", (result as VerificationResult.SecurityViolation).threatType)
    }
}
