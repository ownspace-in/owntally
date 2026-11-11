# M12 tasks: relay sign-in (a signed challenge when connecting)

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M12. Rules: [DESIGN.md](../DESIGN.md), [CRYPTO.md](../CRYPTO.md), `relay/ws-relay/README.md` (the relay's wire protocol), [SECURITY_REVIEW.md](../SECURITY_REVIEW.md) finding N3. Tests first. Owner decision (10 Nov 2026): a signed challenge at connect.

Goal (BUILD_PLAN): the WebSocket relay accepts a connection for a member key only from the holder of that key. Acceptance: a client that names a key it does not hold is refused and cannot replace the real member's connection; tests with a wrong signature, a replayed signature (other nonce, other room) and a good one; the privacy text and `SECURITY_REVIEW.md` N3 updated.

## The problem (finding N3, read in the third review pass)

The relay takes the sender id from the address the client gives (`?id=<member key>`), stamps every forwarded frame with it, and lets a new connection with the same id **replace** the old one. So anyone who knows a room (the hash of a group id) and a member's public key can (a) knock that member off the relay, again each time they reconnect, (b) flood that member until the relay drops them as "too slow", and (c) send frames that say they come from that member. (c) cannot make anyone a member: the app checks the sender against the TLS certificate in `SecureOverCarrier`. (a) and (b) are denial of service on the relay path only; Iroh and the local network are unaffected.

## What exists

- `relay/ws-relay` (Go, `main.go`, `hub.go`, `relay_test.go` 14 tests): a hub of rooms, per-address connect limits, per-connection rate limit, ping, "too slow" kick; `go` 1.27 is installed here, so the relay can be built and tested.
- `WebSocketTransport` (Kotlin, `app/sync`): one WebSocket per group room, `?room=...&id=...`, status `CONNECTING/OPEN/RECONNECTING/CLOSED`; `SecureOverCarrier` runs TLS end to end between the members over it.
- Tests: `WebSocketTransportTest` (a MockWebServer stand-in for the relay), `RealRelayTest` (the real Go binary through `RELAY_BIN`; skipped without it), `RelayLimitsTest`.
- Member keys are Ed25519 (Tink); `LinkAuth`/`JoinCrypto` already sign labelled messages and refuse small-order keys (`WeakKeys`).

## Design (proposed)

After the WebSocket upgrade the relay and the client make a three-step exchange **before the relay treats the client as being in the room** (until then it forwards nothing to it and nothing from it):

1. relay to client: `[0x01][nonce, 32 random bytes]` (33 bytes), the challenge, a fresh nonce for this connection.
2. client to relay: `[0x02][signature, 64 bytes]` over the ASCII label `owntally/v1/relay-auth`, then the room bytes (as given in `?room=`), the member key (as given in `?id=`) and the nonce. Ed25519 by the member key.
3. relay to client: `[0x03]` (ready), after verifying the signature with the key named in `?id=`; or the relay closes the connection with a WebSocket close code (4401, "not the holder of this key").

Only after step 3 does the relay put the client in the room (the replacement of an old connection with the same id happens then, and only the key's holder can do it), and from then on the protocol is exactly today's. States make the frames unambiguous (before "ready" the relay sends nothing but the challenge). Rules in the relay: the whole exchange must finish within 5 seconds or the connection is closed; a signature from a small-order key (the seven encodings the app refuses, `WeakKeys`) is refused; the nonce comes from `crypto/rand`; a signature is good for one connection only because the nonce differs; the room in the message stops a signature being used in another room. The relay needs only the standard library (`crypto/ed25519`).

In the app `WebSocketTransport` signs the challenge with the member's key (`RelayAuth` in the crypto module, same pattern as `LinkAuth`) and reports `OPEN` only after "ready"; a refusal (close code 4401) is recorded as the last error.

What this does not do: it does not hide anything from the relay (it already sees the key, the room, addresses, timing and sizes: the privacy text is right as it is), and it does not stop someone who holds a member's key (they are that member). It also does not stop a stranger from using up connections on the relay with keys of their own: that is the per-address limit's job, unchanged.

## Decisions made (owner, 11 Nov 2026)

- **D1 = A: required from the start.** The new relay refuses any client that does not sign. The owner updates the relay on the VM and the app on their phones together; nothing is released, so nobody else is affected. There is no optional mode.
- **D2 = yes: M12 is finished before 1.0.** The first release has no known way for a stranger who knows a room and a member's key to knock that member off the relay.

## Tasks

- **T12.1 Documents first.** `relay/ws-relay/README.md` (the exchange, the close code, the 5-second limit, the weak keys), `CRYPTO.md` (a section "Relay sign-in": label, message, why the room and the nonce), `SECURITY_REVIEW.md` N3 (status when done).
- **T12.2 The signature in the app's crypto module.** `RelayAuth.sign/verify` (Tink, refuses small-order keys); independent test vectors from Python (`crypto_vectors.py`) that the Kotlin tests check byte for byte **and the Go relay's tests also check** (one vector file read by both sides, so the two implementations cannot disagree).
- **T12.3 The relay (Go).** Tests first: a good signature is admitted and can talk; a wrong signature, another key's signature, a signature for another room, a signature for another nonce (replay), a small-order key, a short or malformed answer, a frame sent before answering, and no answer within the time limit are all refused or closed and the old connection of the real member is untouched; two clients naming the same key cannot both end up in the room; an unauthenticated client receives nothing and cannot send. Then `main.go`/`hub.go`, the flag defaults, `RUNBOOK.md` (build, copy, restart; the order of steps; how to see a refusal in the counts), `deploy/` unchanged unless the unit file needs a flag.
- **T12.4 The app's transport.** Tests first: `WebSocketTransportTest`'s stand-in learns the exchange (and refuses a bad signature); `RealRelayTest` runs the real binary with real keys: two members talk, a stranger naming a member's key is refused and cannot replace them, the real member stays connected; `RelayLimitsTest` and the other relay tests updated. Then `WebSocketTransport` (the exchange, `OPEN` after ready, a refusal recorded), `InternetWays` passes the member's identity.
- **T12.5 Texts.** Nothing in the privacy text changes (the relay sees the same things); `SECURITY_REVIEW.md` N3 and the privacy facts are checked for a sentence that is now wrong; the release checklist gets the deploy step.
- **T12.6 Hand back.** All unit tests (Kotlin and Go), lint, release build and bundle, the vulnerability check (`go.mod` is not covered by the script: `govulncheck` or `go list -m -u` result written down), the emulator suites, the smoke test, the real relay test, the owner's checklist (deploy the new relay, then the app; a test with a phone and a second client naming its key), the pull request.

## Results (11 Nov 2026)

- **T12.1 Documents first. Done.** `CRYPTO.md` section 7a, the relay's README (the exchange, close codes 4401 and 4408, the 5-second limit, no optional mode), the runbook ("Updating to the version with sign-in" and three rows of "If something goes wrong").
- **T12.2 The signature. Done.** `RelayAuth` in the crypto module (Tink; small-order keys refused; label `owntally/v1/relay-auth`, the room's length as one byte, the room, the key, the nonce). **Independent Python vectors** (`crypto_vectors.py`, `relayAuth`, 8: two good, another room, a replay with another nonce, another key's signature, two small-order keys, a short signature) are read **by both the Kotlin tests and the Go relay's tests**; the relay also checks that the message it builds equals one the test wrote out on its own. `RelayAuthTest` (5).
- **T12.3 The relay (Go). Done.** `auth.go` and `handleWS`: the client is in no room until it has signed in; nothing is forwarded to or from it before. 12 new tests (`auth_test.go`): fresh nonce each time; the holder is admitted and talks; an answer signed by another key, for another room, a replay of a recorded answer, small-order keys (three encodings), malformed answers (six shapes), a text frame, no answer in time (4408), a silent client receives nothing and is in no room, **an impostor naming a member's key is refused and the member stays connected and keeps hearing the room**, only the holder can replace their own connection, and the vectors. The 14 older tests now sign in with real keys. **7 mutations, all caught:** signature never checked, small-order keys allowed, the room left out, the same nonce every time, no time limit, sign-in skipped, any answer type accepted. The tools (`smoke`, `idleconns`) sign in through a shared helper written separately from the relay; `responder` (an M0 spike tool that cannot sign) is marked retired.
- **T12.4 The app's transport. Done.** `WebSocketTransport` takes the member's identity, signs the challenge, is `OPEN` only after the relay says ready, treats a frame it did not expect before ready as an error (never as a message) and records a refusal ("the relay did not accept this phone's key (4401)"). `WebSocketTransportTest` +6 (the answer verifies for this room and key only; a fresh challenge at every reconnection; a refusing relay; a relay that never says ready; a frame before ready is not a message; an unexpected frame ends that connection), **`RealRelayTest` against the real Go binary (3, none skipped), including the impostor: refused with 4401 while the real member stays open and still receives**, and a whole sync through the real relay. **4 mutations caught** (signs for another room, open before ready, frames taken as messages before ready, refusal not recorded).
- **T12.5 Texts. Done.** Nothing in the privacy text changed: the relay sees what it always did. `SECURITY_REVIEW.md` N3 is marked fixed; the release checklist has the deploy steps.
- **T12.6 Hand back. Done.** Unit tests (Kotlin, with the real relay), Go tests and vet, lint, release APK and bundle, the vulnerability check (no new dependency: the relay has two, `coder/websocket` and `x/time`, and signs with the standard library), the Python tests, the emulator suites in light and dark, the whole suite against the shrunk build (28 completed, 0 failed), the smoke test of the shipped APK. **Not run:** `govulncheck` for the relay's `go.mod` (installing it was refused in this session; run it where you deploy), a real phone against the real VM, Android 11.

**The one thing that waits for the owner: deploying.** `InternetWaysInstrumentedTest` goes through the live relay at `relay.ownspace.in`, which is still the version without sign-in, so the test that uses the relay alone is **skipped with its reason** (only when the live relay opens the connection and never sends a challenge; an unreachable or refusing relay still fails it). After the owner deploys the new relay (RUNBOOK.md) it runs, and should be run once on a device: it is the end-to-end check on a phone against the real VM.

## Open questions

None at the moment. What only the owner can do: deploy the new relay to the VM and install the new app together, and the review of the relay's sign-in code.
