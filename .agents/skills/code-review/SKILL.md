---
name: code-review
description: Review a proposed diff for correctness, security, privacy, regressions, maintainability, and missing tests before accepting or merging it.
---

# Code Review

## Review scope
Establish the intended behavior/specification and exact diff range first. Review changed code and the surrounding call paths needed to understand it. Do not review the entire repository unless requested.

## Priority order
1. Critical security/privacy issues and data loss
2. Incorrect behavior, crashes, corruption, and race conditions
3. Lifecycle, cancellation, threading, storage, and compatibility defects
4. Missing tests and failure-path handling
5. Maintainability and unnecessary complexity
6. Style issues only when they reduce clarity or violate established conventions

## Review questions
- Does the implementation satisfy the requirement and preserve existing behavior?
- Are inputs, paths, metadata, and network data validated?
- Are secrets or private URLs exposed in logs or files?
- Are cancellation, retries, concurrency, and cleanup correct?
- Can a failed operation be mistaken for success?
- Are permissions and dependencies necessary?
- Are interfaces small and responsibilities cohesive?
- Are tests meaningful and run against the final change?
- Are docs and migration notes needed?

## Findings format
For each finding include severity (`P0` critical, `P1` high, `P2` medium, `P3` low), file and line, concrete failure scenario, impact, and a focused remediation. Do not invent findings or report speculative style preferences as defects.

## Boundaries
A review is read-only unless the user explicitly asks for fixes. Do not commit, push, merge, or alter history. If no actionable defect is found, state the reviewed scope and checks; do not claim the whole project is bug-free.
