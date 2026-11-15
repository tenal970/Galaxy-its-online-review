---
name: android-development
description: Implement or modify native Android features using Kotlin and Jetpack Compose while respecting platform lifecycle, storage, permissions, and compatibility.
---

# Android Development

## Project assumptions
Kotlin and Jetpack Compose are the preferred direction, not proof that a project is already configured that way. Inspect Gradle, plugins, SDK levels, source sets, and existing conventions first.

## Before coding
1. Identify the Android component and lifecycle involved.
2. Check current official Android/Kotlin/Compose documentation for APIs whose behavior or compatibility matters.
3. Confirm compile SDK, target SDK, min SDK, AGP, Gradle, Kotlin, and JDK versions from the repository. Never guess or upgrade them casually.
4. Follow existing project conventions unless they are unsafe or demonstrably broken.

## Implementation rules
- Keep UI state and business logic separated enough to test without introducing unnecessary architecture.
- Use lifecycle-aware collection and cancellation for asynchronous work.
- Never block the main thread with downloads, conversions, filesystem scans, or expensive processing.
- Represent loading, success, failure, and cancellation as explicit states; avoid stale progress or false success.
- Request only permissions required for the exact feature.
- Prefer the Storage Access Framework and user-selected destinations where appropriate; respect scoped storage.
- Handle process recreation and configuration changes deliberately.
- Make accessibility, content descriptions, touch targets, and system insets part of implementation.
- Avoid hardcoded user-visible strings; use Android resources and preserve Thai/English localization.
- Do not add libraries for behavior that the platform or current code can handle simply.

## Compatibility and APIs
Use official documentation and release notes to verify API availability and behavior. Clearly distinguish minimum-supported behavior from newer optional behavior. Do not silence deprecation warnings without understanding their replacement.

## Build discipline
Make the smallest relevant build/test first. Report exact command, exit status, and meaningful output. If Android SDK/platform tools are unavailable in the current environment, state that limitation instead of claiming a successful build.
