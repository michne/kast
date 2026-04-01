---
title: Get started
description: Build the CLI, ensure a standalone runtime, and make your first
  requests.
icon: lucide/rocket
---

Kast now supports one runtime model: the repo-local CLI ensures a standalone
daemon for a workspace, then you can keep using the CLI or call the daemon's
HTTP endpoint directly. This guide gets you from a clean checkout to a running
instance and a first request.

> **Note:** The Gradle build uses Java 21.

## Prerequisites

Before you start, make sure you have the repository checked out and a Kotlin
workspace you want Kast to analyze.

- Java 21
- This repository
- One workspace to pass to the standalone runtime

## Build the CLI

Build the repo-local CLI distribution from the repo root.

```bash
./gradlew writeWrapperScript
```

The CLI fat JAR contains the standalone backend and can start detached runtime
processes for you.

## Ensure a workspace runtime

Start or reuse the standalone daemon for the target workspace.

```bash
./analysis-cli/build/scripts/analysis-cli \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

That command prints JSON describing the selected standalone runtime. It starts
the daemon only when the workspace does not already have one healthy, ready
instance.

Optional follow-up commands:

- `workspace status`: inspect descriptor, liveness, readiness, and capabilities
- `daemon start`: force a detached standalone daemon start
- `daemon stop`: stop the matching daemon and clean up its descriptor

## Discover the instance

The standalone runtime registers itself by writing a `ServerInstanceDescriptor`
JSON file under `<workspace>/.kast/instances/` by default. Set
`KAST_INSTANCE_DIR` if you want to override the directory.

When the workspace lives in Git and Kast uses the default workspace-local
directory, Kast adds `/.kast/` to the repo-local `.git/info/exclude` file so
the metadata stays untracked without changing the committed `.gitignore`.

You can inspect the current state through the CLI:

```bash
./analysis-cli/build/scripts/analysis-cli \
  workspace status \
  --workspace-root=/absolute/path/to/workspace
```

The selected descriptor reports `backendName = "standalone"` and includes the
resolved `host`, `port`, and `pid`.

## Make your first requests

The CLI keeps the request and response JSON machine-readable, so it is the
recommended default path.

1. Inspect the advertised capabilities:

    ```bash
    ./analysis-cli/build/scripts/analysis-cli \
      capabilities \
      --workspace-root=/absolute/path/to/workspace
    ```

2. Send an analysis request with an absolute request file:

    ```bash
    ./analysis-cli/build/scripts/analysis-cli \
      diagnostics \
      --workspace-root=/absolute/path/to/workspace \
      --request-file=/absolute/path/to/query.json
    ```

3. Optional: call the daemon directly over HTTP after you read the descriptor:

    ```bash
    curl http://127.0.0.1:51234/api/v1/health
    ```

If the runtime descriptor includes a token, add
`-H 'X-Kast-Token: shared-secret'` to protected routes.

Example `diagnostics` request file:

```json
{
  "filePaths": [
    "/absolute/path/to/workspace/src/main/kotlin/example/Foo.kt"
  ]
}
```

## Verify the result

You know the bootstrap worked when all three of these conditions are true.

- A descriptor file appears in the instance directory.
- `workspace status` reports one selected standalone runtime.
- `capabilities` advertises the routes your client plans to call.

## Next steps

Read [HTTP API](api-reference.md) to wire a client against the contract. Use
[Runtime model](choose-a-runtime.md) when you need the supported transport
choices. Keep [Operator guide](operator-guide.md) nearby for runtime defaults,
CLI commands, or descriptor lifecycle details.
