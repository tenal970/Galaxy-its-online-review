---
name: ui-ux-design
description: Design or review Android app screens, Compose components, interaction states, visual consistency, accessibility, and localization.
---

# UI and UX Design

## Product direction
The intended visual direction includes a dark gradient default, translucent/frosted cards, adjustable glass strength, a violet accent default (`#8b7cff`), rounded cards and inputs, wallpaper customization, and Thai/English support. Treat these as design direction; inspect current requirements and implementation before assuming any setting exists.

## Design workflow
1. Identify the user's task, primary action, and success state for the screen.
2. Map loading, empty, error, permission-denied, offline, progress, cancellation, and success states as relevant.
3. Reuse existing components and design tokens before inventing new ones.
4. Define hierarchy, spacing, typography, color roles, and interaction feedback consistently.
5. Keep the primary action obvious and avoid decorative UI that competes with it.
6. Check contrast, dynamic text size, screen reader labels, touch targets, keyboard behavior, and small-screen layouts.
7. Use localized string resources. Do not embed Thai or English copy directly in composables.

## Visual constraints
- Avoid emoji as functional icons. Use an intentional, consistent icon source already approved by the project.
- Do not add remote fonts, image services, analytics, or tracking to make the interface look polished.
- Keep glass/transparency effects subtle enough to preserve text contrast and performance.
- Respect system dark/light preferences unless the app's explicit theme setting overrides them.
- Make customizable values persist locally and validate imported font or wallpaper files before use.

## Compose guidance
Prefer small reusable composables with clear state/event contracts. Hoist state only as far as needed. Avoid giant screen composables and excessive abstraction. Keep business logic out of composables.

## Review output
Describe usability problems by impact, provide a concrete correction, and distinguish functional defects from aesthetic preferences. Do not silently redesign unrelated screens.
