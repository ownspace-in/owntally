#!/usr/bin/env bash
# Builds the deploy bundle on your machine. Sends nothing anywhere.
#
#   deploy/vm/build.sh
#
# Output: deploy/vm/dist/bundle (git-ignored), holding
#   ws-relay, ws-relay.service                      the WebSocket relay (built here from relay/ws-relay)
#   iroh-relay, iroh-relay.service, config.toml,    the Iroh relay (official release, checksum-verified)
#     nginx-limits.conf, nginx-locations.conf, renew-hook.sh
#   nginx/*.conf                                    server blocks for a fresh VM
#   site/                                           the invite page, privacy page (no dev files)
#   remote/*.sh                                     the scripts that run on the VM
#   VERSION                                         commit, time, CPU, Iroh version
#
# The configs are copied from where they already live in the repo; there is no second copy to keep in step.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config
require_cmd go; require_cmd curl; require_cmd tar; require_cmd sha256sum

ARCH="$(resolve_arch)"
B="$BUNDLE_DIR"
IROH_SRC="$REPO_ROOT/relay/iroh-relay"
WS_SRC="$REPO_ROOT/relay/ws-relay"

# Same rule the VM script enforces: open access with limits (runbook 9b), so no active access line.
if grep -q '^access' "$IROH_SRC/config.toml"; then
    die "relay/iroh-relay/config.toml has an active 'access' line; the decision is open access with limits. Refusing to bundle it."
fi

rm -rf "$B"
mkdir -p "$B/nginx" "$B/site" "$B/remote" "$DEPLOY_VM_DIR/dist/cache"

log "building ws-relay for linux/$ARCH"
(cd "$WS_SRC" && GOOS=linux GOARCH="$ARCH" CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" -o "$B/ws-relay" .)

# Iroh relay: the official static build. A checksum is built in for one file only; everything else needs IROH_SHA256.
case "$ARCH" in amd64) IROH_ARCH=x86_64 ;; arm64) IROH_ARCH=aarch64 ;; esac
expected="${IROH_SHA256:-}"
if [ -z "$expected" ] && [ "$IROH_VERSION" = v1.4.1 ] && [ "$ARCH" = amd64 ]; then
    expected=75254641e3fad7fcbac6dee4d485556fbe06ef708340a9d8810469270ba626b8
fi
[ -n "$expected" ] || die "no checksum known for iroh-relay $IROH_VERSION on $ARCH. Download it once, check it by hand, and set IROH_SHA256."
tarball="iroh-relay-$IROH_VERSION-$IROH_ARCH-unknown-linux-musl.tar.gz"
cached="$DEPLOY_VM_DIR/dist/cache/$tarball"
if [ ! -f "$cached" ]; then
    log "downloading $tarball"
    curl -fsSL -o "$cached.part" "https://github.com/n0-computer/iroh/releases/download/$IROH_VERSION/$tarball"
    mv "$cached.part" "$cached"
fi
echo "$expected  $cached" | sha256sum -c --quiet - || { rm -f "$cached"; die "checksum of $tarball does not match; deleted it"; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
tar -xzf "$cached" -C "$tmp"
found="$(find "$tmp" -name iroh-relay -type f | head -n 1)"
[ -n "$found" ] || die "no iroh-relay program inside $tarball"
install -m 0755 "$found" "$B/iroh-relay"

log "copying configs and the site"
cp "$IROH_SRC/config.toml" "$IROH_SRC/iroh-relay.service" "$IROH_SRC/nginx-limits.conf" \
   "$IROH_SRC/nginx-locations.conf" "$IROH_SRC/renew-hook.sh" "$B/"
cp "$WS_SRC/deploy/ws-relay.service" "$B/"
cp "$WS_SRC/deploy/nginx/bootstrap-http.conf" "$WS_SRC/deploy/nginx/relay.ownspace.in.conf" "$B/nginx/"
cp "$REPO_ROOT/site/nginx/joinowntally.ownspace.in.conf" "$B/nginx/"
# The site without its dev files (tests, the assetlinks tool, nginx config, README).
tar -C "$REPO_ROOT/site" -cf - \
    --exclude=./nginx --exclude=./README.md --exclude=./assetlinks.py --exclude=./assetlinks.template.json \
    --exclude=./test_assetlinks.py --exclude=__pycache__ . | tar -C "$B/site" -xf -
cp "$DEPLOY_VM_DIR/remote/"*.sh "$B/remote/"
chmod 0755 "$B/remote/"*.sh

commit="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
    warn "the working tree has uncommitted changes; the bundle is not exactly commit $commit"
    commit="$commit+dirty"
fi
printf 'built %s\ncommit %s\narch %s\niroh %s\n' "$(date -u +%FT%TZ)" "$commit" "$ARCH" "$IROH_VERSION" > "$B/VERSION"
log "bundle ready in $B"
cat "$B/VERSION"
