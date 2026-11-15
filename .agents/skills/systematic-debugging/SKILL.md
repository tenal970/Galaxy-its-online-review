---
name: systematic-debugging
description: Diagnose bugs, crashes, hangs, failed builds, flaky tests, and performance regressions by finding root causes before changing code.
---

# Systematic Debugging

## Core rule
Reproduce, observe, isolate, hypothesize, test, then fix. Never patch a symptom merely to make one run look successful.

## Procedure
1. Capture the exact failure, environment, version, reproduction steps, expected result, and actual result.
2. Reproduce with the smallest reliable test case. If it cannot be reproduced, state that.
3. Collect focused evidence: relevant logs, stack trace, exit code, state transitions, and recent diff. Redact secrets and private URLs.
4. Trace the failure to its earliest incorrect assumption or state transition.
5. List plausible hypotheses and the evidence that would confirm or reject each.
6. Change one cause at a time. Avoid unrelated edits and random dependency upgrades.
7. Add a regression test or repeatable verification that fails before the fix and passes after it.
8. Re-run the regression check and the smallest relevant broader suite.

## Android-specific checks
Consider lifecycle cancellation, process death, permissions, scoped storage, foreground/background restrictions, Doze, memory pressure, ABI/native library mismatch, and device/API differences. Do not assume an emulator reproduces a physical-device issue.

## Failed-build checks
Read the first meaningful compiler/configuration error, not just the final Gradle failure summary. Confirm wrapper, JDK, AGP, Kotlin, SDK, and repository configuration before changing versions.

## Report
State root cause with evidence, exact files changed, why the fix addresses the cause, verification performed, and remaining uncertainty. If evidence is insufficient, propose the next diagnostic step instead of guessing.
