#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/scripts/lib.sh"

readonly SCRIPT_DIR
readonly REPO_ROOT="$SCRIPT_DIR"
readonly GRADLEW="${REPO_ROOT}/gradlew"
readonly DIST_ROOT="${REPO_ROOT}/dist"
readonly DIST_DIR="${DIST_ROOT}/kast"
readonly DIST_ZIP="${DIST_ROOT}/kast.zip"
readonly PORTABLE_DIST_DIR="${REPO_ROOT}/kast/build/portable-dist/kast"
readonly PORTABLE_ZIP_DIR="${REPO_ROOT}/kast/build/distributions"
readonly INSTALL_INSTANCE_SCRIPT="${REPO_ROOT}/scripts/install-instance.sh"
readonly GRADLE_ARGS=()

tmp_dir=""

cleanup() {
  if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
    rm -rf "$tmp_dir"
  fi
}

trap cleanup EXIT

usage() {
  cat <<'USAGE' >&2
Usage: ./build.sh [command] [options]

Commands:
  clean              Remove all local kast installs:
                       ~/.local/share/kast/instances/
                       ~/.local/bin/kast-* launchers
                       ~/.local/bin/kast (if present)

Builds the local kast CLI package from source, publishes:
  dist/kast
  dist/kast.zip

Options:
  --install          Install the built portable zip as a local/dev instance.
  --no-install       Skip the interactive install prompt after a successful build.
  --instance <name>  Use this instance name when installing locally.
  --force            Install the build to ~/.local/bin/kast directly (no instance name).
  --help, -h         Show this help.

Examples:
  ./build.sh
  ./build.sh clean
  ./build.sh --no-install
  ./build.sh --install --instance my-dev
  ./build.sh --force
USAGE
}

verify_prerequisites() {
  [[ -n "$REPO_ROOT" && -d "$REPO_ROOT" ]] || die "Could not determine the repo root"
  [[ -x "$GRADLEW" ]] || die "Missing executable gradlew at ${GRADLEW}"
}

run_gradle_build() {
  log_step "Building staged CLI tree and portable zip"
  (
    cd "$REPO_ROOT"
    "$GRADLEW" stageCliDist buildCliPortableZip "${GRADLE_ARGS[@]}"
  )
}

verify_cli_stage() {
  log_step "Verifying staged CLI tree in ${PORTABLE_DIST_DIR}"
  [[ -x "${PORTABLE_DIST_DIR}/kast" ]] || die "Missing staged kast launcher"
  [[ -d "${PORTABLE_DIST_DIR}/bin" ]] || die "Missing staged bin directory"
  [[ -x "${PORTABLE_DIST_DIR}/bin/kast" ]] || die "Missing staged kast native binary"
  [[ -d "${PORTABLE_DIST_DIR}/runtime-libs" ]] || die "Missing staged runtime-libs directory"
  [[ -f "${PORTABLE_DIST_DIR}/runtime-libs/classpath.txt" ]] || die "Missing staged runtime classpath file"

  local jars=()
  shopt -s nullglob
  jars=("${PORTABLE_DIST_DIR}"/libs/kast-*-all.jar)
  shopt -u nullglob

  [[ "${#jars[@]}" -eq 1 ]] || die "Expected exactly one staged fat jar under ${PORTABLE_DIST_DIR}/libs"
}

resolve_portable_zip() {
  local newest=""
  local candidate=""

  shopt -s nullglob
  for candidate in "${PORTABLE_ZIP_DIR}"/kast-*-portable.zip; do
    if [[ -z "$newest" || "$candidate" -nt "$newest" ]]; then
      newest="$candidate"
    fi
  done
  shopt -u nullglob

  [[ -n "$newest" ]] || die "Expected a portable zip under ${PORTABLE_ZIP_DIR}"
  printf '%s\n' "$newest"
}

publish_dist_tree() {
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kast-build.XXXXXX")"

  log_step "Publishing staged CLI tree into ${DIST_DIR}"
  mkdir -p "$DIST_ROOT"
  cp -R "$PORTABLE_DIST_DIR" "${tmp_dir}/kast"
  rm -rf "$DIST_DIR"
  mv "${tmp_dir}/kast" "$DIST_DIR"
  log_success "Published ${DIST_DIR}"
}

publish_dist_zip() {
  local source_zip="$1"

  log_step "Publishing portable zip into ${DIST_ZIP}"
  mkdir -p "$DIST_ROOT"
  cp "$source_zip" "$DIST_ZIP"
  log_success "Published ${DIST_ZIP}"
}

