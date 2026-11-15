# Galaxy it's online - Master Project Instructions

## 1. Project Identity

Galaxy it's online is an Android-first media downloader and converter.

The project aims to provide a reliable, privacy-focused application with a clean user experience.

The workspace is under active development. Always inspect the current project state before making decisions.

Never assume that a feature, file, dependency, configuration, or test already exists.

## 2. Core Principles

Priorities, in order:

1. User privacy and security.
2. Data integrity and reliability.
3. Correct functionality.
4. Maintainable architecture.
5. Performance and resource efficiency.
6. User experience.

Do not sacrifice security or data integrity for convenience.

## 3. Privacy Requirements

- No advertisements.
- No analytics or tracking.
- No unnecessary data collection.
- No hidden telemetry.
- No third-party crash-reporting SDKs unless explicitly approved.
- Do not upload user data, download history, URLs, or logs to external services.
- Store user preferences locally whenever practical.
- Do not save download history or logs by default.
- Any optional log-saving feature must be explicitly enabled by the user.
- Avoid logging authentication tokens, cookies, private URLs, or other sensitive information.
- Runtime network access must be limited to functionality the user requests and explicitly disclosed update mechanisms.

Privacy must be considered during architecture and dependency selection, not added as an afterthought.

## 4. Security Requirements

Treat all external input and downloaded content as untrusted.

- Never execute downloaded files.
- Never assume a file is safe based only on its name or extension.
- Verify file signatures and actual content before accepting media output.
- Reject files that do not match the expected media format.
- Reject executable or application-package content when a media file is expected.
- Handle malformed files and unexpected responses safely.
- Do not expose private filesystem paths or sensitive information.
- Do not use shell commands with unsanitized user input.
- Avoid unsafe temporary-file handling.
- Prevent path traversal and accidental writes outside the intended destination.
- Never overwrite an existing user file without explicit authorization.
- Use secure defaults for network connections and temporary data.

For media verification, consider format signatures such as:

- MP4, M4A, and MOV: ISO Base Media File Format signature, including `ftyp` at the expected location.
- WebM and Matroska: EBML signature `1A 45 DF A3`.
- MP3: ID3 header or a valid MPEG audio frame sync.

These signatures are examples, not a complete validation implementation. Verify format requirements before implementing detection.

## 5. Product Direction

The planned application direction includes:

- Android-native application.
- Kotlin and Jetpack Compose as the preferred UI direction.
- Media downloading and conversion.
- MP3 and MP4 output options.
- Quality selection where supported.
- Download progress and cancellation.
- User-controlled save locations.
- Local preferences and customization.
- Thai and English language support.

These are project goals, not evidence that the features already exist.

Do not report a feature as implemented until its code and behavior have been verified.

## 6. Architecture Guidelines

Keep the initial architecture as small as reasonably possible.

The planned initial module boundaries are:

- `app`: Android application and UI.
- `downloader`: Media download functionality.
- `verify`: Media validation and file integrity checks.

Do not add modules, frameworks, databases, dependency-injection systems, or native components without a clear technical reason.

Do not introduce Rust, UniFFI, or additional architectural layers unless the project requirements justify them.

Keep responsibilities separated and avoid unnecessary complexity.

## 7. Dependencies and Technical Decisions

Never invent dependency coordinates, API names, versions, or compatibility claims.

Before adding a dependency:

1. Find its official documentation or repository.
2. Verify the correct package coordinates and current supported version.
3. Check Android and Gradle compatibility.
4. Review its license and relevant transitive dependencies.
5. Consider privacy, security, maintenance, and project size.
6. Explain why the dependency is necessary.

Do not blindly use the newest version.

Do not change Gradle, Android Gradle Plugin, Kotlin, SDK, or Java versions without checking compatibility.

Prefer official documentation and primary sources over random tutorials.

## 8. Android Development Requirements

- Use the minimum permissions necessary.
- Prefer Android Storage Access Framework or other appropriate scoped-storage mechanisms for user-selected files.
- Do not request unrelated permissions.
- Do not perform long-running work on the main UI thread.
- Handle lifecycle changes and cancellation safely.
- Keep UI state consistent with actual download and conversion states.
- Do not report success unless the output file has been verified.
- Handle failures without leaving misleading progress indicators or stale results.

## 9. File and Workspace Safety

Before modifying anything:

1. Inspect the relevant files.
2. Understand the existing implementation.
3. Identify dependencies and affected components.
4. Explain the planned changes when the task is substantial.

Strict rules:

- Do not delete project files without explicit authorization.
- Do not overwrite existing user work without explicit authorization.
- Do not run broad cleanup commands.
- Do not modify files outside the current workspace unless explicitly authorized.
- Do not reset, rebase, force-push, or rewrite Git history without explicit authorization.
- Do not expose or inspect secrets, credentials, or tokens.
- Preserve existing work whenever possible.

If a requested change could destroy data or cause irreversible effects, stop and explain the risk before proceeding.

## 10. Testing and Verification

After making changes:

- Run relevant tests.
- Run appropriate static checks when available.
- Build the affected components when possible.
- Verify that the expected files were actually created.
- Inspect errors instead of hiding or ignoring them.
- Do not claim that a build or test passed unless it actually ran successfully.

If a test cannot be run, explain why.

Never fabricate test results.

## 11. Implementation Workflow

For each development task:

1. Inspect the current workspace and relevant source files.
2. Identify the requested behavior and existing constraints.
3. Check official documentation when APIs or dependencies are uncertain.
4. Present a short implementation plan for substantial changes.
5. Modify only the necessary files.
6. Test the implementation.
7. Review the final changes for regressions and security issues.
8. Report the outcome.

Keep changes focused. Do not silently expand the task.

## 12. Completion Report

At the end of each task, report:

- What was implemented.
- Files created or modified.
- Dependencies added or changed.
- Tests and build commands executed.
- Actual test results.
- Known issues or limitations.
- Any decisions that still require user approval.

Separate verified facts from assumptions.

## 13. Final Rule

The user's data, privacy, and control over the project take priority.

When uncertain, inspect and verify instead of guessing.

Do not perform destructive or irreversible actions without explicit authorization.
