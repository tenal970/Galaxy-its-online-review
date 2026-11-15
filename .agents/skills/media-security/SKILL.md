---
name: media-security
description: Design, implement, or audit URL handling, media downloading, conversion, temporary files, output validation, and safe saving.
---

# Media Pipeline Security

## Threat model
Treat user-provided URLs, network responses, metadata, filenames, temporary files, and downloaded bytes as untrusted. A media extension or server MIME type is not proof of file type.

## URL and network handling
- Accept only explicitly supported URL schemes, normally HTTP and HTTPS. Reject `file:`, `content:`, `data:`, and unknown schemes unless a feature explicitly requires them.
- Parse URLs with platform/library parsers; do not validate by string prefix alone.
- Avoid logging full URLs because query strings may contain private tokens.
- Handle redirects, timeouts, oversized responses, malformed metadata, and network cancellation.
- Do not add a server-side fetch/proxy or SSRF exposure without a separately reviewed threat model.
- Do not invent domain allowlists that break intended downloader compatibility; validate actual risks and requirements.

## Files and paths
- Treat metadata-derived titles and filenames as untrusted. Normalize names and prevent path traversal, reserved names, and writes outside the selected destination.
- Use app-private temporary storage with unpredictable names where appropriate; clean up only files owned by the current task.
- Never overwrite existing user files silently.
- Bound disk usage and handle low-storage conditions.
- Do not execute, load as code, or install downloaded content.

## Output verification
- Verify actual bytes and container structure before reporting success or exposing output as a completed media file.
- MP4/M4A/MOV commonly use an ISO BMFF `ftyp` box near the start; EBML-based WebM/Matroska commonly begin `1A 45 DF A3`; MP3 may use ID3 or a valid MPEG frame sync.
- These signatures are format clues, not complete parsers. Validate offsets, container structure, truncation, and expected output type using maintained parsers or robust checks where possible.
- Reject executable/package content when media is expected, including ELF, DEX, PE, scripts, and APK/ZIP mismatches.
- Never trust an extension, filename, or MIME type alone.

## Converter and subprocess boundaries
Verify how each library invokes native code and handles arguments, temporary paths, cancellation, and cleanup. Never concatenate untrusted user input into shell commands. Prefer typed APIs or argument arrays.

## Completion gate
A download is successful only when the process finished, the output exists, size/format checks pass, and the destination is correct. Test failure, cancellation, partial files, and retry behavior. Never claim a file is valid based only on a progress callback.
