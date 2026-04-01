#!/usr/bin/env python3
"""Read the current workflow state.
Usage:
  python3 get_state.py <project_root>                # Full state
  python3 get_state.py <project_root> tests           # Section
  python3 get_state.py <project_root> tests.failures  # Nested field
  python3 get_state.py <project_root> --summary       # Status overview
  python3 get_state.py <project_root> --history [N]   # Last N actions
"""
import json, os, sys

def load_state(pr):
    sf = os.path.join(pr, ".agent-workflow", "state.json")
    if not os.path.exists(sf): return None, f"State file not found at {sf}. Run init_state.py first."
    with open(sf) as f: return json.load(f), None

def extract_path(state, dp):
    cur = state
    for p in dp.split("."):
        if isinstance(cur, dict) and p in cur: cur = cur[p]
        else: return None, f"Path not found: {dp}"
    return cur, None

def build_summary(s):
    t,c,comp = s.get("tests",{}), s.get("coverage",{}), s.get("compilation",{})
    lb = s.get("gradle",{}).get("last_build")
    return {"project_discovered": s.get("project",{}).get("status")=="complete",
            "module_count": len(s.get("project",{}).get("app_modules",[])),
            "goal": s.get("goal",{}).get("description","") or "(no goal set)",
            "tests": {"status":t.get("status","pending"),"passed":t.get("passed",0),
                      "failed":t.get("failed",0),"total":t.get("total",0)},
            "coverage": {"status":c.get("status","pending"),"line_percent":c.get("line_percent")},
            "compilation": {"status":comp.get("status","pending"),
                            "non_incremental_count":len(comp.get("non_incremental_modules",[]))},
            "last_build_successful": lb.get("build_successful") if lb else None,
            "history_length": len(s.get("history",[]))}

def main():
    if len(sys.argv)<2:
        json.dump({"ok":False,"error":"Usage: get_state.py <project_root> [path|--summary|--history [N]]"}, sys.stdout); sys.exit(1)
    pr = os.path.abspath(sys.argv[1])
    state, err = load_state(pr)
    if err: json.dump({"ok":False,"error":err}, sys.stdout); sys.exit(1)
    if len(sys.argv)>=3:
        a = sys.argv[2]
        if a=="--summary": json.dump({"ok":True,"summary":build_summary(state)}, sys.stdout, indent=2)
        elif a=="--history":
            n = int(sys.argv[3]) if len(sys.argv)>=4 and sys.argv[3].isdigit() else 10
            h = state.get("history",[])
            json.dump({"ok":True,"total_entries":len(h),"showing":min(n,len(h)),"entries":h[-n:]}, sys.stdout, indent=2)
        else:
            sec, err = extract_path(state, a)
            if err: json.dump({"ok":False,"error":err}, sys.stdout); sys.exit(1)
            json.dump({"ok":True,"path":a,"value":sec}, sys.stdout, indent=2)
    else: json.dump({"ok":True,"state":state}, sys.stdout, indent=2)

if __name__ == "__main__":
    main()
