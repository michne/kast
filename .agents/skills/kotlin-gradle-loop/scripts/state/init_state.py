#!/usr/bin/env python3
"""Initialize the agent workflow state file.
Usage: python3 init_state.py <project_root>
Output JSON: {"ok": true, "state_file": "..."} or {"ok": false, "error": "..."}
"""
import json, os, sys
from datetime import datetime, timezone

def main():
    if len(sys.argv) < 2:
        json.dump({"ok": False, "error": "Usage: init_state.py <project_root>"}, sys.stdout); sys.exit(1)
    project_root = os.path.abspath(sys.argv[1])
    if not os.path.isdir(project_root):
        json.dump({"ok": False, "error": f"Not a directory: {project_root}"}, sys.stdout); sys.exit(1)
    has_gradle = any(os.path.exists(os.path.join(project_root, f))
                     for f in ["build.gradle.kts","build.gradle","settings.gradle.kts","settings.gradle"])
    if not has_gradle:
        json.dump({"ok": False, "error": f"No Gradle build files found in {project_root}"}, sys.stdout); sys.exit(1)
    workflow_dir = os.path.join(project_root, ".agent-workflow")
    state_file = os.path.join(workflow_dir, "state.json")
    os.makedirs(os.path.join(workflow_dir, "logs"), exist_ok=True)
    if os.path.exists(state_file):
        json.dump({"ok": True, "state_file": state_file, "already_existed": True}, sys.stdout); return
    now = datetime.now(timezone.utc).isoformat()
    state = {
        "schema_version": 1, "project_root": project_root, "created_at": now, "updated_at": now,
        "project": {"status":"pending","boot_module":None,"main_class":None,"app_modules":[],
                     "leaf_modules":[],"modules_with_ksp":[],"modules_with_kapt":[],
                     "dependency_graph":{},"jdk_version":None,"kotlin_version":None,
                     "gradle_version":None,"has_buildsrc":False,"has_included_builds":False,
                     "source_sets":{},"errors":[]},
        "goal": {"description":"","constraints":[],"acceptance_criteria":[]},
        "gradle": {"last_build":None,"configuration_cache_compatible":None,"build_cache_enabled":None},
        "tests": {"status":"pending","total":0,"passed":0,"failed":0,"skipped":0,
                  "duration_seconds":0,"failures":[],"timestamp":None},
        "coverage": {"status":"pending","line_percent":None,"branch_percent":None,
                     "class_percent":None,"method_percent":None,"covered_lines":0,
                     "missed_lines":0,"total_lines":0,"lowest_coverage_classes":[],"timestamp":None},
        "compilation": {"status":"pending","incremental_modules":[],"non_incremental_modules":[],
                        "non_incremental_reasons":{},"timestamp":None},
        "history": []
    }
    with open(state_file, "w") as f:
        json.dump(state, f, indent=2)
    json.dump({"ok": True, "state_file": state_file, "already_existed": False}, sys.stdout)

if __name__ == "__main__":
    main()
