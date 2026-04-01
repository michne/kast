---
title: Implementation plan
description: Sequencing plan for the CLI-managed standalone runtime.
icon: lucide/clipboard-list
---

# Implementation plan

This plan captures the remaining implementation order after the repository
moved to a CLI-managed standalone runtime. Read it as a sequencing target, not
as a statement that every phase is already complete.

### Phase 1: Stabilize the standalone-only architecture

Build order, each step validates the previous:

1. **`:analysis-api`** — Keep `AnalysisBackend`, model types, and capability
   enums stable. This is the contract everything else depends on.

2. **`:shared-testing`** — Keep the fake backend and contract test suite honest
   so every production path still conforms to the same response shapes.

3. **`:analysis-server`** — Keep Ktor routes and descriptor handling stable so
   the HTTP layer stays testable and backend-agnostic.

4. **`:backend-standalone`** — Keep `resolveSymbol`, `findReferences`,
   `diagnostics`, `rename`, and `applyEdits` working end to end through the
   Kotlin Analysis API session.

5. **`:analysis-cli`** — Keep `workspace status`, `workspace ensure`, daemon
   lifecycle commands, and analysis subcommands deterministic and JSON-first.

### Phase 2: Harden

- File content hashing to detect stale edits
- Standalone backend: warm index cache on startup, incremental re-index on file
  change notification
- Remove the compat JAR once upstream standalone Analysis API aligns with
  stable IntelliJ `2025.3` internals
- Schema version enforcement (server rejects clients with mismatched
  `schema_version`)

### Phase 3: Expand

- Additional mutations: `extractFunction`, `inlineVariable`, `safeDelete` when
  the standalone backend can plan them safely
- Workspace-wide operations: `workspaceSymbols` (fuzzy symbol search)
- Event stream: SSE endpoint for push-based diagnostics (file-save triggered)
