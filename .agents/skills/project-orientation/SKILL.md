---
name: project-orientation
description: Use when starting work in Galaxy it's online next to inspect the project structure, current implementation status, and available development tools without modifying files.
---

# Project Orientation

## Purpose
Inspect the current project before starting any development task.

## Safety Rules
- This skill is strictly read-only.
- Never create, edit, move, rename, or delete files.
- Never install or uninstall packages.
- Never modify Git configuration, branches, commits, or remotes.
- Never execute commands that modify the system.
- Never inspect files outside the current workspace unless explicitly authorized.
- Do not read secrets, credentials, tokens, or private configuration files.

## Inspection Procedure
1. Confirm the current workspace path.
2. Inspect the project directory structure.
3. Identify existing documentation and source files.
4. Identify build configuration and available test configuration.
5. Identify available development tools and versions.
6. Report missing or unverified information explicitly.

## Report Format
- Workspace location
- Project structure
- Existing implementation
- Build system
- Available tools
- Missing components
- Recommended next step

Never assume a file, dependency, feature, or test exists without verifying it.
