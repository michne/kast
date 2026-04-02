_Plan: Fast alpha migration to a CLI-only Kast runtime

Context: We are explicitly accepting breaking changes. The target is no longer
"keep both IntelliJ and standalone hosts working"; the target is to retire the
IntelliJ/plugin path quickly, keep one standalone runtime, and make the CLI plus
skill wrappers the only supported operator surface.

Decision

- Retire `:backend-intellij` instead of preserving it.
- Stop supporting IntelliJ-hosted runtime discovery, transport selection, and
  plugin packaging.
- Prefer deletion over shims or export layers.
- Keep `:analysis-api`, `:analysis-server`, `:kast`, and one standalone
  backend path.
- Treat `analysis-common` as disposable. If it cannot be made host-neutral
  cheaply, fold its PSI/K2 code into `:backend-standalone` and delete it.

Non-goals

- No attempt to preserve plugin compatibility.
- No effort to keep old module boundaries stable.
- No cache-scanning replacement for IntelliJ jars unless it is temporarily
  required to keep the standalone backend compiling during the migration.

Phases

1. Lock the target architecture.
   - Rewrite root docs and internal plans to describe one supported runtime:
	 CLI-managed standalone.
   - Remove ambiguous language about "two runtime hosts" from `README.md`,
	 root `AGENTS.md`, and the Kast skill docs.
   - Exit gate: repo docs describe one supported runtime path.

2. Remove IntelliJ build-graph ownership.
   - Remove `:backend-intellij` from `settings.gradle.kts`.
   - Remove `org.jetbrains.intellij.platform.settings` from
	 `settings.gradle.kts` if nothing else needs it.
   - Delete `build-logic/src/main/kotlin/kast.intellij-plugin.gradle.kts` and
	 any now-dead plugin wiring.
   - Prune IntelliJ plugin version metadata from `gradle/libs.versions.toml`
	 and any unused repositories added only for plugin packaging.
   - Exit gate: no `org.jetbrains.intellij.platform` plugin usage remains and
	 the build no longer includes `:backend-intellij`.

3. Collapse PSI/K2 ownership onto the standalone backend.
   - Audit `analysis-common` for IntelliJ PSI, Kotlin PSI, and Analysis API
	 imports.
   - Move `resolveTarget`, `declarationEdit`, symbol conversion, diagnostic
	 conversion, and any other PSI/K2-specific utilities into
	 `:backend-standalone` unless they are still shared by another surviving
	 runtime.
   - Shrink `analysis-common` to truly host-neutral code, or delete the module
	 entirely if nothing meaningful remains.
   - Exit gate: `analysis-common` no longer needs IntelliJ/Kotlin plugin jars,
	 or `analysis-common` is gone.

4. Remove IntelliJ classpath provisioning from the surviving build.
   - Delete cache-scanning logic from `analysis-common/build.gradle.kts`.
   - Rework `backend-standalone/build.gradle.kts` so any remaining IntelliJ/K2
	 jars are obtained by declared dependencies or are fully removed as part of
	 the backend rewrite.
   - Delete stale comments and migration leftovers that instruct users to build
	 IntelliJ first to warm Gradle caches.
   - Exit gate: no build script reads from `~/.gradle/caches/**/transforms` or
	 relies on `:backend-intellij` side effects.

5. Simplify CLI and runtime selection to one backend.
   - Remove `intellij` as a runtime/backend option from `kast`.
   - Delete `backendPreferenceRank("intellij")` paths and any
	 `backendName == "intellij"` branches.
   - Simplify request routing, daemon management, and skill wrappers so they
	 target only the standalone daemon.
   - Update `.agents/skills/kast/SKILL.md` to remove `http-intellij` and any
	 "choose a runtime" guidance.
   - Exit gate: CLI and skill docs expose a single backend model.

6. Remove dead docs and packaging references.
   - Update `docs/get-started.md`, `docs/choose-a-runtime.md`,
	 `docs/operator-guide.md`, `docs/remaining-work.md`, and `README.md`.
   - Delete plugin setup, IntelliJ runtime selection, and any claims that the
	 plugin is a supported host.
   - Remove obsolete artifacts and references such as `backend-intellij.zip` if
	 they are no longer needed for release or documentation.
   - Exit gate: user-facing docs consistently describe CLI-only operation.

7. Sweep for dead dependencies and module edges.
   - Verify `analysis-api` and `analysis-server` remain free of IntelliJ-only
	 dependencies.
   - Revisit `kast/build.gradle.kts` after the refactor; it currently
	 depends on `:backend-standalone`, which may be acceptable or may need a
	 cleaner boundary later.
   - Remove any orphaned repositories, versions, test wiring, or Gradle tasks
	 left behind by the IntelliJ module deletion.
   - Exit gate: module graph is minimal and intentional.

Validation

Run these in order as the migration lands:

1. `./gradlew :backend-standalone:build`
2. `./gradlew :kast:build`
3. `./gradlew :analysis-server:test`
4. `./gradlew build`

Success criteria

- The repo builds without `:backend-intellij`.
- No build script scans Gradle transform caches for IntelliJ distributions.
- No supported CLI or skill path mentions an IntelliJ runtime.
- `analysis-common` is either host-neutral or deleted.
- Docs, skills, and module graph all describe the same single-runtime model.
