import sys
import os
import struct

class MediaFormat:
    MP4 = ("mp4", "video/mp4")
    WEBM = ("webm", "video/webm")
    MATROSKA = ("mkv", "video/x-matroska")
    MP3 = ("mp3", "audio/mpeg")
    M4A = ("m4a", "audio/mp4")
    JPEG = ("jpg", "image/jpeg")
    PNG = ("png", "image/png")
    UNKNOWN = ("bin", "application/octet-stream")

class VerificationResult:
    class Valid:
        def __init__(self, fmt, details=""):
            self.detectedFormat = fmt
            self.details = details
        def __repr__(self): return f"Valid({self.detectedFormat})"

    class FormatMismatch:
        def __init__(self, expected, detected, reason):
            self.expectedFormat = expected
            self.detectedFormat = detected
            self.reason = reason
        def __repr__(self): return f"FormatMismatch(expected={self.expectedFormat}, detected={self.detectedFormat}, reason='{self.reason}')"

    class SecurityViolation:
        def __init__(self, threat, details):
            self.threatType = threat
            self.details = details
        def __repr__(self): return f"SecurityViolation({self.threatType}, details='{self.details}')"

class DefaultMediaVerifier:
    ELF_MAGIC = b"\x7F\x45\x4C\x46"
    DEX_MAGIC = b"\x64\x65\x78\x0A"
    PE_MAGIC = b"\x4D\x5A"
    ZIP_MAGIC = b"\x50\x4B\x03\x04"
    SHEBANG = b"\x23\x21"

    EBML_MAGIC = b"\x1A\x45\xDF\xA3"
    ID3_MAGIC = b"\x49\x44\x33"
    JPEG_MAGIC = b"\xFF\xD8\xFF"
    PNG_MAGIC = b"\x89\x50\x4E\x47\x0D\x0A\x1A\x0A"

    def verify(self, b: bytes, expected=None):
        if len(b) < 4:
            return VerificationResult.FormatMismatch(expected, MediaFormat.UNKNOWN, f"Payload too small to evaluate signature ({len(b)} bytes)")

        # 1. Critical Threat Detection
        if b.startswith(self.ELF_MAGIC):
            return VerificationResult.SecurityViolation("ELF_BINARY", "Dangerous Linux/Android ELF executable detected")
        if b.startswith(self.DEX_MAGIC):
            return VerificationResult.SecurityViolation("DEX_BINARY", "Dangerous Android Dalvik Executable (DEX) detected")
        if b.startswith(self.PE_MAGIC):
            return VerificationResult.SecurityViolation("PE_EXECUTABLE", "Dangerous Windows Portable Executable (EXE/DLL) detected")
        if b.startswith(self.ZIP_MAGIC):
            return VerificationResult.SecurityViolation("ZIP_ARCHIVE", "Raw ZIP/APK archive payload detected where media expected")
        if b.startswith(self.SHEBANG):
            return VerificationResult.SecurityViolation("SHELL_SCRIPT", "Executable shell script header detected")

        text_prefix = b[:128].decode('utf-8', errors='ignore').lower().strip()
        if text_prefix.startswith("<!doctype html") or text_prefix.startswith("<html") or text_prefix.startswith("<?xml"):
            return VerificationResult.SecurityViolation("HTML_DISGUISED", "HTML or XML response received instead of raw media stream")

        # 2. Initial Format Detection
        initial_detected = self.detect_format(b)
        if initial_detected == MediaFormat.UNKNOWN:
            return VerificationResult.FormatMismatch(expected, MediaFormat.UNKNOWN, "No recognized safe media container structure found")

        # 3. Format-specific structural validation & refined detection
        final_detected, structure_err = self.inspect_container_structure(b, initial_detected)
        if structure_err is not None:
            return VerificationResult.FormatMismatch(expected or final_detected, final_detected, structure_err)

        if expected is not None and expected != MediaFormat.UNKNOWN and expected != final_detected:
            is_iso_bmff_compat = (expected == MediaFormat.M4A and final_detected == MediaFormat.MP4) or \
                                 (expected == MediaFormat.MP4 and final_detected == MediaFormat.M4A)
            if not is_iso_bmff_compat:
                return VerificationResult.FormatMismatch(expected, final_detected, f"Container mismatch: Expected {expected} but detected {final_detected}")

        return VerificationResult.Valid(final_detected, f"Container structure verified clean for {final_detected}")

    def detect_format(self, b: bytes):
        if b.startswith(self.JPEG_MAGIC): return MediaFormat.JPEG
        if b.startswith(self.PNG_MAGIC): return MediaFormat.PNG
        if b.startswith(self.EBML_MAGIC): return MediaFormat.WEBM
        if b.startswith(self.ID3_MAGIC): return MediaFormat.MP3
        if self.is_valid_mp3_frame_sync(b): return MediaFormat.MP3

        if len(b) >= 8 and b[4:8] == b"ftyp":
            if len(b) >= 12:
                brand = b[8:12].decode('ascii', errors='ignore').lower()
                if "m4a" in brand or "m4b" in brand: return MediaFormat.M4A
            return MediaFormat.MP4
        return MediaFormat.UNKNOWN

    def inspect_container_structure(self, b: bytes, initial_format):
        if initial_format in (MediaFormat.MP4, MediaFormat.M4A):
            if len(b) < 8: return (initial_format, "Truncated ISO-BMFF header")
            box_size = struct.unpack(">I", b[0:4])[0]
            if box_size < 8 or box_size > 1024*1024:
                return (initial_format, f"Invalid ISO-BMFF box size: {box_size}")
            return (initial_format, None)
        elif initial_format == MediaFormat.WEBM:
            return self.inspect_ebml_header(b)
        elif initial_format == MediaFormat.MP3:
            if b.startswith(self.ID3_MAGIC):
                if len(b) < 10: return (initial_format, "Truncated ID3v2 header")
                version = b[3]
                if version not in (2, 3, 4): return (initial_format, f"Unsupported ID3 version: 2.{version}")
            elif not self.is_valid_mp3_frame_sync(b):
                return (initial_format, "Corrupted MPEG audio frame sync header")
            return (initial_format, None)
        elif initial_format == MediaFormat.PNG:
            if len(b) < 8 or not b.startswith(self.PNG_MAGIC): return (initial_format, "Invalid PNG signature")
            return (initial_format, None)
        elif initial_format == MediaFormat.JPEG:
            if len(b) < 4 or not b.startswith(self.JPEG_MAGIC): return (initial_format, "Invalid JPEG signature")
            return (initial_format, None)
        return (initial_format, "Unknown format")

    def inspect_ebml_header(self, b: bytes):
        if len(b) < 4 or not b.startswith(self.EBML_MAGIC):
            return (MediaFormat.WEBM, "Missing EBML header ID 0x1A45DFA3")

        offset = 4
        res = self.read_vint(b, offset)
        if not res: return (MediaFormat.WEBM, "Truncated EBML header size VINT")
        header_size, header_len = res
        offset += header_len

        if header_size <= 0:
            return (MediaFormat.WEBM, f"Invalid EBML header size: {header_size} (unknown or zero size unsupported in EBML Header)")
        if header_size > 65536:
            return (MediaFormat.WEBM, f"EBML header declared size too large: {header_size} bytes")

        remaining_bytes = len(b) - offset
        if header_size > remaining_bytes:
            return (MediaFormat.WEBM, f"Truncated EBML header: declared {header_size} bytes, available {remaining_bytes} bytes")

        header_end = offset + header_size

        doc_type_found = None
        doc_type_count = 0

        while offset < header_end:
            elem_res = self.read_ebml_element_id(b, offset, header_end)
            if not elem_res:
                return (MediaFormat.WEBM, f"Truncated or malformed EBML element ID at offset {offset}")
            elem_id, elem_id_len = elem_res
            offset += elem_id_len

            sz_res = self.read_vint(b, offset, header_end)
            if not sz_res:
                return (MediaFormat.WEBM, f"Truncated or malformed EBML element size at offset {offset}")
            elem_sz, elem_sz_len = sz_res
            offset += elem_sz_len

            if elem_sz < 0:
                return (MediaFormat.WEBM, f"Unknown-size element unsupported within EBML Header at offset {offset}")
            remaining_in_hdr = header_end - offset
            if elem_sz > remaining_in_hdr:
                return (MediaFormat.WEBM, f"EBML element size ({elem_sz}) exceeds declared header boundary at offset {offset}")

            if elem_id == 0x4282: # DocType
                doc_type_count += 1
                if doc_type_count > 1:
                    return (MediaFormat.WEBM, "Duplicate DocType elements detected (RFC 8794 requires exactly one DocType)")
                if elem_sz == 0:
                    return (MediaFormat.WEBM, "Invalid DocType element size: 0 (zero-length DocType)")
                if elem_sz > 64:
                    return (MediaFormat.WEBM, f"Invalid DocType element size: {elem_sz} (exceeds 64 bytes)")

                # RFC 8794 Section 13 String parsing:
                # Printable ASCII 0x20..0x7E, terminated by 0x00 or end of element
                actual_length = elem_sz
                for i in range(elem_sz):
                    c = b[offset + i]
                    if c == 0x00:
                        actual_length = i
                        break
                    if c < 0x20 or c > 0x7E:
                        return (MediaFormat.WEBM, f"Invalid character 0x{c:02X} in DocType (must be printable ASCII)")

                if actual_length == 0:
                    return (MediaFormat.WEBM, "Zero-length DocType string before null terminator")

                doc_type_str = b[offset:offset+actual_length].decode('ascii')
                if doc_type_str.startswith(" ") or doc_type_str.endswith(" "):
                    return (MediaFormat.WEBM, f"DocType string contains invalid leading or trailing whitespace: '{doc_type_str}'")

                doc_type_found = doc_type_str

            offset += elem_sz

        if doc_type_found is None:
            return (MediaFormat.WEBM, "EBML header missing DocType element (0x4282)")

        if doc_type_found == "webm":
            return (MediaFormat.WEBM, None)
        elif doc_type_found == "matroska":
            return (MediaFormat.MATROSKA, None)
        else:
            return (MediaFormat.UNKNOWN, f"Unsupported EBML DocType: '{doc_type_found}' (expected 'webm' or 'matroska')")

    def read_vint(self, b: bytes, offset: int, limit=None):
        if limit is None: limit = len(b)
        if offset >= limit: return None
        first = b[offset]
        if first == 0: return None

        length = 1
        mask = 0x80
        while (first & mask) == 0:
            length += 1
            mask >>= 1
            if length > 8: return None

        if length > limit - offset: return None

        data_mask = (1 << (8 - length)) - 1
        val = first & data_mask
        all_ones = (first & data_mask) == data_mask

        for i in range(1, length):
            byte = b[offset + i]
            val = (val << 8) | byte
            if byte != 0xFF: all_ones = False

        if all_ones and length <= 8:
            return (-1, length)

        return (val, length)

    def read_ebml_element_id(self, b: bytes, offset: int, limit: int):
        if offset >= limit: return None
        first = b[offset]
        if first == 0: return None

        length = 1
        mask = 0x80
        while (first & mask) == 0:
            length += 1
            mask >>= 1
            if length > 4: return None

        if length > limit - offset: return None

        val = 0
        for i in range(length):
            val = (val << 8) | b[offset + i]

        is_valid = False
        if length == 1: is_valid = (0x81 <= val <= 0xFE)
        elif length == 2: is_valid = (0x407F <= val <= 0x7FFE)
        elif length == 3: is_valid = (0x203FFF <= val <= 0x3FFFFE)
        elif length == 4: is_valid = (0x101FFFFF <= val <= 0x1FFFFFFE)
        if not is_valid: return None

        return (val, length)

    def is_valid_mp3_frame_sync(self, b: bytes):
        if len(b) < 4: return False
        b0, b1, b2 = b[0], b[1], b[2]
        if b0 != 0xFF or (b1 & 0xE0) != 0xE0: return False
        mpeg_version = (b1 >> 3) & 0x03
        if mpeg_version == 0x01: return False
        layer = (b1 >> 1) & 0x03
        if layer == 0x00: return False
        bitrate_index = (b2 >> 4) & 0x0F
        if bitrate_index == 0x0F: return False
        sample_rate_index = (b2 >> 2) & 0x03
        if sample_rate_index == 0x03: return False
        return True

