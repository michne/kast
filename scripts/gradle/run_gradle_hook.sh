#!/usr/bin/env bash
set -euo pipefail
# Determine the project's configured gradleHook (from .agent-workflow/state.json) and run it
# Usage: bash scripts/gradle/run_gradle_hook.sh <project_root>

if [ "$#" -ne 1 ]; then
  echo "{\"ok\":false,\"error\":\"Usage: run_gradle_hook.sh <project_root>\"}"
  exit 2
fi

PROJECT_ROOT=$(cd "$1" && pwd)
STATE_FILE="$PROJECT_ROOT/.agent-workflow/state.json"

GRADLE_HOOK=""
if [ -f "$STATE_FILE" ]; then
  # Pass the state file path as an argv to the Python process. The previous
  # heredoc placement caused the shell to attempt to execute the JSON file
  # as a command when the arg was placed after the heredoc terminator.
  GRADLE_HOOK=$(python3 - "$STATE_FILE" <<PY
import json,sys
try:
    s=json.load(open(sys.argv[1]))
    proj = s.get("project", {})
    gh = proj.get("gradleHook")
    print(gh if gh is not None else "")
except Exception:
    print("")
PY
  )
fi

if [ -z "$GRADLE_HOOK" ]; then
  GRADLE_HOOK="check"
fi

# Delegate to run_task.sh
bash "$PROJECT_ROOT/scripts/gradle/run_task.sh" "$PROJECT_ROOT" "$GRADLE_HOOK"
