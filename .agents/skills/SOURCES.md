# Community references and design notes

Checked: 2026-11-13. These links informed workflow ideas and the Agent Skills layout. This pack contains original project-specific instructions and does not vendor repository code or scripts.

- Agent Skills format and progressive disclosure: https://github.com/microsoft/skills — folder convention, YAML frontmatter, optional references/scripts/assets, and keeping detailed context on demand.
- Open Agent Skills specification: https://openagentskills.dev/docs/specification — progressive disclosure, reference organization, and validation concepts.
- obra/superpowers: https://github.com/obra/superpowers — test-driven development, systematic debugging, planning, and evidence-before-completion practices.
- Addy Osmani agent-skills: https://github.com/addyosmani/agent-skills — specification-first work, incremental implementation, source-driven development, code simplification, and documentation/ADR workflows.
- Matt Pocock skills: https://github.com/mattpocock/skills — module design, architecture improvement, specifications/tickets, code review, and handoff concepts.
- JuliusBrussee skills: https://github.com/JuliusBrussee/skills — concise communication, context awareness, and session control concepts.

Selection notes:
- Community repository popularity is not a security or quality guarantee.
- The Android-specific candidate reviewed during research assumes a stack including Hilt and Room; those are not adopted as mandatory project dependencies.
- No community Skill is installed wholesale. External scripts and runtime integrations are intentionally excluded.
