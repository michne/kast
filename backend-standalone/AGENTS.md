# Standalone backend agent guide

`backend-standalone` owns the headless runtime, the standalone backend
implementation, and the standalone-specific PSI/K2 helper code.

## Ownership

Use this unit for headless host concerns and nowhere else.

- Keep host bootstrapping here: CLI arguments, environment fallbacks, server
  startup, shutdown hooks, and runtime packaging.
- Keep capability advertising conservative. The standalone backend currently
  implements `RESOLVE_SYMBOL`, `FIND_REFERENCES`, `DIAGNOSTICS`, `RENAME`, and
  `APPLY_EDITS`, but not `CALL_HIERARCHY`.
- Preserve the current CLI contract: `--key=value` arguments,
  `KAST_WORKSPACE_ROOT` and `KAST_TOKEN` fallbacks, and normalized absolute
  workspace roots.
- Reuse shared transport and edit semantics from `analysis-server` and
  `analysis-api` instead of re-implementing them here.
- Keep standalone-only PSI/K2 helpers in this unit instead of rebuilding a
  shared module unless another runtime actually returns.

## Verification

Build the standalone host after changes, and add tests when the surface grows.

- Run `./gradlew :backend-standalone:build`.
- If you expand the backend surface, add or update tests that prove the new
  advertised capabilities.
