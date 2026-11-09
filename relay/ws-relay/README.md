# ws-relay

A small relay that forwards opaque encrypted frames between members of a group. It stores nothing, never reads payloads, and logs only counts and errors.

This is the baseline internet transport from milestone M0. It may become the main transport or a backup, depending on the M0 results (see `docs/SPIKE_RESULTS.md`).

## Wire protocol

Connect with a WebSocket to:

```
wss://<host>/ws?room=<roomHex>&id=<publicKeyHex>
```

- `room`: 16 to 32 bytes as hex. Clients derive it from the group id with a hash, so the relay never learns the real group id.
- `id`: the member's 32 byte public key as hex.
- Only binary frames are allowed. Text frames close the connection.
- Maximum frame size is 64 KB, including the header below.

Frames sent to the relay:

| First byte | Rest of the frame | Effect |
|---|---|---|
| `0` (broadcast) | payload | sent to every other client in the room |
| `1` (targeted) | 32 byte target key, then payload | sent only to the client with that key; dropped silently if that key is not connected |

Frames received from the relay are `[sender key, 32 bytes][payload]`. Anything else, an unknown first byte or a short targeted frame, closes the connection.

Reconnecting with the same `id` in the same room replaces the old connection.

The relay does not queue messages. A message for a client that is not connected is lost, and the sync protocol above it recovers on the next sync.

## Limits

| Limit | Value |
|---|---|
| Frame size | 64 KB |
| Clients per room | 20 by default (`-max-per-room`); **production: 40** |
| Messages per connection | 50 per second, burst 100, then the connection is closed |
| New connections per address | 30 a minute by default (`-connects-per-min`); **production: 300** |
| Ping | every 30 seconds, connection closed after 90 seconds without an answer |
| Slow receivers | closed if their 64 message queue fills up |

### How the app meets these limits (measured 9 Nov 2026, `RelayLimitsTest`)

- **Frames and message rate:** the app sends frames of at most 16,000 bytes and at most 40 a second, under both limits. A history of 5,000 signed entries (about 2.2 MB) crossed the real relay in about 4 seconds and was not cut off.
- **Connections:** the app holds one connection per group while it listens, so a phone in 40 groups opens 40 at once. With the default 30 a minute from one address, 30 get in at once and the rest wait their turn (all 40 were in after about 36 seconds). The limit is per address as the relay sees it, and many phones on one mobile carrier or one office network share an address, so a few busy phones can hold back the others behind it for a minute at a time. Nothing is lost (the app tries again after 1, 5 and 30 seconds), but joining and "Try now" can be slow. **Decision R3 (9 Nov 2026): production runs `-connects-per-min 300`**; the cost is only the work of accepting connections.
- **Clients per room:** exactly 20, which is the largest group. A removed member who still syncs, or a person waiting to be admitted to a full group, is a 21st client and gets `503 room full` (the app reports the person as not reachable). **Decision R3: production runs `-max-per-room 40`.**

The program's own defaults are unchanged (30 and 20). The production values are in `deploy/ws-relay.service`; to apply them on a running VM see `RUNBOOK.md` ("Change limits").

## Run

```
go build -ldflags="-s -w" -o ws-relay .
./ws-relay -addr 127.0.0.1:8080
```

It speaks plain WebSocket and expects a TLS reverse proxy (nginx, config in `deploy/nginx/`) in front. Use `-trust-proxy` only when behind that proxy, so rate limits use the client address from `X-Forwarded-For`. `GET /healthz` returns `ok`.

Test helpers in `tools/`: `smoke` (checks a running relay), `idleconns` (memory check), `responder` (plays phone B for the spike app).

Flags: `-addr`, `-max-per-room`, `-trust-proxy`, `-connects-per-min`.

Deploying on a VM: see [RUNBOOK.md](RUNBOOK.md). `go run ./tools/smoke -url wss://host/ws` checks a running relay.

## Test

```
go test -race ./...
```

Memory check: `tools/idleconns` opens many idle connections. With 100 idle connections the relay used about 11 MB of memory in the M0 spike (6 MB binary).

## Not in the spike, needed for production

- **Authentication.** Still not built. Today anyone who knows a room's name and a member's public key can connect with that key, and a new connection with the same key replaces the old one, so the real member's connection can be pushed off now and then. Since M7 this cannot expose or change anything: the app runs TLS between the two phones inside the relay's frames, each side proving the member key it holds (`docs/tasks/M7-internet-sync.md`, R1), so a stranger can disrupt but not read, forge or change entries. Whether to add a proof of the key at the relay (each client signs a challenge the relay sends on connect, and the relay only admits keys the group has announced) is the owner's decision; the rate limits and the other ways phones reach each other (the same network, Iroh) are the measures today.
- **TLS.** Terminated by the proxy. See [RUNBOOK.md](RUNBOOK.md).
