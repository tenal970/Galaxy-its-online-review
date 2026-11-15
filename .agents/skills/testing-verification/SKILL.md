---
name: testing-verification
description: Define and run proportionate tests for features and bug fixes, verify build results, and prevent unsupported completion claims.
---

# Testing and Verification

## Before testing
Translate requirements into observable acceptance criteria. Identify the narrowest test that proves the changed behavior and the broader checks needed to detect regressions.

## Test selection
- Pure logic, URL parsing, filename sanitization, and media validation: unit tests with boundary and malformed inputs.
- Coroutine/state logic: deterministic tests for success, failure, cancellation, retries, and concurrency where relevant.
- Compose UI: tests for primary interactions, state rendering, accessibility labels, and error/progress states where infrastructure exists.
- Android integrations: instrumentation or device checks for storage, permissions, lifecycle, and native libraries when needed.
- Build/configuration changes: run the relevant Gradle configuration/build tasks.

## Quality rules
- Prefer deterministic tests; avoid arbitrary sleeps and real network dependencies.
- Include negative cases and edge cases, not only happy paths.
- Do not weaken assertions or delete failing tests merely to get a green build.
- Do not claim coverage percentages unless measured by a coverage tool.
- Do not add large test frameworks without a clear need.

## Verification protocol
1. Name the exact command before running it.
2. Run it in the actual environment.
3. Read the relevant output and exit status.
4. Distinguish passed, failed, skipped, and not run.
5. Re-run the final relevant verification after the last code change.

## Completion gate
A feature is not verified merely because it compiles. Check acceptance criteria and important failure paths. If SDKs, devices, credentials, or network access are unavailable, say exactly what could not be verified and provide the command the maintainer should run.
