#!/usr/bin/env bash
# Builds, sends and installs on the VM, then checks from outside.
#
#   deploy/vm/deploy.sh [--only PART] [--yes] [--no-check]
#
# PART is one of:
#   all          ws-relay, iroh-relay, nginx, site (an update of a VM that is already set up; the default)
#   first-time   a fresh VM: certificates and nginx, site, ws-relay, iroh-relay, nginx limits
#                (needs CERTBOT_EMAIL; read docs/DEPLOY_VM.md section 4 first)
#   ws-relay | iroh-relay | nginx | site     one part
#   status       only show the VM's state (read-only; changes nothing)
#
# It asks you to type the VM's host name before changing anything (--yes skips the question).
# DRY_RUN=1 prints every command for the VM and runs none. SKIP_BUILD=1 uses the bundle already built.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

PART=all; YES=0; CHECK=1
while [ $# -gt 0 ]; do
    case "$1" in
        --only) PART="${2:-}"; shift 2 ;;
        --yes) YES=1; shift ;;
        --no-check) CHECK=0; shift ;;
        -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option '$1' (see --help)" ;;
    esac
done
require_var VM_HOST; require_var VM_USER

case "$PART" in
    all)        STEPS=(install-ws-relay install-iroh-relay install-nginx install-site) ;;
    first-time) STEPS=(first-time-certs install-site install-ws-relay install-iroh-relay install-nginx) ;;
    ws-relay)   STEPS=(install-ws-relay) ;;
    iroh-relay) STEPS=(install-iroh-relay) ;;
    nginx)      STEPS=(install-nginx) ;;
    site)       STEPS=(install-site) ;;
    status)     STEPS=(status) ;;
    *) die "--only must be all, first-time, ws-relay, iroh-relay, nginx, site or status" ;;
esac
if [ "$PART" = first-time ]; then
    : "${CERTBOT_EMAIL:?set CERTBOT_EMAIL=you@example.org for the first run (certificate expiry notices)}"
fi

if [ "$PART" != status ]; then
    if [ "${SKIP_BUILD:-0}" = 1 ]; then
        [ -f "$BUNDLE_DIR/VERSION" ] || die "SKIP_BUILD=1 but there is no bundle in $BUNDLE_DIR"
        log "using the bundle already built:"; sed 's/^/    /' "$BUNDLE_DIR/VERSION"
    else
        "$DEPLOY_VM_DIR/build.sh"
    fi
    if [ "$DRY_RUN" != 1 ] && [ "$YES" != 1 ]; then
        printf 'This changes %s (steps: %s).\nType the host name to continue: ' "$VM_HOST" "${STEPS[*]}"
        read -r answer || answer=""
        [ "$answer" = "$VM_HOST" ] || die "not confirmed; nothing was changed"
    fi
    send_bundle
fi

if [ "$PART" = status ]; then
    # status.sh is self-contained and read-only: it is piped through ssh, nothing is copied to the VM.
    log "on the VM: status (read-only)"
    if [ "$DRY_RUN" = 1 ]; then
        printf '  [dry-run] ssh %s@%s bash -s < remote/status.sh\n' "$VM_USER" "$VM_HOST"
    else
        ssh "$VM_USER@$VM_HOST" 'bash -s' < "$DEPLOY_VM_DIR/remote/status.sh"
    fi
    exit 0
fi

for step in "${STEPS[@]}"; do
    log "on the VM: $step"
    if [ "$step" = first-time-certs ]; then
        run_ssh "sudo env CERTBOT_EMAIL=$(printf '%q' "$CERTBOT_EMAIL") bash ~/$REMOTE_DIR/remote/$step.sh"
    else
        run_ssh "sudo bash ~/$REMOTE_DIR/remote/$step.sh"
    fi
done

if [ "$PART" != status ] && [ "$CHECK" = 1 ] && [ "$DRY_RUN" != 1 ]; then
    log "checking from outside"
    "$DEPLOY_VM_DIR/check.sh"
fi
