# Copilot instructions

- TDD: write failing unit tests first. Every change must include tests that prove behavior and regressions are covered.
- Kotlin standards: follow Kotlin style, apply formatting and lints (ktlint/detekt/spotless), avoid platform-specific APIs in shared modules.
- Constitutional code: treat API/model changes as contract changes; preserve schema compatibility and capability advertising unless intentionally changing.
- Clean code: prefer small, single-responsibility units, clear names, and minimal surface area.

Process to keep the agent moving on a robust, testable path:
1. Choose the narrowest owning unit.
2. Add failing tests (unit tests first).
3. Implement the smallest, well-tested change.
4. Run LSP/IDE diagnostics and the narrowest Gradle task that proves the change.
5. Fix type/import errors, run the module's test task, then broader integration tasks as needed.
6. Update `AGENTS.md`/docs when behavioral or contract rules change.
7. Ensure local `./build.sh` and CI pass before merging.

Use LSP/IDE for navigation and impact analysis (goToDefinition, findReferences, incoming/outgoingCalls) before renames or signature changes.