install_local_instance() {
  local source_zip="$1"
  [[ -x "$INSTALL_INSTANCE_SCRIPT" ]] || die "Missing install helper at ${INSTALL_INSTANCE_SCRIPT}"
  local install_cmd=("$INSTALL_INSTANCE_SCRIPT" --archive "$source_zip")

  if [[ -n "$instance_name" ]]; then
    install_cmd+=(--instance "$instance_name")
  fi

  log_step "Installing local/dev instance from ${source_zip}"
  (
    cd "$REPO_ROOT"
    "${install_cmd[@]}"
  )
}

force_install_local() {
  local source_zip="$1"
  local install_root="${HOME}/.local/share/kast/main"
  local bin_dir="${HOME}/.local/bin"
  local launcher_path="${bin_dir}/kast"

  command -v python3 >/dev/null 2>&1 || die "Missing required tool: python3"

  local tmp_extract
  tmp_extract="$(mktemp -d "${TMPDIR:-/tmp}/kast-force-install.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf '${tmp_extract}'" RETURN

  log_step "Extracting ${source_zip}"
  extract_zip_archive "$source_zip" "$tmp_extract"
  [[ -d "${tmp_extract}/kast" ]] || die "Archive did not contain the expected kast/ directory"

  log_step "Installing to ${install_root}"
  rm -rf "$install_root"
  mkdir -p "$(dirname -- "$install_root")" "$bin_dir"
  mv "${tmp_extract}/kast" "$install_root"
  chmod +x "${install_root}/kast" "${install_root}/bin/kast"

  cat >"$launcher_path" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec "${install_root}/kast" "\$@"
EOF
  chmod +x "$launcher_path"

  log_success "Installed ${launcher_path}"
}

clean_local_installs() {
  local instances_root="${HOME}/.local/share/kast/instances"
  local bin_dir="${HOME}/.local/bin"

  log_step "Removing local instances from ${instances_root}"
  rm -rf "$instances_root"

  log_step "Removing instance launchers matching ${bin_dir}/kast-*"
  local launchers=()
  shopt -s nullglob
  launchers=("${bin_dir}"/kast-*)
  shopt -u nullglob
  if [[ "${#launchers[@]}" -gt 0 ]]; then
    rm -f "${launchers[@]}"
  fi

  if [[ -f "${bin_dir}/kast" || -L "${bin_dir}/kast" ]]; then
    log_step "Removing forced install at ${bin_dir}/kast"
    rm -f "${bin_dir}/kast"
  fi

  log_success "Cleaned up local kast installs"
}

install_mode="prompt"
instance_name=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    clean)
      log_section "Kast local clean"
      clean_local_installs
      exit 0
      ;;
    --install)
      install_mode="yes"
      shift
      ;;
    --no-install)
      install_mode="no"
      shift
      ;;
    --force)
      install_mode="force"
      shift
      ;;
    --instance)
      [[ $# -ge 2 ]] || die "Missing value for --instance"
      instance_name="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

if [[ -n "$instance_name" && "$instance_name" =~ [^a-zA-Z0-9._-] ]]; then
  die "Instance name may contain only letters, digits, dot, underscore, and dash"
fi

if [[ -n "$instance_name" && "$install_mode" == "no" ]]; then
  die "--instance cannot be combined with --no-install"
fi

if [[ -n "$instance_name" && "$install_mode" == "force" ]]; then
  die "--instance cannot be combined with --force"
fi

if [[ -n "$instance_name" && "$install_mode" == "prompt" ]]; then
  install_mode="yes"
fi

verify_prerequisites

log_section "Kast local build"
run_gradle_build
verify_cli_stage

portable_zip="$(resolve_portable_zip)"
publish_dist_tree
publish_dist_zip "$portable_zip"

should_install="no"
case "$install_mode" in
  yes)
    should_install="yes"
    ;;
  no)
    should_install="no"
    ;;
  force)
    should_install="force"
    ;;
  prompt)
    if can_prompt && prompt_yes_no "Install this build locally as a dev instance?" "no"; then
      should_install="yes"
    else
      log_note "Skipped local/dev install"
    fi
    ;;
esac

if [[ "$should_install" == "yes" ]]; then
  install_local_instance "$portable_zip"
elif [[ "$should_install" == "force" ]]; then
  force_install_local "$portable_zip"
fi

log_success "Local build is ready at ${DIST_DIR}"
log "Portable zip: ${DIST_ZIP}"
