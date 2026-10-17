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
| Clients per room | 20 (`-max-per-room`) |
| Messages per connection | 50 per second, burst 100, then the connection is closed |
| New connections per address | 30 per minute (`-connects-per-min`) |
| Ping | every 30 seconds, connection closed after 90 seconds without an answer |
| Slow receivers | closed if their 64 message queue fills up |

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

- **Authentication.** Today anyone can join any room by guessing its id, and can claim any `id`. Production must have each client prove it holds its private key by signing a challenge the relay sends on connect, and must tie the room to the group (for example, the relay only admits keys the group has announced). Without this, a stranger who learns a room id could send junk into the room or squat on a member's key. The payloads are still encrypted and signed, so they cannot read or forge expenses, but they could disrupt sync.
- **TLS.** Terminated by the proxy. See [RUNBOOK.md](RUNBOOK.md).
