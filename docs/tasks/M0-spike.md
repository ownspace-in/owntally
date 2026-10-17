# M0 tasks: choose the internet transport

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M0. This is throwaway spike code, kept in `spike/` and not shipped. The only things that carry forward are the `Transport` interface, the WebSocket relay program (if chosen) and `docs/SPIKE_RESULTS.md`.

What you need from the owner before starting: two Android phones (Android 8 or later) on different mobile networks, a small Linux VM with a public IP, and DNS for `relay.ownspace.in`. If any of these is missing, stop and ask.

Each task below ends with "Done when", which must be checkable.

## Progress

| Task | Status |
|---|---|
| T0.1 spike project | Built (debug APK, 9 MB). Not yet installed on two phones. |
| T0.2 `Transport` interface | Done, in `app/sync` with an in-memory fake and 4 passing tests. |
| T0.3 WebSocket relay in Go | Done, in `relay/ws-relay` with 14 passing tests (race detector on). 100 idle connections used about 11 MB. |
| T0.4 deploy the relay | Runbook, systemd unit, Caddyfile and a smoke-test tool written. Not yet run on a real VM. |
| T0.5 WebSocket transport on Android | Code done and tested: 17 tests in `app/sync` including against the real relay, and the spike app ran the full probe on an Android emulator (30 of 30). Not yet run on two real phones on mobile data. |
| T0.6 Iroh attempt | Not started. |
| T0.7 Nearby Connections | Code written (`spike/.../NearbyTransport.kt`) and compiles. Never run. Needs two phones. |
| T0.8 results and decision | Template in `docs/SPIKE_RESULTS.md`, with a preliminary emulator row. Waiting on phone results. |

---

## T0.1 Spike project

Do:
- Create `spike/` as a Gradle (Kotlin DSL) Android project: minSdk 26, one Compose screen with a text log and buttons.
- Add a version catalog. Add OkHttp. Do not add other libraries yet.

Done when: the app installs on both phones and shows an empty log with a "Send test message" button.

## T0.2 `Transport` interface

Do: create this in a pure Kotlin file with no Android imports, so it can later move to `app/sync/`.

```kotlin
interface Transport {
    /** Connect to the member with this public key, in this group. */
    suspend fun connect(group: GroupRoom, peer: PublicKey): Link
    /** Accept incoming connections for this group. */
    fun incoming(group: GroupRoom): Flow<Link>
}

interface Link {
    val peer: PublicKey
    val kind: LinkKind            // DIRECT, RELAYED, NEARBY
    suspend fun send(bytes: ByteArray)
    val received: Flow<ByteArray>
    suspend fun close()
}

enum class LinkKind { DIRECT, RELAYED, NEARBY }
```

Rules: payloads are opaque bytes (the sync protocol and encryption sit above this). No transport may read or interpret them.

Done when: the interface compiles, and a fake in-memory implementation passes a test that sends 100 messages in order between two links.

## T0.3 WebSocket relay program

Language: Go (decided by the owner), as a single static binary (one small file to deploy, low memory). Put it in `relay/ws-relay/`.

Behaviour:
- Listens for secure WebSocket connections at `/ws?room=<roomId>&id=<publicKeyHex>`.
- `roomId` is a hash of the group id (the relay must not learn the real group id).
- Messages are binary frames. A frame sent by one client is forwarded unchanged to every other client in the same room. If the frame starts with a 32 byte target public key, forward it only to that client. Document which you chose.
- Stores nothing. Never decrypts. Logs only counts and errors, never contents or keys.
- Limits: 64 KB per frame, 20 clients per room, ping every 30 seconds, close idle connections after 90 seconds, simple per-address rate limit.
- TLS is handled by a reverse proxy (Caddy or nginx) or by the program itself using Let's Encrypt. Pick one and document it.
- Authentication is out of scope for the spike, but write down in `relay/ws-relay/README.md` what production needs (each client proves it holds its key by signing a server challenge).

Tests: a Go test that starts the relay, connects three clients, and checks forwarding, targeting, size limit, room isolation (a client in another room receives nothing).

Done when: the tests pass and the binary runs locally under 30 MB of memory with 100 idle connections.

## T0.4 Deploy the relay

Do: follow a runbook you write in `relay/ws-relay/RUNBOOK.md` on the owner's VM: install, systemd unit with restart on failure, firewall (only 80 and 443 open), TLS for `relay.ownspace.in`, a health check URL, and one command to see logs.

Done when: `wss://relay.ownspace.in/ws` accepts a connection from a laptop test client, and someone who has not seen the project could repeat the steps from the runbook.

## T0.5 WebSocket transport on Android

Do: implement `Transport` over OkHttp WebSockets talking to the relay, in the spike app.
- Reconnect with back off (1, 5, 30 seconds).
- Report `LinkKind.RELAYED`.

Test (two phones, different mobile networks, Wi-Fi off): the first phone sends 1, 1,000 and 50,000 byte messages and the other receives them intact.

Done when: all three message sizes arrive correctly in both directions, ten times in a row, and the numbers are written into `docs/SPIKE_RESULTS.md` (connect time, failures, Android versions).

## T0.6 Iroh attempt (time-box: 3 working days)

Do, in this order, stopping at the first that works:
1. Add the official Kotlin package for Iroh from Maven Central. Make two phones exchange a message on different mobile networks. Watch for calls that never finish on Android.
2. If step 1 hangs or crashes: try a community build with JNI-based bindings.
3. If that fails: build the Kotlin bindings from the Iroh FFI source for `arm64-v8a` (and `armeabi-v7a` if easy), and use that.

For whichever works, test both:
- Direct: two phones, different mobile networks, relay not forced.
- Relayed: force traffic through `relay.ownspace.in` (run the Iroh relay program on the VM following `docs/DESIGN.md` "Hosting your own relay"; if that is not set up, use Iroh's public relay and say so in the results).

Record, for each attempt: works or not, exact error if not, how many of 10 runs connected, direct or relayed, time to connect, added app size in MB, minimum Android version tested.

Done when: either one attempt passes the rule in the build plan (connection completes, no hanging calls, relayed path works, app size acceptable), or all three are recorded as failed with errors, and you stop. Do not extend the time-box without asking the owner.

## T0.7 Nearby Connections

Do: add `play-services-nearby`, implement a `Transport` for two phones in the same room (advertise and discover, confirm, send bytes). Report `LinkKind.NEARBY`.

Test: 1, 1,000 and 50,000 byte messages, Wi-Fi on and off, airplane mode with Bluetooth and Wi-Fi turned on.

Done when: results written, including which radio was used and time to connect.

## T0.8 Results and recommendation

Do: fill in `docs/SPIKE_RESULTS.md` with one table, one row per candidate (WebSocket relay, Iroh, Nearby), columns from the build plan step 4, plus a short recommendation following the decision rule.

Done when: the owner has read it and written the decision at the top of the file. Only then close M0.

---

# What to hand back

- `spike/` (can stay in the repo, marked as throwaway).
- `Transport` and `Link` interfaces, moved into the real project when M1 starts.
- `relay/ws-relay/` with README and runbook (kept if the WebSocket relay is chosen or kept as a backup).
- `docs/SPIKE_RESULTS.md`.
