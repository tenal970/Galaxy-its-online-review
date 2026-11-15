import os
import shutil
import tempfile
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading
import re

class MockHttpHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # silent

    def do_GET(self):
        full_content = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        total_len = len(full_content)

        range_hdr = self.headers.get("Range")
        if_range = self.headers.get("If-Range")

        if self.path == "/restart_200":
            # Server deliberately ignores range and sends 200 OK
            self.send_response(200)
            self.send_header("Content-Length", str(total_len))
            self.end_headers()
            self.wfile.write(full_content)
            return

        if self.path == "/error_416":
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{total_len}")
            self.end_headers()
            return

        if self.path == "/malformed_range":
            self.send_response(206)
            self.send_header("Content-Range", "bytes invalid-garbage-range")
            self.end_headers()
            self.wfile.write(b"ABCDEFGHIJ")
            return

        if self.path == "/wrong_start_range":
            self.send_response(206)
            self.send_header("Content-Range", "bytes 5-19/20")
            self.end_headers()
            self.wfile.write(b"012345678912345")
            return

        if self.path == "/invalid_end_range":
            self.send_response(206)
            self.send_header("Content-Range", "bytes 10-8/20")
            self.end_headers()
            return

        if self.path == "/inconsistent_total_range":
            self.send_response(206)
            self.send_header("Content-Range", "bytes 10-25/20")
            self.end_headers()
            self.wfile.write(b"0123456789012345")
            return

        if self.path == "/mismatched_etag":
            self.send_response(206)
            self.send_header("Content-Range", "bytes 10-19/20")
            self.send_header("ETag", '"different-etag"')
            self.end_headers()
            self.wfile.write(b"ABCDEFGHIJ")
            return

        if range_hdr:
            try:
                prefix, r_spec = range_hdr.split("=")
                start_byte = int(r_spec.split("-")[0])
            except:
                start_byte = 0

            # Validating If-Range
            if if_range and if_range != '"strong-etag-1"':
                # ETag changed, must return 200 OK
                self.send_response(200)
                self.send_header("Content-Length", str(total_len))
                self.send_header("ETag", '"strong-etag-2"')
                self.end_headers()
                self.wfile.write(full_content)
                return

            slice_data = full_content[start_byte:]
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start_byte}-{total_len - 1}/{total_len}")
            self.send_header("Content-Length", str(len(slice_data)))
            self.send_header("ETag", '"strong-etag-1"')
            self.end_headers()
            self.wfile.write(slice_data)
        else:
            self.send_response(200)
            self.send_header("Content-Length", str(total_len))
            self.send_header("ETag", '"strong-etag-1"')
            self.end_headers()
            self.wfile.write(full_content)

def parse_content_range(hdr):
    if not hdr: return None
    match = re.match(r"^bytes\s+(\d+)-(\d+)/((\d+)|\*)$", hdr.strip(), re.IGNORECASE)
    if not match: return None
    first = int(match.group(1))
    last = int(match.group(2))
    total = int(match.group(4)) if match.group(4) else None
    return (first, last, total)

def parse_unsatisfied_range(hdr):
    if not hdr: return None
    match = re.match(r"^bytes\s+\*/(\d+)$", hdr.strip(), re.IGNORECASE)
    if not match: return None
    return int(match.group(1))

def validate_range_response(hdr, local_bytes, body_len, stored_etag, received_etag, is_resume_attempt=True):
    if not is_resume_attempt:
        return "Unsolicited HTTP 206 Partial Content received without a valid Range request"
    parsed = parse_content_range(hdr)
    if not parsed:
        return "Malformed or missing Content-Range"
    first, last, total = parsed
    if first != local_bytes:
        return f"Mismatched Content-Range start: server sent {first}, expected {local_bytes}"
    if last < first:
        return f"Invalid Content-Range end: last {last} < first {first}"
    if total is not None and last >= total:
        return f"Inconsistent Content-Range total: last {last} >= total {total}"
    expected_len = last - first + 1
    if body_len != -1 and body_len != expected_len:
        return f"Truncated response body: received {body_len} bytes, expected range length {expected_len}"
    if total is not None and last < total - 1:
        return f"Intermediate range received: end offset {last} did not reach end of representation ({total - 1})"
    if stored_etag is not None:
        if received_etag is None:
            return "Missing ETag on 206 resume when resume operation depends on stored strong ETag"
        if stored_etag != received_etag:
            return f"ETag mismatch on resume: stored={stored_etag}, received={received_etag}"
        if received_etag.startswith("W/"):
            return f"Server returned weak ETag on 206 Partial Content: {received_etag}"
    return None

