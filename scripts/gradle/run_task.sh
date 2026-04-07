#!/usr/bin/env bash
set -euo pipefail
# Run a Gradle task for the given project root and emit structured JSON to stdout.
# Usage: bash scripts/gradle/run_task.sh <project_root> <gradle_task> [<task_args>...]

if [ "$#" -lt 2 ]; then
  echo "{\"ok\":false,\"error\":\"Usage: run_task.sh <project_root> <gradle_task> [args...]\"}"
  exit 2
fi

PROJECT_ROOT=$(cd "$1" && pwd)
shift

TASK_ARGS=("$@")

# Ensure agent workflow dirs exist
mkdir -p "$PROJECT_ROOT/.agent-workflow/logs"

LOG_FILE="$PROJECT_ROOT/.agent-workflow/logs/gradle-$(date +%s).log"

# Prefer repo-local Gradle wrapper if present and executable
if [ -x "$PROJECT_ROOT/gradlew" ]; then
  GRADLE_CMD=("$PROJECT_ROOT/gradlew")
else
  GRADLE_CMD=("gradle")
fi

START_MS=$(python3 - <<PY
import time
print(int(time.time()*1000))
PY
)

# Run Gradle, capture output to log file
"${GRADLE_CMD[@]}" "${TASK_ARGS[@]}" >"$LOG_FILE" 2>&1 || true
EXIT_CODE=$?

END_MS=$(python3 - <<PY
import time
print(int(time.time()*1000))
PY
)

DURATION_MS=$((END_MS-START_MS))

# Build a JSON array for the executed tasks safely
TASKS_JSON=$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${TASK_ARGS[@]}")

# Export values for the JSON emitter
export TASKS_JSON
export EXIT_CODE="$EXIT_CODE"
export DURATION_MS="$DURATION_MS"
export LOG_FILE="$LOG_FILE"

# Emit structured JSON result using the exported env vars
python3 - <<'PY'
import json, os
tasks = json.loads(os.environ.get('TASKS_JSON', '[]'))
exit_code = int(os.environ.get('EXIT_CODE', '0'))
duration_ms = int(os.environ.get('DURATION_MS', '0'))
log_file = os.environ.get('LOG_FILE', '')
res = {
    "ok": exit_code == 0,
    "exit_code": exit_code,
    "duration_ms": duration_ms,
    "tasks_executed": tasks,
    "build_successful": exit_code == 0,
    "log_file": os.path.abspath(log_file) if log_file else None
}
print(json.dumps(res))
PY

exit $EXIT_CODE
