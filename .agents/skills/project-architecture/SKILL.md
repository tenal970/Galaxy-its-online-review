---
name: project-architecture
description: Plan or review the app architecture, module boundaries, interfaces, data flow, and long-term maintainability without overengineering.
---

# Project Architecture

## Goal
Build the simplest architecture that satisfies verified requirements and leaves clear seams for genuine future growth.

## Before proposing architecture
1. Inspect the current source tree and build configuration. Do not design around files or systems that do not exist.
2. Extract requirements, constraints, non-goals, data flows, and failure cases.
3. Separate confirmed requirements from possible future ideas.
4. Identify the smallest useful end-to-end slice.

## Design rules
- Prefer a small number of cohesive modules with clear responsibilities.
- Keep the initial boundaries aligned with the project direction: `app`, `downloader`, and `verify`, unless evidence supports changing them.
- Prefer deep modules: substantial behavior behind a small, stable interface.
- Keep UI, download orchestration, media validation, and filesystem access separated where that improves testability and safety.
- Define contracts at module boundaries: inputs, outputs, errors, cancellation, ownership, and threading expectations.
- Depend on abstractions only where they protect a real boundary or make a real behavior testable.
- Add a framework, database, DI container, service, or module only when a concrete requirement justifies its ongoing cost.
- Do not add speculative plugin systems, generic factories, event buses, repositories, or layers for hypothetical future features.
- Do not introduce Hilt, Room, Rust, UniFFI, or Clean Architecture ceremony by default.
- Do not split a module merely because it has several files. Split when ownership, build time, testing, or change isolation materially improves.

## Extensibility without complexity
For each proposed extension point, answer:
1. What concrete second implementation or change is expected?
2. Which interface isolates that change?
3. What complexity does the interface add now?
4. Can the same goal be met with a simpler implementation?

Do not build extension points for vague possibilities. Keep interfaces small and avoid leaking implementation details.

## Decisions
Record material, hard-to-reverse decisions as short Architecture Decision Records (ADRs), including context, decision, alternatives, consequences, and date. Do not create an ADR for trivial choices.

## Deliverable
Produce:
- A concise component/module map
- Key data-flow description
- Boundary contracts and error paths
- Main risks and trade-offs
- Minimal implementation sequence
- Decisions that require user approval

Planning is read-only unless the user explicitly asks to create or modify project files. Do not write production code during an architecture-only task.
