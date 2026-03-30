# Kast

Kast is a Kotlin analysis server with one HTTP/JSON contract and two runtime
hosts:

- an IntelliJ plugin for local development
- a standalone JVM process for CI and headless workflows

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, models, errors, and edit validation
- `analysis-server`: Ktor transport, descriptor file handling, and HTTP routes
- `backend-intellij`: IntelliJ-hosted backend and plugin entrypoint
- `backend-standalone`: standalone runtime entrypoint
- `shared-testing`: fake backend fixtures used by server and backend tests

## Current state

The bootstrap and first vertical slice are in place:

- the Gradle build, convention plugins, and module structure exist
- the HTTP server, descriptor file workflow, and edit-application path work
- the IntelliJ backend provides PSI-backed symbol resolution, references,
  rename planning, and parser-level diagnostics
- the standalone backend is scaffolded and currently advertises `APPLY_EDITS`
  only

The next work is to replace the standalone scaffolding with a full Kotlin
Analysis API implementation and to bring `callHierarchy` online behind
capability gating.
