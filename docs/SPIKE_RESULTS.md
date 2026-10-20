# M0 spike results

**Decision (owner fills in):** _not decided yet_

Rule from [BUILD_PLAN.md](BUILD_PLAN.md): if Iroh passes, it is the main transport and the WebSocket relay stays as a backup behind the same `Transport` interface. If Iroh fails, the WebSocket relay is the main transport.

## How to run the phone tests

1. Build the spike app: `cd spike && ./gradlew :app:assembleDebug`. Install `app/build/outputs/apk/debug/app-debug.apk` on both phones.
2. Relay test: turn Wi-Fi off on both phones (mobile data only, ideally on different carriers). On phone B pick "Phone B", "Relay", the relay address and the same room name, then Start. On phone A pick "Phone A", the same settings, Start, then Run test.
3. Nearby test: both phones in the same room. Pick "Nearby" on both. Phone B: Start. Phone A: Start, then Run test. Try with Wi-Fi on, Wi-Fi off, and airplane mode with Bluetooth and Wi-Fi switched on.
4. Tap "Copy log" on phone A and paste the `RESULT` line into the table below. Repeat at least 3 times per setup.

Launch options for scripted runs: `adb shell am start -n owntally.spike/.MainActivity -e relay <url> -e room <name> -e role A -e mode RELAY --ez autostart true --ez autotest true`.

## Results

| Candidate | Setup | Runs | Works | Link | Connect time | Messages ok | Avg / p95 round trip | App size added | Min Android tested | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| WebSocket relay | Real phones, different mobile networks, `relay.ownspace.in` | | | | | | | | | |
| Iroh | Real phones, different mobile networks, direct | | | | | | | | | |
| Iroh | Real phones, forced through relay | | | | | | | | | |
| Nearby | Real phones, same room | | | | | | | | | |

## Preliminary (emulator, not a valid test)

Run on 17 Oct 2026. Android 16 emulator (API 36) on the development machine, talking to the Go relay and a Go test responder on the same machine over loopback. This shows the code works on the Android runtime. It says nothing about mobile networks, carriers or Nearby.

| Candidate | Setup | Runs | Works | Link | Connect time | Messages ok | Avg / p95 round trip |
|---|---|---|---|---|---|---|---|
| WebSocket relay | Emulator to local relay (cleartext), responder on host | 1 | Yes | RELAYED | 2460 ms | 30 of 30, 0 corrupt | 41 ms / 138 ms |

Also checked on the development machine (not on phones): the transport reconnects after the relay closes the socket, and after the relay process is killed and restarted (`app/sync` tests, including one against the real relay binary).

## Relay deployed (T0.4)

Run on 20 Oct 2026 from the development laptop against the real VM (`wss://relay.ownspace.in/ws`, nginx plus certbot, relay under systemd). `https://relay.ownspace.in/healthz` prints `ok`. `go run ./tools/smoke`, three runs:

| Run | Connect | Round trip |
|---|---|---|
| 1 | 249 ms | 19 ms |
| 2 | 139 ms | 20 ms |
| 3 | 145 ms | 14 ms |

This is a laptop on a fixed line, not a phone on mobile data.

## Not yet tested

- Real phones on mobile data (WebSocket relay). The relay itself is now deployed.
- Nearby Connections: the code compiles but has never run. Emulators cannot test it.
- Iroh: not started (T0.6).
