# Galaxy it's online — Phase 1: Direct Download Foundation

## Overview
Phase 1 establishes the robust, persistent, and secure foundation for **Galaxy it's online**:
- Multi-module Gradle Kotlin DSL architecture (`:app`, `:downloader`, `:verify`).
- Centralized Version Catalog (`gradle/libs.versions.toml`).
- RFC 9110 compliant HTTP/HTTPS streaming engine with resume support (`HttpRangeDownloadEngine`).
- Persistent storage manager using app-private non-evictable storage (`context.filesDir/downloads_in_progress/`) and atomic recovery manifests (`PersistentStorageManager`).
- Multi-layer media signature and anti-execution security validator (`DefaultMediaVerifier`).
- Storage Export Broker for Android Storage Access Framework (SAF) integration (`StorageExportBroker`).

---

## Architecture Boundaries & Modules

### 1. `:verify` (Pure Kotlin / JVM)
- **Responsibility:** File security, threat detection, and container signature validation.
- **Threat Signatures Rejected:**
  - Linux/Android ELF binaries (`\x7FELF`)
  - Android Dalvik Executables (`dex\n`)
  - Windows Portable Executables (`MZ`)
  - ZIP / APK / JAR raw archives (`PK\x03\x04`)
  - Executable Shell scripts (`#!`)
  - Disguised HTML/XML error bodies (Captive portals / 403 blocks)
- **Container Signatures Verified:**
  - ISO-BMFF (`ftyp` box for MP4 / M4A)
  - EBML (`\x1A\x45\xDF\xA3` for WebM / MKV)
  - ID3v2 (`ID3` header for MP3)
  - MPEG Frame Sync (`0xFF` + `0xE0..0xFF` for raw MP3 frames)
  - JPEG (`\xFF\xD8\xFF`) and PNG (`\x89PNG`)

### 2. `:downloader` (Pure Kotlin / JVM + OkHttp)
- **Responsibility:** Network streaming, recovery manifests, Range requests, and Storage export.
- **Key Features:**
  - Direct streaming to disk (zero in-memory buffering of media streams).
  - Handles `Range: bytes=X-` and `If-Range` with Strong ETag validation (rejects weak `W/` tags).
  - Handles `206 Partial Content` by appending to existing partial file.
  - Handles `200 OK` safely by truncating and restarting from byte 0 (never corrupting partial bytes).
  - Handles `416 Range Not Satisfiable` by verifying completion or resetting safely.
  - Periodic manifest checkpoint flushing (1 MB heuristic) for crash recovery.
  - SAF Export verification: Validates signature and exact bytes written before deleting task data.

### 3. `:app` (Android Application)
- **Responsibility:** UI presentation (Jetpack Compose), Scoped Storage integration, and Task Manager lifecycle.
- **Android Configuration:**
  - `minSdk = 26`
  - `compileSdk = 36`
  - `targetSdk = 36`

---

## State Model & Task Recovery

```
    [ QUEUED ]
        │
        ▼
    [ ACTIVE ] ──(Pause/Interruption)──► [ PAUSED / INTERRUPTED ]
        │                                        │
        │                                  (User Resume)
        │                                        │
        ├────────────────────────────────────────┘
        ▼
   [ COMPLETED ] ──(SAF Export Gate)──► [ EXPORTED & REMOVED ]
        │
        ├──(Permanent Cancel)─────────► [ CANCELLED & REMOVED ]
        └──(Security Violation)───────► [ FAILED / CORRUPTED ]
```

---

## Verification & Test Results
- **DefaultMediaVerifier Test Suite:** 9/9 tests passed (valid MP4, WebM, MP3 ID3, MP3 Sync, rejection of ELF, DEX, PE, ZIP, and HTML disguised payloads).
- **HttpRangeDownloadEngine Test Suite:** 4/4 integration spec tests passed (HTTP 200 clean download, HTTP 206 range resume, 200-restart truncation, ETag invalidation).
