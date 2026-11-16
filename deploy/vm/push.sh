#!/usr/bin/env bash
# Copies the bundle (deploy/vm/dist/bundle) to ~/owntally-deploy on the VM. Changes nothing else.
# Files already there are overwritten; files that are no longer in the bundle are left alone (harmless).
#   deploy/vm/push.sh          (DRY_RUN=1 shows what it would do)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config
require_var VM_HOST; require_var VM_USER
[ -f "$BUNDLE_DIR/VERSION" ] || die "no bundle in $BUNDLE_DIR; run deploy/vm/build.sh first"
log "sending the bundle to $VM_USER@$VM_HOST:~/$REMOTE_DIR"
send_bundle
log "sent"