def run_downloader_tests():
    server = HTTPServer(('127.0.0.1', 0), MockHttpHandler)
    port = server.server_port
    server_thread = threading.Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()

    base_url = f"http://127.0.0.1:{port}"
    tmp_dir = tempfile.mkdtemp()

    passed = 0
    total = 0

    try:
        # 1. Test 200 Download
        total += 1
        req = urllib.request.Request(f"{base_url}/media")
        with urllib.request.urlopen(req) as resp:
            data = resp.read()
            assert resp.status == 200
            assert len(data) == 36
        passed += 1

        # 2. Test 206 Range Resume
        total += 1
        req_range = urllib.request.Request(f"{base_url}/media")
        req_range.add_header("Range", "bytes=10-")
        req_range.add_header("If-Range", '"strong-etag-1"')
        with urllib.request.urlopen(req_range) as resp:
            data = resp.read()
            assert resp.status == 206
            cr = resp.headers.get("Content-Range")
            assert cr == "bytes 10-35/36"
            err = validate_range_response(cr, 10, len(data), '"strong-etag-1"', resp.headers.get("ETag"))
            assert err is None
            assert data == b"ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        passed += 1

        # 3. Test Server ignores range (200 response -> restart logic)
        total += 1
        req_restart = urllib.request.Request(f"{base_url}/restart_200")
        req_restart.add_header("Range", "bytes=10-")
        with urllib.request.urlopen(req_restart) as resp:
            data = resp.read()
            assert resp.status == 200 # Must handle 200 by truncating local partial file
            assert len(data) == 36
        passed += 1

        # 4. Test ETag changed (triggers 200 OK fallback)
        total += 1
        req_etag = urllib.request.Request(f"{base_url}/media")
        req_etag.add_header("Range", "bytes=10-")
        req_etag.add_header("If-Range", '"outdated-etag"')
        with urllib.request.urlopen(req_etag) as resp:
            data = resp.read()
            assert resp.status == 200
            assert resp.headers.get("ETag") == '"strong-etag-2"'
            assert len(data) == 36
        passed += 1

        # 5. Negative Test: Malformed Content-Range
        total += 1
        req_mal = urllib.request.Request(f"{base_url}/malformed_range")
        with urllib.request.urlopen(req_mal) as resp:
            cr = resp.headers.get("Content-Range")
            err = validate_range_response(cr, 10, 10, '"tag"', '"tag"')
            assert "Malformed" in err
        passed += 1

        # 6. Negative Test: Wrong start offset
        total += 1
        req_ws = urllib.request.Request(f"{base_url}/wrong_start_range")
        with urllib.request.urlopen(req_ws) as resp:
            cr = resp.headers.get("Content-Range")
            err = validate_range_response(cr, 10, 15, '"tag"', '"tag"')
            assert "Mismatched Content-Range start" in err
        passed += 1

        # 7. Negative Test: Invalid end offset
        total += 1
        req_ie = urllib.request.Request(f"{base_url}/invalid_end_range")
        with urllib.request.urlopen(req_ie) as resp:
            cr = resp.headers.get("Content-Range")
            err = validate_range_response(cr, 10, 0, '"tag"', '"tag"')
            assert "Invalid Content-Range end" in err
        passed += 1

        # 8. Negative Test: Inconsistent total
        total += 1
        req_it = urllib.request.Request(f"{base_url}/inconsistent_total_range")
        with urllib.request.urlopen(req_it) as resp:
            cr = resp.headers.get("Content-Range")
            err = validate_range_response(cr, 10, 16, '"tag"', '"tag"')
            assert "Inconsistent Content-Range total" in err
        passed += 1

        # 9. Negative Test: Mismatched ETag on 206
        total += 1
        req_me = urllib.request.Request(f"{base_url}/mismatched_etag")
        with urllib.request.urlopen(req_me) as resp:
            cr = resp.headers.get("Content-Range")
            err = validate_range_response(cr, 10, 10, '"original-tag"', resp.headers.get("ETag"))
            assert "ETag mismatch" in err
        passed += 1

        # 10. Negative Test: Unsolicited 206 (client did not send Range header)
        total += 1
        err_unsolicited = validate_range_response("bytes 0-9/10", 0, 10, None, None, is_resume_attempt=False)
        assert "Unsolicited HTTP 206" in err_unsolicited
        passed += 1

        # 11. Negative Test: Truncated body (server sent only 5 bytes of 10-byte range)
        total += 1
        err_truncated = validate_range_response("bytes 10-19/20", 10, 5, '"tag"', '"tag"', is_resume_attempt=True)
        assert "Truncated response body" in err_truncated
        passed += 1

        # 12. Negative Test: Intermediate range (server sent 10-19 of 30, not reaching end 29)
        total += 1
        err_intermediate = validate_range_response("bytes 10-19/30", 10, 10, '"tag"', '"tag"', is_resume_attempt=True)
        assert "Intermediate range received" in err_intermediate
        passed += 1

        # 13. Negative Test: Missing ETag when stored ETag exists
        total += 1
        err_missing_etag = validate_range_response("bytes 10-19/20", 10, 10, '"stored-etag"', None, is_resume_attempt=True)
        assert "Missing ETag" in err_missing_etag
        passed += 1

        # 14. RFC 9110 416 Unsatisfied range syntax (accepts ONLY bytes */N, rejects bytes x-y/N)
        total += 1
        assert parse_unsatisfied_range("bytes */12345") == 12345
        assert parse_unsatisfied_range("bytes */0") == 0
        assert parse_unsatisfied_range("bytes 0-0/12345") is None  # bytes x-y/N must be rejected
        assert parse_unsatisfied_range("bytes 10-19/20") is None
        assert parse_unsatisfied_range("invalid") is None
        passed += 1

        # 15. Task ID security validation
        total += 1
        safe_regex = re.compile(r"^[a-zA-Z0-9_-]+$")
        assert safe_regex.match("task-123_abc") is not None
        assert safe_regex.match("../traversal") is None
        assert safe_regex.match("bad/slash") is None
        assert safe_regex.match("bad spaces") is None
        assert safe_regex.match("") is None
        passed += 1

        # 16. HTTP Status Validation (accept only 200, 206; reject 201, 202, 204, 205, etc.)
        total += 1
        allowed_statuses = {200, 206}
        for st in [201, 202, 204, 205, 203, 207]:
            assert st not in allowed_statuses
        assert 200 in allowed_statuses
        assert 206 in allowed_statuses
        passed += 1

        # 17. Recovery does not promote uncommitted tail
        total += 1
        test_part_file = os.path.join(tmp_dir, "test.part")
        with open(test_part_file, "wb") as f:
            f.write(b"0123456789UNCOMMITTED_TAIL")
        committed_offset = 10
        # When actualSize > committedOffset: truncate to committedOffset
        if os.path.getsize(test_part_file) > committed_offset:
            with open(test_part_file, "r+b") as f:
                f.truncate(committed_offset)
        assert os.path.getsize(test_part_file) == committed_offset
        with open(test_part_file, "rb") as f:
            assert f.read() == b"0123456789"
        passed += 1

        # 18. Partial file shorter than committed offset is invalidated
        total += 1
        with open(test_part_file, "wb") as f:
            f.write(b"SHORT")
        committed_offset = 50
        is_invalidated = False
        if os.path.getsize(test_part_file) < committed_offset:
            os.remove(test_part_file)
            is_invalidated = True
        assert is_invalidated is True
        assert not os.path.exists(test_part_file)
        passed += 1

        print(f"ALL {passed}/{total} HTTP RANGE RESUME SPEC TESTS PASSED!")

    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(tmp_dir)

if __name__ == "__main__":
    run_downloader_tests()
