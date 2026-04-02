---
title: Get started
description: Install Kast, start a workspace runtime, verify it is ready, and
  stop it cleanly.
icon: lucide/play
---

This guide gets you from a fresh checkout to a ready workspace runtime. When
you finish, you will have `kast` on your path, a daemon attached to one
workspace, and a clear way to confirm the runtime is healthy.

## Before you begin

You need a small amount of local setup before the first command can succeed.

- Keep Java 21 or newer available through `JAVA_HOME` or your shell `PATH`.
- Run the commands from a checkout of this repository.
- Know the absolute path to the Kotlin workspace you want to analyze.

## Install the published CLI

Install the current published release from the repo root so the `kast`
executable is available from your shell.

1. Run the installer:

   ```bash
   ./install.sh
   ```

2. If your current shell still cannot find `kast`, open a new shell session so
   the updated `PATH` takes effect.

!!! note
    `./install.sh` validates that Java 21 or newer is available before it
    unpacks the release.

## Start or reuse a workspace runtime

Use `workspace ensure` to attach Kast to one workspace. The command reuses a
healthy daemon when one already exists, or starts a new daemon when it does
not.

1. Run the command with an absolute workspace path:

   ```bash
   kast \
     workspace ensure \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read the JSON result on stdout for the selected runtime metadata.
3. Read the daemon note on stderr to see whether Kast started a daemon or
   reused one that was already ready.

## Verify that the workspace is ready

Check status first, then inspect capabilities, so you know the runtime is live
and the features you plan to call are actually advertised.

1. Inspect the workspace runtime:

   ```bash
   kast \
     workspace status \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Inspect the advertised capability set:

   ```bash
   kast \
     capabilities \
     --workspace-root=/absolute/path/to/workspace
   ```

The second command is the fastest way to confirm which read and mutation
operations the current runtime supports.

## Stop the runtime when you are done

Stop the workspace daemon when you no longer need it. This keeps the lifecycle
explicit and makes it easy to tell which workspace runtimes are active.

1. Stop the daemon for the workspace:

   ```bash
   kast \
     daemon stop \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Read stderr for the stop note when a daemon was actually stopped.

## Troubleshooting first-run issues

The first failure usually comes from one of a small set of environment or usage
mistakes.

- If `./install.sh` fails immediately, confirm that Java 21 or newer is
  installed and your operating system is supported by the published bundle.
- If Kast reports a usage error, rewrite the command so every option uses the
  `--key=value` form.
- If Kast cannot find the workspace or a file, convert the path to an absolute
  path and rerun the command.

## Next steps

Once the workspace runtime is ready, you can move on to the common analysis
tasks and the compact reference page.

- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
