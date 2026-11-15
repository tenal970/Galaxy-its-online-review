---
name: dependency-license-audit
description: Evaluate third-party libraries, Gradle plugins, native binaries, versions, licenses, maintenance, and security before adding or upgrading dependencies.
---

# Dependency and License Audit

## Required checks
For each proposed dependency, identify:
- Exact artifact/package coordinates and selected version
- Official repository and release/source tag
- Supported Android, Kotlin, Gradle, AGP, and JDK range where applicable
- Direct and important transitive dependencies
- License for the library and bundled/native components
- Maintenance signals: recent releases, issue activity, archived status, known deprecations
- Security advisories or known high-impact defects
- Runtime permissions, network access, telemetry, and data-handling behavior
- Binary size and performance impact where relevant

## Source quality
Prefer the official project repository, official documentation, release notes, Maven Central/Google Maven metadata, SPDX license identifiers, and recognized security advisories. Use community posts only as supplementary evidence.

## Rules
- Never invent coordinates, versions, license identifiers, or compatibility claims.
- Do not equate a repository's top-level license with the license of every bundled binary or transitive component.
- Inspect native FFmpeg builds and downloader wrappers separately; identify enabled codecs/features and their licensing implications.
- Do not assume a library is permissively licensed because its wrapper is permissively licensed.
- Do not silently add a dependency, change a major version, or accept a copyleft obligation. Present material licensing consequences for user decision; do not give legal advice.
- Prefer the smallest maintained dependency set that meets requirements.
- Pin versions intentionally and explain update policy. Avoid dynamic versions and unreviewed dependency substitutions.

## Report
Use a compact table with dependency, version, source, license evidence, compatibility, risks, and recommendation. Mark unknowns as unknown. Link sources and record the date checked. If evidence is incomplete, do not approve the dependency yet.
