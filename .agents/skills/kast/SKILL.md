---
name: kast
description: >
  Use this skill for any Kotlin/JVM semantic code intelligence task: resolve a symbol,
  find references, run diagnostics, plan a rename, apply edits. Triggers on:
  "resolve symbol", "find references", "kast", "rename symbol", "run diagnostics",
  "apply edits", "symbol at offset", "semantic analysis", "kotlin analysis daemon".
  The daemon lifecycle is fully managed by this skill. Single pathway for every operation.
---

# Kast Skill

kast is a Kotlin semantic analysis daemon. It provides symbol resolution, reference
finding, diagnostics, rename planning, and edit application for Kotlin/JVM workspaces.

---

## 0. Bootstrap (run once per session)

Locate the skill root and resolve the kast binary. Do this before anything else.

```bash
SKILL_ROOT="$(cd "$(dirname "$(find "$(git rev-parse --show-toplevel)" \
  -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit)")" && pwd)"
KAST="$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")"
```

`$KAST` is the verified kast binary. `$SKILL_ROOT` is the absolute path to bundled
scripts. Use both variables for every subsequent invocation in this session.

Then ensure the workspace daemon is ready:

```bash
"$KAST" workspace ensure --workspace-root="$(git rev-parse --show-toplevel)"
```

`workspace ensure` starts the daemon if none exists, reuses an existing healthy one,
and blocks until the daemon reaches `READY` (60 s timeout). Exit 0 = daemon ready.

### If `workspace ensure` fails

Read the daemon log — it is the authoritative source for the failure:

```bash
cat "$(git rev-parse --show-toplevel)/.kast/logs/standalone-daemon.log" | tail -60
```

| Log message | Fix |
|-------------|-----|
| `SocketException: Operation not permitted` | Unix domain sockets are blocked. Run this command outside the sandboxed environment. |
| `Address already in use` | Run `"$KAST" daemon stop --workspace-root=<abs-path>` then re-run `workspace ensure`. |
| `OutOfMemoryError` | Export `KAST_DAEMON_OPTS=-Xmx2g` and re-run `workspace ensure`. |
| Java version errors | Install Java 21+ and re-run. |

Do not retry `workspace ensure` without first resolving the root cause shown in the log.

---

## 1. Index Validation (after first ensure in a new workspace)

`workspace ensure` reaching `READY` does not guarantee the index is serving semantic
results. Validate once with a known symbol before doing real work:

```bash
# Pick any class declaration in the workspace — adjust paths as needed
grep -rn "^class \|^object \|^interface " --include="*.kt" \
  "$(git rev-parse --show-toplevel)/src" | head -1
```

Take the file path and symbol name from that output, then:

```bash
python3 "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt --symbol SymbolName

"$KAST" symbol resolve \
  --workspace-root="$(git rev-parse --show-toplevel)" \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset-from-above>
```

If `symbol resolve` returns a valid `fqName`, the index is healthy. If it returns
`NOT_FOUND` and state is `READY`, wait 15 seconds and retry — the initial index pass
may still be running.

---

## 2. Symbol Lookup (name → offset)

All kast commands require `--offset` (a zero-based UTF-16 character offset). When the
user gives a symbol by name, derive the offset in two steps.

**Step 1 — Find the declaration:**

```bash
# class / object / interface / sealed / enum
grep -rn "\bclass SymbolName\b\|\bobject SymbolName\b\|\binterface SymbolName\b" \
  --include="*.kt" "$(git rev-parse --show-toplevel)"

# function
grep -rn "\bfun SymbolName\b" --include="*.kt" "$(git rev-parse --show-toplevel)"

# val / var property
grep -rn "\bval SymbolName\b\|\bvar SymbolName\b" \
  --include="*.kt" "$(git rev-parse --show-toplevel)"
```

Pick the declaration line (the hit that contains `class`/`fun`/`val`/`var`/etc.)
over plain usage lines.

**Step 2 — Compute the offset:**

```bash
python3 "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt --symbol SymbolName
```

Output: `<offset>\t<line>\t<col>\t<context>` — one line per match, declaration sites
first. Take the first line's `<offset>`.

To target a specific line instead of a name:

```bash
python3 "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt --line <1-based-line> --col <0-based-col>
```

**Step 3 — Confirm identity:**

```bash
"$KAST" symbol resolve \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset>
```

Check that `symbol.fqName` matches what the user described. If not, advance the offset
by a few characters toward the start of the identifier and retry.

---

## 3. Analysis Commands

All commands:
- Write JSON to stdout; write daemon lifecycle notes to stderr
- Use `--key=value` for every flag
- Require absolute paths for `--workspace-root`, `--file-path`, and `--file-paths`
- `--offset` = zero-based UTF-16 character offset from start of file

### symbol resolve

```bash
"$KAST" symbol resolve \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset>
```

Key output: `symbol.fqName`, `symbol.kind`, `symbol.location`, `symbol.type`

### references

```bash
"$KAST" references \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --include-declaration=true
```

Key output: `references` (array of `Location`), `declaration`, `page.truncated`

