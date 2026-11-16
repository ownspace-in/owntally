#!/usr/bin/env bash
# Helpers for the scripts that run ON the VM, as root, from the unpacked bundle (~/owntally-deploy). Sourced, not run.
set -euo pipefail

BUNDLE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${BACKUP_DIR:=/root/owntally-backups}"
LAST_BACKUP=""

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }
require_root() { [ "$(id -u)" -eq 0 ] || die "run this with sudo: sudo bash $0"; }
require_file() { [ -f "$1" ] || die "missing $1 (the bundle is incomplete: run deploy/vm/build.sh and push.sh again)"; }

# Copies a file aside before it is replaced. Sets LAST_BACKUP to the copy (empty if there was nothing to copy).
backup() {
    LAST_BACKUP=""
    [ -e "$1" ] || return 0
    mkdir -p "$BACKUP_DIR"; chmod 0700 "$BACKUP_DIR"
    LAST_BACKUP="$BACKUP_DIR/$(printf '%s' "$1" | tr / _).$(date +%Y%m%d-%H%M%S)"
    cp -a "$1" "$LAST_BACKUP"
    log "backed up $1 to $LAST_BACKUP"
}

# wait_for SECONDS COMMAND...: tries once a second until the command succeeds.
wait_for() {
    local n="$1" i; shift
    for i in $(seq 1 "$n"); do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    return 1
}

# Prints the HTTP status of a URL ("000" if nothing answered). -k because loopback names do not match the certificate.
http_code() { curl -sk -o /dev/null -w '%{http_code}' --max-time 5 "$@" || true; }

# code_is URL CODE: true if the URL answers with that status (so wait_for can retry it).
code_is() { [ "$(http_code "$1")" = "$2" ]; }
