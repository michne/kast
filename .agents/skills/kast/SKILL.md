---
name: kast
description: Use the portable `kast` CLI from `PATH`. On match, first verify that `kast` is available and runnable, then run workspace and analysis commands through that executable for the target workspace. Use when Codex needs to start or inspect a Kast daemon, query capabilities, resolve symbols, find references, run diagnostics, plan renames, or apply edit plans. If `kast` is missing, fail clearly instead of probing repo-local wrapper paths, descriptor files, or direct transports.
---

# Kast

Use `kast` from `PATH` and nothing else.

Treat PATH availability as part of the contract for this skill.

## Startup check

Run these checks immediately after matching the skill:

```bash
command -v kast
kast help
```

If either command fails, stop and report that `kast` is not available on
`PATH`.

Do not search for `./kast/build/scripts/kast`.

Do not build a wrapper inside the repo as a fallback.

## Golden path

Run the CLI directly:

```bash
kast \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

That command starts or reuses the standalone daemon for the workspace. Reuse
the same wrapper for every later command.

## Command rules

Use `--key=value` syntax for every option. Pass an absolute
`--workspace-root=...` for the workspace you want to analyze.

Prefer inline CLI arguments for the common query commands:

- `symbol resolve --file-path=... --offset=...`
- `references --file-path=... --offset=... [--include-declaration=true]`
- `diagnostics --file-paths=/absolute/A.kt,/absolute/B.kt`
- `rename --file-path=... --offset=... --new-name=RenamedSymbol [--dry-run=true]`

Use `--request-file=/absolute/path/to/query.json` only when the payload is
already on disk or the command requires it. `edits apply` always requires
`--request-file`.

## Supported commands

Use only these public commands:

- `workspace status`
- `workspace ensure`
- `daemon start`
- `daemon stop`
- `capabilities`
- `symbol resolve`
- `references`
- `diagnostics`
- `rename`
- `edits apply`

Successful results stay on stdout as JSON. Daemon lifecycle notes, when
present, go to stderr.

## Avoid

Do not use repo-local wrapper paths such as `./kast/build/scripts/kast`.

Do not use the old transport helper scripts.

Do not inspect `.kast/instances` or descriptor JSON to decide what to run.

Do not call direct HTTP transports.

Do not invent hyphenated pseudo-operations such as `workspace-status`,
`symbol-resolve`, or `edits-apply`.

Do not use `callHierarchy`; it is still a known gap.
