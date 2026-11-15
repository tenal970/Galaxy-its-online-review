---
name: open-source-handoff
description: Prepare a project for public collaboration and transfer to new maintainers with clear documentation, contribution rules, decisions, and reproducible builds.
---

# Open-Source Handoff

## Goal
Make it possible for a new developer to understand, build, test, change, and responsibly release the project without relying on the original chat or maintainer's memory.

## Inspect before writing
Check which documents already exist and their accuracy. Do not overwrite existing documentation or create boilerplate that the project does not need.

## Documentation to establish as the project matures
- `README.md`: purpose, screenshots when available, supported features, limitations, installation/use, privacy summary, and links.
- `CONTRIBUTING.md`: environment setup, build/test commands, coding conventions, branch/commit/PR workflow, and review expectations.
- `docs/ARCHITECTURE.md`: module map, data flow, key contracts, and boundaries.
- `docs/DECISIONS/`: concise ADRs for material decisions.
- `CHANGELOG.md`: user-visible changes by release.
- `SECURITY.md`: supported versions and responsible vulnerability-reporting process, without publishing private contact details unless approved.
- `LICENSE` and third-party notices: only after the owner explicitly selects the project license and dependency obligations are reviewed.
- Issue/PR templates and code-of-conduct documents when the project is ready to accept community contributions.

## Handoff quality bar
- Document exact prerequisites and tested commands from a clean checkout.
- Distinguish supported behavior from planned features.
- Record known bugs, platform limitations, and non-goals.
- Keep secrets, personal URLs, signing keys, credentials, and private logs out of Git.
- Include dependency/license inventory and native binary notices where required.
- Explain how to run tests and how to reproduce common failures.
- Keep architecture and task handoffs synchronized with actual code, not aspirational designs.

## License and governance safeguards
Never choose a license, transfer ownership, create a release, publish a package, or change repository visibility without explicit user approval. Do not imply that open source means unrestricted use. Flag unresolved licensing questions for the owner.

## Session handoff
When another developer or agent must continue, prepare a short handoff containing objective, verified state, decisions, relevant files, exact test results, blockers, and the first next task. Do not dump the full conversation.
