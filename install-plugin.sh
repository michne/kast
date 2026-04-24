#!/usr/bin/env bash
# install-plugin.sh — Install the kast IntelliJ plugin into a running IntelliJ instance.
#
# Usage:
#   ./install-plugin.sh
#
# Prompts for:
#   1. Which running IntelliJ process to target (if more than one is running)
#   2. Install source: local build or latest GitHub release
#
# Local:  copies backend-intellij/build/libs/backend-intellij-0.1.1-SNAPSHOT.jar
#         to the instance's plugins directory.
# Remote: downloads kast-intellij-<tag>.zip from GitHub releases and extracts
#         it into the plugins directory.
#
# Restart IntelliJ after running this script for the plugin to activate.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
LOCAL_JAR="${SCRIPT_DIR}/backend-intellij/build/libs/backend-intellij-0.1.1-SNAPSHOT.jar"
RELEASE_REPO="amichne/kast"
PLUGIN_ID="io.github.amichne.kast.intellij"

# ---------------------------------------------------------------------------
# Logging (mirrors kast.sh style)
# ---------------------------------------------------------------------------

_supports_color() {
  [[ "${CLICOLOR_FORCE:-}" == "1" ]] && return 0
  [[ -n "${NO_COLOR:-}" ]] && return 1
  [[ ! -t 2 ]] && return 1
  [[ "${TERM:-dumb}" != "dumb" ]]
}

_c() { _supports_color && printf '\033[%sm%s\033[0m' "$1" "$2" || printf '%s' "$2"; }

log_section() { printf '\n%s\n'   "$(_c '1;36' "$*")" >&2; }
log_step()    { printf '%s %s\n'  "$(_c '1;34' '>')" "$*" >&2; }
log_success() { printf '%s %s\n'  "$(_c '1;32' 'v')" "$*" >&2; }
log_note()    { printf '%s %s\n'  "$(_c '33'   '*')" "$*" >&2; }
log_prompt()  { printf '%s %s'    "$(_c '1;35' '?')" "$*" >/dev/tty; }
die()         { printf '%s %s\n'  "$(_c '1;31' 'x')" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Process discovery
# ---------------------------------------------------------------------------

# Emit lines: "<pid> <app-bundle-path>" for each running IntelliJ-family process.
_find_intellij_procs() {
  ps aux \
    | grep -E '/Contents/MacOS/idea$' \
    | grep -v grep \
    | awk '{
        pid = $2
        for (i = 11; i <= NF; i++) {
          if ($i ~ /\.app\/Contents\/MacOS\/idea$/) {
            sub(/\/Contents\/MacOS\/idea$/, "", $i)
            print pid " " $i
            break
          }
        }
      }'
}

# Read product name + version from product-info.json inside an app bundle.
_product_label() {
  local app_path="$1"
  local pinfo="${app_path}/Contents/Resources/product-info.json"
  [[ -f "$pinfo" ]] || { echo "$(basename "$app_path")"; return; }
  python3 - "$pinfo" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"{d['name']} {d['version']}  [{d['dataDirectoryName']}]")
EOF
}

# Return the plugins directory for a given pid + app bundle path.
# Prefers -Didea.config.path JVM arg; falls back to standard macOS location.
_plugins_dir() {
  local pid="$1" app_path="$2"

  # Check for explicit config path override in JVM args
  local jvm_args config_path=""
  jvm_args=$(ps -p "$pid" -o args= 2>/dev/null || true)
  config_path=$(printf '%s' "$jvm_args" \
    | grep -oE '\-Didea\.config\.path=[^ ]+' \
    | cut -d= -f2- | head -1)

  if [[ -n "$config_path" ]]; then
    printf '%s/plugins' "$config_path"
    return
  fi

  # Standard macOS location derived from product-info.json
  local pinfo="${app_path}/Contents/Resources/product-info.json"
  [[ -f "$pinfo" ]] || die "Cannot find product-info.json in ${app_path}"
  local data_dir_name
  data_dir_name=$(python3 - "$pinfo" <<'EOF'
import json, sys; print(json.load(open(sys.argv[1]))["dataDirectoryName"])
EOF
)
  printf '%s/Library/Application Support/JetBrains/%s/plugins' "$HOME" "$data_dir_name"
}

# ---------------------------------------------------------------------------
# Interactive helpers
# ---------------------------------------------------------------------------

_read_line() { IFS= read -r "$1" </dev/tty; }

_pick_from_list() {
  # Usage: _pick_from_list "prompt text" item1 item2 ...
  # Prints the 0-based index of the selected item.
  local prompt_text="$1"; shift
  local -a items=("$@")
  local count=${#items[@]}

  if [[ $count -eq 1 ]]; then
    echo 0; return
  fi

  local i
  for i in "${!items[@]}"; do
    printf '  [%d] %s\n' "$((i + 1))" "${items[$i]}" >/dev/tty
  done
  printf '\n' >/dev/tty

  local choice
  while true; do
    log_prompt "${prompt_text} (1-${count}): "
    _read_line choice
    printf '\n' >/dev/tty
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= count )); then
      echo $((choice - 1)); return
    fi
    log_note "Enter a number between 1 and ${count}"
  done
}

