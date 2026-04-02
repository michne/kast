# Kast

Kast is a Kotlin analysis tool for real Kotlin workspaces. The current right
way to use it is the repo-local `kast` command.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, models, errors, and edit validation
- `kast`: CLI control plane for workspace status, ensure, daemon
    lifecycle, and request dispatch
- `analysis-server`: request dispatch and daemon transport plumbing
- `backend-standalone`: standalone runtime entrypoint plus Kotlin Analysis API
    integration
- `shared-testing`: fake backend fixtures used by server and backend tests

## How to use it

Build the CLI from the repo root:

```bash
./gradlew :kast:syncRuntimeLibs :kast:writeWrapperScript
```

Start or reuse a runtime for a workspace:

```bash
./kast/build/scripts/kast \
  workspace ensure \
  --workspace-root=/absolute/path/to/workspace
```

Run analysis commands the same way:

```bash
./kast/build/scripts/kast \
  capabilities \
  --workspace-root=/absolute/path/to/workspace

./kast/build/scripts/kast \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

Stop the daemon when you need to:

```bash
./kast/build/scripts/kast \
  daemon stop \
  --workspace-root=/absolute/path/to/workspace
```

Successful commands print JSON on stdout. Daemon lifecycle notes go to stderr.

The main remaining production gap is `callHierarchy`.
