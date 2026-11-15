---
name: context-efficiency
description: Reduce unnecessary token and context use while preserving technical accuracy; use for long sessions, large repositories, compact responses, or agent handoffs.
---

# Context Efficiency

## Goal
Spend context on decisions and evidence, not repetition. Preserve correctness; never compress away details needed to execute or verify work.

## Working rules
1. Start with the user's current task and the smallest relevant context. Do not reread the entire repository or conversation by default.
2. Inspect directory trees shallowly first. Search targeted symbols, filenames, and call sites; open only relevant ranges. Expand the search only when evidence requires it.
3. Prefer concise command output (`--stat`, targeted `grep`, selected `sed` ranges, test summaries). Avoid dumping binary files, generated output, dependency trees, or huge logs into the conversation.
4. Summarize findings with file paths, line/range hints, and conclusions. Keep raw output only when it is evidence needed to diagnose or reproduce a problem.
5. Do not repeat facts already established in current project documentation. Check that documentation is current before relying on it.
6. Keep user-facing updates short: status, decision, changed files, verification, blockers. Do not narrate every command.
7. If the task is large, create or update a concise task plan and work one verified slice at a time.
8. If context is becoming unreliable or a session may end, create a compact handoff using the template below. Do not attempt to preserve the whole conversation.

## Preserve exactly
Never paraphrase or truncate when exactness matters: commands, paths, filenames, API names, dependency coordinates, version numbers, hashes, error messages, stack traces, test names, security findings, and user requirements.

## Token-saving boundaries
- Do not use cryptic shorthand in source code, APIs, documentation, or user instructions merely to save tokens.
- Do not remove explanations that prevent a dangerous or irreversible mistake.
- Do not claim a measured token reduction unless actual usage metrics were measured. Concise output does not prove lower input-token cost.
- Do not change model reasoning quality or skip tests to save tokens.
- Avoid repeatedly loading large reference files; extract only the needed section and record stable conclusions when appropriate.

## Handoff template
When needed, create/update `docs/HANDOFF.md` (create `docs/` only if appropriate) with:
- Current goal and status
- Verified project state
- Decisions and reasons
- Files changed and important paths
- Commands/tests run and actual results
- Known problems and unresolved questions
- Exact next action
- Relevant source links

Exclude secrets, tokens, private URLs, and irrelevant chat history. Mark assumptions clearly. Keep it short enough for a new agent to act without rereading the whole session.

## Completion
Report only relevant findings. Separate verified facts from assumptions. State what was not checked.
