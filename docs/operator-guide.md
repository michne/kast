# Operator guide

This guide covers the current bootstrap flows for the two Kast runtimes.

## IntelliJ plugin

The IntelliJ module builds a plugin that starts one project-scoped Kast server
per open workspace. The server binds to `127.0.0.1` on an ephemeral port and
writes a descriptor JSON file under `~/.kast/instances/`.

Build the plugin from the repo root:

```bash
./gradlew :backend-intellij:buildPlugin
```

Run it in a sandbox IDE:

```bash
./gradlew :backend-intellij:runIde
```

The plugin currently advertises these capabilities:

- `RESOLVE_SYMBOL`
- `FIND_REFERENCES`
- `DIAGNOSTICS`
- `RENAME`
- `APPLY_EDITS`

`DIAGNOSTICS` currently reports parser-level PSI errors. Semantic diagnostics
remain part of the next backend hardening pass.

## Standalone process

The standalone runtime builds a fat JAR plus a launcher script. It binds to
`127.0.0.1` by default, uses an ephemeral port unless configured otherwise, and
writes the same descriptor JSON file format as the IntelliJ backend.

Build the standalone distribution:

```bash
./gradlew :backend-standalone:fatJar :backend-standalone:writeWrapperScript
```

Run it against the current workspace:

```bash
./backend-standalone/build/scripts/backend-standalone \
  --workspace-root=/absolute/path/to/workspace
```

Optional flags:

- `--host=127.0.0.1`
- `--port=0`
- `--token=shared-secret`
- `--request-timeout-ms=30000`
- `--max-results=500`
- `--max-concurrent-requests=4`

The standalone backend currently advertises `APPLY_EDITS` only. The Kotlin
Analysis API integration remains scaffolded but not implemented yet.

## Descriptor files

Both runtimes write a `ServerInstanceDescriptor` JSON file under
`~/.kast/instances/` or the directory pointed to by `KAST_INSTANCE_DIR`.

Each descriptor contains:

- `workspaceRoot`
- `backendName`
- `backendVersion`
- `host`
- `port`
- `token`
- `pid`
- `schemaVersion`

Consumers should discover Kast instances through the descriptor directory,
rather than assuming a fixed port.