If `page.truncated = true`, the result is capped at `limits.maxResults`. There is no
pagination — this is the full available set.

### diagnostics

```bash
"$KAST" diagnostics \
  --workspace-root=/absolute/workspace/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

Key output: `diagnostics[].severity` (`ERROR`|`WARNING`|`INFO`), `.message`, `.location`

An empty `diagnostics` array means the files are clean.

### rename (dry-run plan)

```bash
"$KAST" rename \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName \
  --dry-run=true
```

Key output: `edits`, `fileHashes` (SHA-256 integrity tokens), `affectedFiles`

Pass `fileHashes` unchanged to `edits apply`. Do not recompute or manipulate them.

### edits apply

Always supply the apply request via `--request-file`. Never construct inline JSON.
Use `kast-plan-utils.py` to extract the request from the rename plan output:

```bash
"$KAST" rename \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName \
  --dry-run=true > /tmp/rename-plan.json

python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" \
  extract-apply-request /tmp/rename-plan.json /tmp/apply-request.json

"$KAST" edits apply \
  --workspace-root=/absolute/workspace/path \
  --request-file=/tmp/apply-request.json
```

Key output: `applied` (written edits), `affectedFiles`

---

## 4. Workflows

### Resolve or find references for a named symbol

```
1. Bootstrap (Section 0)
2. grep for declaration → identify file + symbol name
3. find-symbol-offset.py --symbol <Name> → take first offset
4. symbol resolve → confirm fqName
5. references → consume results
```

### Pre-edit impact assessment

Before modifying a symbol:

```
1. Bootstrap (Section 0)
2. Symbol lookup (Section 2) → offset
3. symbol resolve → confirm identity and kind
4. references → count call sites and assess impact
```

### Safe rename

Use the one-shot script. It runs workspace ensure, plans the rename, extracts the apply
request without `jq`, applies the edits, runs diagnostics, and exits non-zero if any
`ERROR`-severity diagnostics remain.

```bash
bash "$SKILL_ROOT/scripts/kast-rename.sh" \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName
```

If `kast-rename.sh` exits non-zero due to `CONFLICT`: files changed between plan and
apply. Re-run `kast-rename.sh` — it will produce a fresh plan with updated hashes.

### Post-edit validation

After any code change (rename, manual edit, or generated edit):

```
1. workspace ensure --workspace-root=<abs-path>
2. diagnostics --file-paths=<comma-separated list of modified files>
3. python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" check-diagnostics /tmp/diag.json
4. Fix any ERROR-severity diagnostic, then repeat from step 2
```

### Diagnostic triage

When a build fails or a file has unknown errors:

```
1. Bootstrap (Section 0)
2. diagnostics --file-paths=<file>
3. For each ERROR: symbol resolve at diagnostic startOffset → identify the symbol
4. references → check for broken call sites
5. Fix, then diagnostics again to confirm clean
```

---

## 5. Error Reference

| Error / Symptom | Cause | Fix |
|-----------------|-------|-----|
| `VALIDATION_ERROR` (400) | Bad parameters | Fix the file path, offset, or name and retry |
| `NOT_FOUND` (404) | Offset on whitespace or still indexing | Run `find-symbol-offset.py` to land on the identifier. If state is `READY`, wait 15 s and retry. |
| `CONFLICT` (409) | Files changed since rename plan | Re-run `kast-rename.sh` — it produces a fresh plan |
| `CAPABILITY_NOT_SUPPORTED` (501) | Capability absent | Run `"$KAST" capabilities` to confirm. Use grep for text search; kast does not do it. |
| `APPLY_PARTIAL_FAILURE` (500) | Disk or permissions error mid-apply | Inspect `details` map; fix the root cause and apply remaining edits manually |
| `workspace ensure` timeout | Daemon failed before reaching READY | Read `.kast/logs/standalone-daemon.log` — the cause is always there |

---

## 6. Rules

- Use `--key=value` syntax for every flag.
- All `--workspace-root`, `--file-path`, and `--file-paths` values must be absolute paths.
- Always use `--request-file` for `edits apply`. Never construct inline JSON.
- Never use `jq` — use `kast-plan-utils.py` for all JSON operations.
- Never call `callHierarchy` — it is not implemented.
- Never use hyphenated pseudo-commands (`workspace-status`, `symbol-resolve`, `edits-apply`).
- When `workspace ensure` fails, read the log before doing anything else.

---

## 7. Integration

| Task | Tool |
|------|------|
| Resolve a symbol at an offset | kast `symbol resolve` |
| Find all references across workspace | kast `references` |
| Get compile errors for a file | kast `diagnostics` |
| Rename a symbol safely | `kast-rename.sh` |
| Find text in comments or strings | Grep — kast does not search text |
| Build the project | `kotlin-gradle-loop` skill / `./gradlew build` |
| Run tests | `kotlin-gradle-loop` skill / `./gradlew test` |

kast = semantic intelligence (what is this symbol, where is it used, rename it).
kotlin-gradle-loop = build and test iteration (does it compile, do tests pass).
