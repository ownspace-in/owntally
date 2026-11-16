#!/usr/bin/env bash
# Copies the invite page and the privacy page to /var/www/joinowntally. Run on the VM:  sudo bash ~/owntally-deploy/remote/install-site.sh
# Static files only; nginx needs no reload. Files removed from the repo are NOT removed here (delete them by hand if ever needed).
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_root
[ -f "$BUNDLE/site/index.html" ] || die "the bundle has no site/index.html (run build.sh and push.sh again)"
mkdir -p /var/www/joinowntally
cp -a "$BUNDLE/site/." /var/www/joinowntally/
chmod -R a+rX /var/www/joinowntally
log "site files are in /var/www/joinowntally"
if [ ! -f /var/www/joinowntally/.well-known/assetlinks.json ]; then
    warn "no .well-known/assetlinks.json: invite links open in the browser until it exists (site/README.md, 'assetlinks.json')"
fi