def build_ebml_header(doc_type_bytes: bytes) -> bytes:
    doc_elem = bytes([0x42, 0x82, 0x80 | len(doc_type_bytes)]) + doc_type_bytes
    ver_elem = bytes([0x42, 0x86, 0x81, 0x01])
    body = ver_elem + doc_elem
    return b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(body)]) + body

def run_tests():
    verifier = DefaultMediaVerifier()
    passed = 0
    total = 0

    # 1. Valid MP4
    total += 1
    mp4_hdr = b"\x00\x00\x00\x1Cftypisom\x00\x00\x02\x00" + b"\x00"*16
    res = verifier.verify(mp4_hdr, MediaFormat.MP4)
    assert isinstance(res, VerificationResult.Valid) and res.detectedFormat == MediaFormat.MP4
    passed += 1

    # 2. Valid WebM
    total += 1
    webm_hdr = build_ebml_header(b"webm")
    res = verifier.verify(webm_hdr, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.Valid) and res.detectedFormat == MediaFormat.WEBM
    passed += 1

    # 3. Valid Matroska
    total += 1
    mkv_hdr = build_ebml_header(b"matroska")
    res = verifier.verify(mkv_hdr, MediaFormat.MATROSKA)
    assert isinstance(res, VerificationResult.Valid) and res.detectedFormat == MediaFormat.MATROSKA
    passed += 1

    # 4. Matroska Rejected when expected is WebM
    total += 1
    res = verifier.verify(mkv_hdr, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch)
    assert res.expectedFormat == MediaFormat.WEBM and res.detectedFormat == MediaFormat.MATROSKA
    passed += 1

    # 5. Valid Null-Terminated DocType (RFC 8794 Section 13)
    total += 1
    null_padded_hdr = build_ebml_header(b"webm\x00\x00")
    res = verifier.verify(null_padded_hdr, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.Valid) and res.detectedFormat == MediaFormat.WEBM
    passed += 1

    # 6. Reject Missing DocType
    total += 1
    ver_elem = bytes([0x42, 0x86, 0x81, 0x01])
    hdr_no_doc = b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(ver_elem)]) + ver_elem
    res = verifier.verify(hdr_no_doc, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "missing DocType" in res.reason
    passed += 1

    # 7. Reject Duplicate DocType
    total += 1
    doc1 = bytes([0x42, 0x82, 0x84]) + b"webm"
    doc2 = bytes([0x42, 0x82, 0x84]) + b"webm"
    dup_body = doc1 + doc2
    hdr_dup = b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(dup_body)]) + dup_body
    res = verifier.verify(hdr_dup, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "Duplicate DocType" in res.reason
    passed += 1

    # 8. Reject Zero-Length DocType
    total += 1
    empty_doc = bytes([0x42, 0x82, 0x80])
    hdr_empty_doc = b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(empty_doc)]) + empty_doc
    res = verifier.verify(hdr_empty_doc, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "zero-length DocType" in res.reason
    passed += 1

    # 9. Reject Leading or Trailing Whitespace in DocType
    total += 1
    hdr_leading_sp = build_ebml_header(b" webm")
    res = verifier.verify(hdr_leading_sp, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "whitespace" in res.reason
    hdr_trailing_sp = build_ebml_header(b"webm ")
    res = verifier.verify(hdr_trailing_sp, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "whitespace" in res.reason
    passed += 1

    # 10. Reject Invalid ASCII in DocType
    total += 1
    hdr_invalid_ascii = build_ebml_header(b"w\x07bm")
    res = verifier.verify(hdr_invalid_ascii, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "Invalid character" in res.reason
    passed += 1

    # 11. Reject Truncated VINT
    total += 1
    trunc_vint = b"\x1A\x45\xDF\xA3\x10"
    res = verifier.verify(trunc_vint, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "Truncated EBML header size VINT" in res.reason
    passed += 1

    # 12. Reject Invalid VINT Marker All Zero
    total += 1
    all_zero_vint = b"\x1A\x45\xDF\xA3\x00"
    res = verifier.verify(all_zero_vint, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "Truncated EBML header size VINT" in res.reason
    passed += 1

    # 13. Reject Unknown-Size VINT in Header
    total += 1
    unknown_sz_vint = b"\x1A\x45\xDF\xA3\xFF"
    res = verifier.verify(unknown_sz_vint, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "unknown or zero size" in res.reason
    passed += 1

    # 14. Reject Element Size Exceeding Header Boundary
    total += 1
    inner_elem = bytes([0x42, 0x82, 0x80 | 20]) + bytes(20)
    hdr_overflow = b"\x1A\x45\xDF\xA3\x88" + inner_elem # declared 8 bytes, element needs 23
    res = verifier.verify(hdr_overflow, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "exceeds declared header boundary" in res.reason
    passed += 1

    # 15. Reject Non-Minimal Element ID (RFC 8794 Section 5 Table 3 & 4)
    total += 1
    non_minimal_elem = bytes([0x40, 0x3F, 0x81, 0x01])
    hdr_non_minimal = b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(non_minimal_elem)]) + non_minimal_elem
    res = verifier.verify(hdr_non_minimal, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "element ID" in res.reason
    passed += 1

    # 16. Reject Element ID with all 1s in VINT_DATA (RFC 8794 Section 5)
    total += 1
    all_ones_elem = bytes([0x7F, 0xFF, 0x81, 0x01])
    hdr_all_ones = b"\x1A\x45\xDF\xA3" + bytes([0x80 | len(all_ones_elem)]) + all_ones_elem
    res = verifier.verify(hdr_all_ones, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.FormatMismatch) and "element ID" in res.reason
    passed += 1

    # 17. Valid MP3 ID3 & Frame Sync
    total += 1
    id3_hdr = b"ID3\x03\x00\x00\x00\x00\x0F\x76"
    res_id3 = verifier.verify(id3_hdr, MediaFormat.MP3)
    assert isinstance(res_id3, VerificationResult.Valid) and res_id3.detectedFormat == MediaFormat.MP3
    sync_hdr = b"\xFF\xFB\x90\x64"
    res_sync = verifier.verify(sync_hdr, MediaFormat.MP3)
    assert isinstance(res_sync, VerificationResult.Valid) and res_sync.detectedFormat == MediaFormat.MP3
    passed += 1

    # 18. Threat Rejections: ELF, DEX, ZIP/APK
    total += 1
    elf_hdr = b"\x7FELF\x02\x01\x01\x00"
    res = verifier.verify(elf_hdr, MediaFormat.MP3)
    assert isinstance(res, VerificationResult.SecurityViolation) and res.threatType == "ELF_BINARY"
    dex_hdr = b"dex\n039\x00"
    res = verifier.verify(dex_hdr, MediaFormat.MP4)
    assert isinstance(res, VerificationResult.SecurityViolation) and res.threatType == "DEX_BINARY"
    zip_hdr = b"PK\x03\x04\x14\x00\x08\x00"
    res = verifier.verify(zip_hdr, MediaFormat.WEBM)
    assert isinstance(res, VerificationResult.SecurityViolation) and res.threatType == "ZIP_ARCHIVE"
    passed += 1

    # 19. Threat Rejection: HTML disguised
    total += 1
    html_hdr = b"<!DOCTYPE html><html><head><title>403 Forbidden</title></head></html>"
    res = verifier.verify(html_hdr, MediaFormat.MP4)
    assert isinstance(res, VerificationResult.SecurityViolation) and res.threatType == "HTML_DISGUISED"
    passed += 1

    print(f"ALL {passed}/{total} RIGOROUS VERIFIER TESTS PASSED ACCORDING TO SPEC!")

if __name__ == "__main__":
    run_tests()