# ---------------------------------------------------------------------------
# Install sources
# ---------------------------------------------------------------------------

_install_local() {
  local plugins_dir="$1"
  [[ -f "$LOCAL_JAR" ]] \
    || die "Local JAR not found: ${LOCAL_JAR}\nBuild it first:  ./gradlew :backend-intellij:buildPlugin"

  log_step "Copying $(basename "$LOCAL_JAR") → ${plugins_dir}/"
  mkdir -p "$plugins_dir"

  # Remove any prior directory-style install for the same plugin to avoid conflicts
  local existing_dir="${plugins_dir}/$(basename "${LOCAL_JAR%.jar}")"
  if [[ -d "$existing_dir" ]]; then
    log_note "Removing existing directory install: ${existing_dir}"
    rm -rf "$existing_dir"
  fi

  cp "$LOCAL_JAR" "${plugins_dir}/"
  log_success "Installed: ${plugins_dir}/$(basename "$LOCAL_JAR")"
}

_install_remote() {
  local plugins_dir="$1"
  command -v gh >/dev/null 2>&1 || die "'gh' CLI is required for remote install. Install from https://cli.github.com"

  log_step "Fetching latest release from ${RELEASE_REPO}..."
  local latest_tag
  latest_tag=$(gh release list --repo "$RELEASE_REPO" --limit 1 --exclude-drafts \
    --json tagName --jq '.[0].tagName' 2>/dev/null) \
    || die "Failed to fetch release info — is 'gh' authenticated?"

  local zip_name="kast-intellij-${latest_tag}.zip"
  local tmp_dir
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "$tmp_dir"' RETURN

  log_step "Downloading ${zip_name}..."
  gh release download "$latest_tag" \
    --repo "$RELEASE_REPO" \
    --pattern "$zip_name" \
    --output "${tmp_dir}/${zip_name}" \
    || die "Download failed for ${zip_name}"

  log_step "Extracting into ${plugins_dir}/"
  mkdir -p "$plugins_dir"

  # Remove any prior install (JAR or directory) for this plugin
  local jar_name
  for jar_name in "${plugins_dir}"/backend-intellij*.jar; do
    [[ -f "$jar_name" ]] && { log_note "Removing prior JAR: ${jar_name}"; rm -f "$jar_name"; }
  done

  unzip -o -q "${tmp_dir}/${zip_name}" -d "$plugins_dir"
  log_success "Installed ${zip_name} into ${plugins_dir}/"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

log_section "Kast IntelliJ Plugin Installer"

# 1. Discover running IntelliJ processes
log_step "Scanning for running IntelliJ processes..."

declare -a proc_pids=()
declare -a proc_apps=()
declare -a proc_labels=()

while IFS=' ' read -r pid app_path; do
  [[ -z "$pid" || -z "$app_path" ]] && continue
  label="PID ${pid}  $(_product_label "$app_path")"
  proc_pids+=("$pid")
  proc_apps+=("$app_path")
  proc_labels+=("$label")
done < <(_find_intellij_procs)

[[ ${#proc_pids[@]} -gt 0 ]] || die "No running IntelliJ IDEA processes found."

# 2. Select the target instance
log_section "Step 1 of 2 — Select IntelliJ instance"
idx=$(_pick_from_list "Which instance?" "${proc_labels[@]}")

selected_pid="${proc_pids[$idx]}"
selected_app="${proc_apps[$idx]}"
selected_label="${proc_labels[$idx]}"
plugins_dir=$(_plugins_dir "$selected_pid" "$selected_app")

log_success "Target: ${selected_label}"
log_note    "Plugins dir: ${plugins_dir}"

# 3. Choose install source
log_section "Step 2 of 2 — Choose install source"
printf '  [L] Local build   %s\n' "$LOCAL_JAR" >/dev/tty
printf '  [R] Remote        latest release from github.com/%s\n\n' "$RELEASE_REPO" >/dev/tty

local_available="no"
[[ -f "$LOCAL_JAR" ]] && local_available="yes"
[[ "$local_available" == "no" ]] && log_note "Local JAR not found (build first to enable this option)"

src_choice=""
while true; do
  log_prompt "Install source [L/R]: "
  _read_line src_choice
  printf '\n' >/dev/tty
  case "${src_choice,,}" in
    l|local)
      [[ "$local_available" == "yes" ]] || { log_note "Local JAR not built — choose R or run: ./gradlew :backend-intellij:buildPlugin"; continue; }
      break ;;
    r|remote) break ;;
    *) log_note "Enter L for local or R for remote" ;;
  esac
done

# 4. Execute
case "${src_choice,,}" in
  l|local)  _install_local  "$plugins_dir" ;;
  r|remote) _install_remote "$plugins_dir" ;;
esac

# 5. Done
log_section "Done"
log_note "Restart IntelliJ IDEA to activate the plugin."
log_note "Plugin ID: ${PLUGIN_ID}"
printf '\n' >&2
