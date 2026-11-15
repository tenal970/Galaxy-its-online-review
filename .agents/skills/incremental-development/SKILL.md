---
name: incremental-development
description: Convert requirements into small implementation slices and deliver each slice with focused tests and review.
---

# Incremental Development

## Goal
Avoid giant unreviewable changes. Keep the project in a buildable, understandable state after each meaningful increment.

## Workflow
1. Restate the requested behavior and observable acceptance criteria.
2. Inspect relevant code and existing tests before planning changes.
3. Break work into thin vertical slices that each produce a visible, testable behavior.
4. Identify dependencies and blockers between slices.
5. For each slice, state files likely to change, expected behavior, test, and rollback risk.
6. Implement only the current slice. Avoid unrelated cleanup and speculative features.
7. Run the smallest meaningful verification immediately; then run broader checks when appropriate.
8. Review the diff before moving to the next slice.

## Scope control
- Prefer one behavior change at a time.
- Avoid writing large amounts of code before compiling or testing.
- Do not combine broad refactoring with a feature unless required to make the feature safe.
- Preserve existing behavior unless a change is explicitly requested.
- If the plan conflicts with the codebase or evidence, pause and revise the smallest affected part of the plan.
- Ask for approval before destructive, irreversible, externally visible, or costly actions.

## Tests
Use test-first development when practical, especially for pure logic, validation, parsers, and bug fixes. For UI or platform behavior where test-first is impractical, define observable acceptance criteria and add the most useful test available.

## Progress reporting
At each meaningful checkpoint report: completed slice, changed files, verification command/result, and next slice. Do not claim completion based on code appearance alone.

## Completion
The requested acceptance criteria must be checked against real behavior or test output. List unimplemented criteria and known limitations explicitly.
