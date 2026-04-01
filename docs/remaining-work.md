---
title: Remaining work
description: Gaps that remain after the CLI-only standalone migration.
icon: lucide/construction
---

# Remaining work

This page lists the implementation areas that remain incomplete after the
repository moved to a CLI-managed standalone runtime. The verification baseline
now covers shared contract fixtures, standalone bootstrap and packaging smoke
checks, and operator documentation for real repositories.

## Call hierarchy support

The contract and route exist for call hierarchy, but the standalone backend
does not implement it yet.

- **Status:** Not implemented in the standalone backend
- **Current state:** The API model includes `CALL_HIERARCHY`, and the server
  exposes `/api/v1/call-hierarchy`
- **Where:** `analysis-api/src/main/kotlin/io/github/amichne/kast/api/AnalysisBackend.kt`,
  `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisApplication.kt`
- **Missing:** Real call graph discovery in the standalone backend
- **Next step:** Implement call hierarchy in `backend-standalone` using the
  Kotlin Analysis API, then advertise `CALL_HIERARCHY` only after the response
  is stable and tested end to end.

## Standalone Analysis API compatibility cleanup

The standalone backend now resolves the IntelliJ `2025.3` distribution through
a declared Maven dependency instead of another module's warmed Gradle cache.
The remaining compatibility debt is the small compat JAR that bridges internal
plugin-descriptor APIs used by `analysis-api-standalone-for-ide`.

- **Status:** Partial
- **Current state:** `analysis-api-standalone-for-ide` is pinned to
  `2.3.20-ij253-119` (latest ij253 build). The compat JAR bridges
  `PathResolver.resolvePath(4-arg)` and related internal IJ APIs that the AA
  still calls against old `kotlin-compiler.jar` forms.
- **Where:** `backend-standalone/build.gradle.kts`,
  `backend-standalone/src/compat/java/`
- **Missing:** An upstream standalone Analysis API release that aligns with the
  stable IntelliJ `2025.3` plugin-descriptor APIs and lets the compat JAR go
  away entirely.
- **Next step:** Remove the compat sources and JAR packaging once the upstream
  standalone Analysis API no longer needs the bridged classes.

## CLI and runtime packaging boundary

The current CLI module depends directly on `:backend-standalone` so it can
launch the standalone daemon from the same repo-local distribution.

- **Status:** Acceptable for now
- **Current state:** The boundary is simple and works, but the CLI ships with
  more runtime implementation detail than a thinner client would need.
- **Where:** `analysis-cli/build.gradle.kts`
- **Missing:** A cleaner packaging split if the CLI ever needs to become a
  smaller independent client.
- **Next step:** Revisit only if distribution size, layering, or external reuse
  makes the direct dependency costly.

## Network hardening

Safe defaults exist and are now enforced by code.

- **Status:** Done
- **Current state:** `AnalysisServerConfig` validates at construction time that
  non-loopback hosts require a non-empty token. Binding to `0.0.0.0` or any
  non-loopback address without a token throws `IllegalArgumentException` at
  startup.
- **Where:** `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt`
