#!/usr/bin/env bash
# Shared helpers for the deploy scripts that run on YOUR machine. Sourced, not run.
# The scripts that run on the VM have their own helpers: remote/common.sh.
set -euo pipefail

DEPLOY_VM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DEPLOY_VM_DIR/../.." && pwd)"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "needs '$1' installed on this machine"; }
require_var() { [ -n "${!1:-}" ] || die "$1 is not set (copy deploy/vm/config.env.example to deploy/vm/config.env)"; }

# Settings: config.env if present, then defaults. Variables already in the environment win.
load_config() {
    local pre_host="${VM_HOST:-}" pre_user="${VM_USER:-}" pre_arch="${VM_ARCH:-}"
    if [ -f "$DEPLOY_VM_DIR/config.env" ]; then
        # shellcheck disable=SC1091
        . "$DEPLOY_VM_DIR/config.env"
    fi
    [ -z "$pre_host" ] || VM_HOST="$pre_host"
    [ -z "$pre_user" ] || VM_USER="$pre_user"
    [ -z "$pre_arch" ] || VM_ARCH="$pre_arch"
    : "${VM_HOST:=}" "${VM_USER:=}" "${VM_ARCH:=auto}" "${REMOTE_DIR:=owntally-deploy}"
    : "${IROH_VERSION:=v1.4.1}" "${RELAY_HOST:=relay.ownspace.in}" "${SITE_HOST:=joinowntally.ownspace.in}"
    : "${DRY_RUN:=0}" "${BUNDLE_DIR:=$DEPLOY_VM_DIR/dist/bundle}"
}

# Everything that touches the VM goes through these two, so DRY_RUN=1 shows the plan and changes nothing.
# run_ssh asks for a terminal (-t) so that sudo can ask for a password.
run_ssh() {
    if [ "$DRY_RUN" = 1 ]; then
        printf '  [dry-run] ssh %s@%s %s\n' "$VM_USER" "$VM_HOST" "$*"
    else
        ssh -t "$VM_USER@$VM_HOST" "$@"
    fi
}

# Read-only question to the VM (used for the CPU type); runs even in a dry run.
ask_ssh() { ssh "$VM_USER@$VM_HOST" "$@"; }

send_bundle() {
    if [ "$DRY_RUN" = 1 ]; then
        printf '  [dry-run] copy %s to %s@%s:~/%s\n' "$BUNDLE_DIR" "$VM_USER" "$VM_HOST" "$REMOTE_DIR"
        return 0
    fi
    ssh "$VM_USER@$VM_HOST" "mkdir -p ~/$REMOTE_DIR"
    tar -C "$BUNDLE_DIR" -cf - . | ssh "$VM_USER@$VM_HOST" "tar -C ~/$REMOTE_DIR -xf -"
}

resolve_arch() {
    case "$VM_ARCH" in
        amd64|arm64) echo "$VM_ARCH" ;;
        auto)
            require_var VM_HOST; require_var VM_USER
            case "$(ask_ssh uname -m)" in
                x86_64) echo amd64 ;;
                aarch64) echo arm64 ;;
                *) die "unknown CPU type on the VM; set VM_ARCH to amd64 or arm64" ;;
            esac ;;
        *) die "VM_ARCH must be auto, amd64 or arm64" ;;
    esac
}
