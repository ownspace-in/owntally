# M0 spike results

**Decision (owner, 28 Oct 2026):** support all three. Iroh is the main internet transport, the WebSocket relay stays as a backup behind the same `Transport` interface, and Nearby is used for same-room sync. We run our own Iroh relay at `relay.ownspace.in` (the WebSocket relay stays on the same host under `/ws`). Until our Iroh relay is running, Iroh uses the public n0 relays, as the design allows for development.

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
| WebSocket relay | Redmi 23053RN02I (Android 15) and Galaxy S24 SM-S928B (Android 16), both on mobile data, `relay.ownspace.in` | 10 reported by the owner, all worked, with the phones swapping the A and B roles (log lines not yet pasted); earlier 2 logged runs on mobile data and 1 on Wi-Fi | Yes | RELAYED | socket was already open, so connect time not measured | 30 of 30, 0 corrupt | 177 ms / 345 ms and 157 ms / 320 ms | 15.7 MB release APK (arm64, includes the Iroh library; the relay path alone adds nothing) | API 35 and 36 | Carriers were not recorded; different carriers not yet confirmed. Also 1 run on Wi-Fi on both: 30 of 30, 75 ms / 86 ms. |
| Iroh | Redmi 23053RN02I (Android 15) and Galaxy S24 SM-S928B (Android 17), both on mobile data, Iroh's public relay and discovery (`aps1-1.relay.n0.iroh.link`), debug build 28 Oct 21:20 | 10: 3 logged (2 with the Redmi and S24 swapping roles, 1 with a Realme RMX5061 on Wi-Fi as B and the S24 on mobile data as A), plus 7 more reported by the owner as running fine, with no per-run numbers or setups recorded | Yes | Starts RELAYED, reported DIRECT by the end of the run | 892 ms, 768 ms and 644 ms | 30 of 30 each, 0 corrupt | 211 ms / 346 ms, 215 ms / 354 ms and 113 ms / 177 ms | About 6 MB compressed, 14 MB unpacked (arm64) | API 35, 36 and 37 | After the last message the idle link ended on B about a minute later with `IrohException` (the spike never closes links; the product must handle idle timeouts). The earlier stall (reply never reached the dialer) was a bug in the spike's threading, fixed by running Iroh reads and writes on `Dispatchers.IO`; the cause was not proven. Whether the two phones were on the same carrier was not recorded, so DIRECT may not hold across carriers. |
| Iroh | Real phones, forced through relay | 0 | Not tested | | | | | | | `relay.ownspace.in` does not run an Iroh relay yet, and the spike has no way to force relay-only. The public n0 relay carried the connection until the direct path opened. |
| Nearby | Same two phones side by side, Wi-Fi on | 10 reported by the owner, all worked, with the phones swapping the A and B roles (log lines not yet pasted); earlier 3 logged runs | Yes (after the location permission fix) | NEARBY | 4.3 s, 6.0 s, 6.3 s | 30 of 30 each, 0 corrupt | 38 ms / 66 ms, 36 ms / 78 ms, 191 ms / 448 ms | Play services Nearby 19.3.0 | API 35 and 36 | First attempts failed with `MISSING_PERMISSION_ACCESS_COARSE_LOCATION` until the app requested coarse and fine location on all Android versions. Each A run also logged a spurious `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` after connecting, without affecting the result. Wi-Fi off and airplane mode setups not yet tested. On a Galaxy S24 Ultra updated to Android 17 (API 37), searching as phone A failed with `8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION` even though all permissions were granted. Cause: the phone's Location switch was off. Advertising works without it, discovery does not, and the error text is misleading. After turning Location on it worked. A newer library (19.5.1) made no difference. The product must detect this and tell the user to turn Location on. |

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

## Iroh, emulator findings (T0.6, not a valid test)

Official Kotlin package `computer.iroh:iroh-android:1.1.0` on two Android 16 emulators through Iroh's public relay (not ours). The native library loads and the endpoint comes online in about 1.5 s after bind. One run connected (5.4 s, relayed) and delivered message 1, but the reply never reached the dialer. Two of five scripted runs failed with `CONNECT No addressing information available` and the rest gave no result in 120 s. The cause is not yet known and may be in the spike's dialing and stream code. Size added: about 6 MB compressed and 14 MB unpacked for arm64-v8a. Release build, arm64 only, minified: 15.7 MB APK (not yet smoke-tested).

## Not yet tested

- Real phones on mobile data (WebSocket relay). The relay itself is now deployed.
- Nearby Connections: the code compiles but has never run. Emulators cannot test it.
- Iroh: not started (T0.6).
