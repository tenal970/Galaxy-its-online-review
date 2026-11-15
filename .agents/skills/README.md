# Galaxy it's online — Agent Skills Pack v1

A project-specific set of 12 original Agent Skills for the Galaxy it's online workspace. The Skills apply engineering practices inspired by public community resources; their instructions were written for this project rather than copied wholesale.

## Included Skills

| Skill | Purpose |
|---|---|
| `context-efficiency` | Reduce repeated context and unnecessary output without losing technical precision; prepare compact handoffs. |
| `project-architecture` | Keep architecture simple, modular, testable, and extensible only where justified. |
| `incremental-development` | Break work into small verified implementation slices. |
| `android-development` | Kotlin/Compose Android platform and lifecycle guidance. |
| `ui-ux-design` | UI consistency, accessibility, localization, and the app's intended visual direction. |
| `media-security` | URL handling, downloads, conversion, safe paths, and media output verification. |
| `dependency-license-audit` | Verify dependencies, versions, compatibility, licenses, and native binaries. |
| `systematic-debugging` | Reproduce and diagnose root causes before fixing. |
| `testing-verification` | Test acceptance criteria and require fresh evidence before completion claims. |
| `code-review` | Read-only diff review for correctness, security, privacy, and maintainability. |
| `open-source-handoff` | Prepare docs and reproducible workflows for future contributors and maintainers. |
| `technical-research` | Research current APIs, GitHub projects, and primary sources safely. |
| `skill-maintenance` | Create and audit Skills safely while controlling metadata/context overhead. |

## Installation

Extract this pack into the root of the Galaxy it's online workspace. It contains only `.agents/skills/` paths and does not include or replace `AGENTS.md` or the existing `project-orientation` Skill. Use a no-overwrite extraction option.

After installation, inspect the file list and test explicit invocation in Antigravity. Explicit Skill loading is not proof that automatic activation works; test those separately.

## Token and context strategy

The Skills are separated by task so an agent can invoke only the relevant procedure. Their descriptions are deliberately short. Detailed references are not loaded by default because this pack does not need any large reference payloads. The exact metadata-loading and automatic-activation behavior depends on the Antigravity version and must be verified in the app.

A concise-response Skill can reduce unnecessary output, but does not guarantee a reduction in input tokens, model reasoning cost, or overall quota. Preserve exact commands, code, version strings, logs, and security details.

## Safety and scope

- These files are instruction documents, not executable code.
- No external scripts, binaries, dependencies, hooks, or telemetry are installed.
- No project source code or build configuration is changed by this pack.
- Do not import community Skills or run their scripts without a separate license/security review.
- The project license remains undecided; this pack does not choose one.

## Validation checklist

1. Confirm every new Skill has `SKILL.md` with matching kebab-case `name` and non-empty `description`.
2. Confirm `.agents/skills/project-orientation/SKILL.md` remains unchanged.
3. Invoke a few Skills explicitly in Antigravity.
4. Test whether the runtime auto-selects relevant Skills; do not assume it does.
5. Verify the agent follows read-only boundaries when a Skill says it is planning or reviewing only.
