---
title: Use Kast from an LLM agent
description: Resolve Kotlin symbols and references through the packaged
  `kast` skill and its CLI discovery workflow.
icon: lucide/book-open
---

This guide shows how to use the packaged `kast` skill when an LLM agent needs
semantic Kotlin lookup. The workflow stays narrow on purpose: discover the
CLI, ensure the workspace daemon, resolve the symbol, and then expand to
references in the same workspace.

!!! note
    The current packaged skill does not support `callHierarchy`. Use
    `symbol resolve` and `references` for semantic navigation today.

## Take the shortest path

If you want the fastest reliable workflow, follow this sequence before you add
anything more advanced.

1. Give the agent an absolute workspace root, an absolute file path, and a
   zero-based UTF-16 offset.
2. Ask the agent to use the packaged `kast` skill.
3. Resolve the symbol first so the declaration identity is explicit.
4. Run `references` against the same file path and offset.
5. Report the declaration location, usage previews, and whether
   `page.truncated` is `true`.

## Understand what the packaged skill handles

The packaged skill removes a few decisions from the prompt so the agent does
not guess at environment details or CLI behavior.

- **CLI discovery:** The skill runs
  `bash .agents/skills/kast/scripts/resolve-kast.sh` so it can find `kast` on
  `PATH`, in local build output, in `dist/`, or through a one-time wrapper
  build.
- **Workspace lifecycle:** The skill runs `workspace ensure` before analysis so
  symbol and reference queries hit a ready daemon instead of a cold workspace.
- **CLI contract:** The skill uses `--key=value` syntax, absolute paths, and
  stdout JSON as the semantic result.
- **Control-plane separation:** The skill treats stderr as daemon notes, not as
  the semantic answer.
- **Failure handling:** The skill is expected to deal with missing
  capabilities, `NOT_FOUND`, and other common runtime errors instead of
  pretending the query succeeded.

## Prepare the request

The skill still needs concrete input from you. If you keep these values
explicit, the agent can run deterministically and summarize the result without
guessing.

- **Workspace root:** Pass the Kotlin workspace root as an absolute path, for
  example `/absolute/path/to/workspace`.
- **File path:** Pass the target Kotlin file as an absolute path inside that
  workspace.
- **Offset:** Pass a zero-based UTF-16 character offset from the start of the
  file.
- **Declaration preference:** For `references`, decide whether you want the
  declaration returned in the same payload with
  `--include-declaration=true`.
- **Output expectation:** Tell the agent what to summarize, for example
  `fqName`, symbol kind, declaration location, and callers.

!!! note
    If you only know a line and column, convert them to a zero-based UTF-16
    offset before you ask the agent to run Kast. Line and column coordinates
    are not accepted by the CLI.

## Resolve the symbol first

Use `symbol resolve` before you ask for broader impact. This anchors the rest
of the workflow on the correct declaration instead of forcing the agent to
infer identity from raw text.

Example prompt for the agent:

```text
Use the packaged `kast` skill to resolve the symbol at
/absolute/path/to/src/main/kotlin/sample/Use.kt
offset 41
in workspace /absolute/path/to/workspace.

Return the fully qualified name, kind, declaration file, line, column,
and type if present.
```

The packaged skill aligns that request to this command sequence:

```bash
KAST=$(bash .agents/skills/kast/scripts/resolve-kast.sh)
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
"$KAST" symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/sample/Use.kt \
  --offset=41
```

When the result comes back, the agent must focus on these fields:

- `symbol.fqName` for the stable symbol identity
- `symbol.kind` to confirm the target type, such as `FUNCTION` or `PROPERTY`
- `symbol.location.filePath`, `startLine`, and `startColumn` for the
  declaration anchor
- `symbol.type` and `symbol.containingDeclaration` when they are present

## Expand to references

Use `references` after symbol resolution when you want caller discovery, usage
sites, or a rough change-impact check. Ask for the declaration in the same
payload when you want one response that contains both the definition and the
usage list.

Example prompt for the agent:

```text
Use the packaged `kast` skill to find references for the symbol at
/absolute/path/to/src/main/kotlin/sample/Use.kt
offset 41
in workspace /absolute/path/to/workspace.

Include the declaration and summarize each usage by file, line,
column, and preview.
```

The packaged skill aligns that request to this command sequence:

```bash
KAST=$(bash .agents/skills/kast/scripts/resolve-kast.sh)
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
"$KAST" references \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/sample/Use.kt \
  --offset=41 \
  --include-declaration=true
```

When the result comes back, the agent must focus on these fields:

- `declaration` when the request asked for it
- `references[]` for the usage locations
- `references[].preview` for short usage snippets
- `page.truncated` for the cap on returned results

## Read the result safely

The result payload already contains enough structure for an LLM to make useful
semantic decisions. The agent needs to read the right fields and report their
limits honestly.

- **Treat `symbol.fqName` as the identity:** Use it when comparing a resolved
  declaration with later usage summaries.
- **Treat `symbol.kind` as a guardrail:** If the kind is not the one you
  expected, stop and report the mismatch instead of continuing.
- **Treat declaration coordinates as navigation anchors:** Use
  `symbol.location.filePath`, `startLine`, and `startColumn` for summaries and
  follow-up file reads.
- **Treat `references[].preview` as a snippet:** It is a quick usage hint, not
  a replacement for reading the full file body.
- **Treat `page.truncated` as a hard limit:** If it is `true`, say that Kast
  capped the result set and do not imply that you saw every usage.

!!! warning
    `page.truncated=true` does not mean the current skill workflow can page for
    more results. It means the visible result set is capped.

## Use the loop for edit planning

Once symbol identity and usage spread are clear, you can carry the same flow
into rename planning or targeted diagnostics.

1. Resolve the symbol at the exact target offset.
2. Verify the returned `fqName`, `kind`, and declaration location.
3. Run `references` against the same file path and offset.
4. Check whether the reference spread is narrow enough for the planned change.
5. Move to rename planning only after the declaration and caller set make
   sense.

## Avoid common mistakes

Most bad symbol-reference results come from a small set of avoidable input and
interpretation mistakes.

- Do not pass relative paths for the workspace or file.
- Do not pass a line and column as though they were the `offset`.
- Do not aim the offset at whitespace, comments, or string contents.
- Do not assume `preview` is the full surrounding function body.
- Do not assume `page.truncated=true` means pagination is available in the
  current skill workflow.
- Do not ask this skill for `callHierarchy`; that remains a known gap.

## Next steps

Use the broader task and reference pages when you want the underlying CLI
commands outside the LLM-specific workflow.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
