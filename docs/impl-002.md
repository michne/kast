---
title: CLI control plane
description: The current command-first surface for managing Kast's standalone
  runtime.
icon: lucide/terminal-square
---

# CLI control plane

Kast now exposes a repo-local CLI in front of the standalone daemon. The goal
is simple: callers should be able to start background workspace analysis,
validate that the target workspace is healthy, and then run analysis operations
without managing descriptor files or a foreground process handle themselves.

The CLI keeps descriptor discovery plus HTTP `health`, `runtime/status`, and
`capabilities` checks as internal implementation details.

## Current baseline

Kast currently has one HTTP transport and one supported runtime.

- `analysis-server` owns the HTTP and descriptor lifecycle.
- `backend-standalone` starts a standalone JVM process that initializes a
  headless Analysis API session and then exposes the same HTTP routes.
- `analysis-cli` owns detached startup, readiness checks, and JSON command
  dispatch.

Today, the bootstrap contract is:

1. Ensure a runtime.
2. Read its descriptor file from `<workspace>/.kast/instances/` or
   `KAST_INSTANCE_DIR`.
3. Call `/api/v1/runtime/status`.
4. Call `/api/v1/capabilities`.
5. Send analysis requests over HTTP or stay on the CLI wrapper.

The implemented CLI closes the original workflow gaps:

- It provides a detached launcher for the standalone runtime.
- It reads and validates descriptors in production code.
- It waits on `runtime/status` before issuing analysis commands.

The descriptor default is already workspace-local. When that default is used
inside Git, Kast seeds `/.kast/` into `.git/info/exclude` so the metadata does
not become tracked by accident.

## Requirements

Any wrapper around the current HTTP surface still needs to satisfy these
requirements.

- Start a workspace runtime without keeping an interactive terminal attached.
- Confirm that the selected workspace matches the requested absolute root.
- Reject stale descriptors and dead processes before routing any operation.
- Distinguish `starting`, `indexing`, `ready`, and `degraded` states.
- Keep capability checks honest for the standalone host.
- Stay easy for skills and automation to consume, preferably with stable JSON
  output and predictable exit codes.

## Command surface

The command surface stays small and maps directly to the current workflow.

Every command accepts `--workspace-root=/absolute/path`, and all successful and
failing outputs stay machine-readable.

| Command | Purpose |
| --- | --- |
| `kast daemon start` | Start a detached standalone daemon for a workspace and print its resolved runtime metadata. |
| `kast daemon stop` | Stop the matching standalone daemon and clean up its descriptor if the process is still live. |
| `kast workspace status` | Report descriptor, liveness, health, readiness, backend identity, and capability state for one workspace. |
| `kast workspace ensure` | Ensure that a healthy, ready runtime exists for the workspace, starting one when needed. |
| `kast capabilities` | Return the advertised capabilities after the workspace passes readiness checks. |
| `kast symbol resolve` | Run symbol resolution after `workspace ensure` succeeds. |
| `kast references` | Run reference search after `workspace ensure` succeeds. |
| `kast diagnostics` | Run diagnostics after `workspace ensure` succeeds. |
| `kast rename` | Run rename planning after `workspace ensure` succeeds. |
| `kast edits apply` | Apply text edits after `workspace ensure` succeeds. |

For skills, the most important commands are `kast workspace ensure`,
`kast workspace status`, and the analysis subcommands that keep the current
request and response JSON shapes.

## Execution model

The CLI uses one bootstrap path for every analysis command.

1. Normalize the requested workspace root.
2. Look for a matching descriptor by workspace root and the standalone backend.
3. Validate that the recorded `pid` is still alive.
4. Call `runtime/status`.
5. If no healthy standalone runtime exists, start one in detached mode.
6. Wait until the runtime reports `ready`, or fail with a timeout.
7. Call `capabilities`.
8. Run the requested analysis operation.

This keeps startup, liveness, and capability checks in one place instead of
repeating them in every skill.

## Open decisions

Several design choices still need explicit decisions before implementation.

- Whether the CLI should eventually support a thinner packaging split from the
  standalone runtime implementation
- Whether readiness ever needs a separate on-disk state file for bootstrap
  without sockets
- Whether HTTP should stay the long-term internal transport or give way to
  local IPC later

## Next steps

The next implementation sequence is:

1. Keep the existing CLI surface stable as the standalone backend grows.
2. Add `CALL_HIERARCHY` only when the standalone backend can support it for
   real repositories.
3. Remove the compat JAR once upstream standalone Analysis API catches up.
4. Revisit packaging or transport details only when they become measurable
   sources of complexity.
