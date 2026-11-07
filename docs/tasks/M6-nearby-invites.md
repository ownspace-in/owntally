# M6 tasks: Nearby sync, invites and joining

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M6. Rules: [DESIGN.md](../DESIGN.md) ("Pairing: the ticket", "Sync transports", "Flows"), [CRYPTO.md](../CRYPTO.md) (section 7 and clarification C4), [WIRE_FORMAT.md](../WIRE_FORMAT.md) (section 6), mockups 09, 09b, 10 and 11.

Goal (BUILD_PLAN): Nearby transport with a fallback for the same Wi-Fi, the invite screen (QR, share link, copy code, expiry, revoke), QR scan, the join flow, host approval, the join requests screen and the sync status screen (the last two exist from M4 and M5; here they get real connections).

**Decisions L1 and L2 are made (8 Nov 2026, below). Nothing is built until the owner approves this plan.** More decisions may come before the first task starts; they are listed under "Open questions".

## Progress

| Task | Status |
|---|---|
| T6.1 plan and decisions | In progress: L1 and L2 decided, open questions below. |
| T6.2 to T6.12 | Not started. |

## What already exists

- `Ticket` and `TicketRegistry` in `app/crypto` (issue, text form, expiry, single use, exact text rules). The registry is in memory only.
- Join requests (M4): the host's screen, approve and decline, the name check. Today a request only comes from a debug tool.
- The sync engine, the coordinator, the status model and the Sync screen (M5), run over any `Transport`. The app currently uses `NoTransport`.
- `Transport` and `Link` interfaces, an in-memory network, and a WebSocket transport (M0).
- Spike code for Nearby Connections (`spike/`), proven on real phones (Wi-Fi on). Emulators cannot run Nearby.

## Decisions made

- **L1 (8 Nov 2026): the "same Wi-Fi fallback" is local discovery.** For phones where Nearby does not work (no Google Play Services, risk R3), the host announces itself on the local network (mDNS through the system's `NsdManager`) and listens on a local port; the other phone finds it and connects over a plain socket. No internet and no Google services needed. Cannot be tested on the emulator without extra setup; real phones and a loopback test cover it.
- **L2 (8 Nov 2026): the local link comes in three kinds, all built, and the app chooses: Noise, then TLS 1.3, then a plain link.**
  - **Plain:** the same messages as every transport, with a signed handshake in which the host proves it holds the key named in the ticket (a challenge it signs with its existing signing key). Sniffers on the network see public keys, counters, sizes and timing, not content (CRYPTO.md section 10).
  - **TLS 1.3:** the platform's own TLS, each side with a self-made certificate checked against the member's known key (the ticket's host key for a joiner). No cryptography of our own.
  - **Noise:** a Noise handshake through a library, each side with its key. **A new third-party cryptography library: it must be vetted and pinned (version, licence, maintenance, vulnerability check) and approved by the owner before it is added (see Open questions).**
  - **Choice:** the strongest first (Noise, then TLS, then plain). If one does not connect within a few seconds, the app moves down to the next. The signed announcement of what a phone supports means a failed attempt can be told apart from an older app. The status line says which kind was used. **Known cost (owner accepted this choice over "never fall back"):** a hostile network that breaks the stronger handshakes pushes everyone to the plain link, where the metadata above is visible. Nothing else is lost.

## Plan (tasks)

Proposed order. Each task ends with tests first, as in M5.

- **T6.2 Join handshake.** Pure protocol in `app/sync`: the joiner shows the ticket id and the host proves its key; the joiner sends a request sealed with the group key (name, phone, keys); the host checks the ticket (issued, unused, not expired) and puts the request in the join requests list; after approval the host sends the group's history. Tests on the fake network: wrong host key, expired, reused, forged and malformed tickets are all refused without saying why.
- **T6.3 Issued tickets that survive a restart (CRYPTO C4).** A Room table for issued and used tickets; the group key is not stored a second time in the clear (keep a hash or seal it). Test: issue, restart, still single use.
- **T6.4 Invite screen** (mockup 10): QR, share link, copy code, expiry, revoke. QR via a vetted library (to be named in the task; no network).
- **T6.5 Join flow** (mockups 09 and 09b): paste or scan, waiting for approval, history arrives, and the first-run recovery of a phrase trying key slots in order (CRYPTO clarification C8).
- **T6.6 Host side wiring:** requests reach the join requests screen; approve admits (M4) and starts the history sync.
- **T6.7 Local link without security:** framing over sockets, the local discovery of L1, the plain link with the signed handshake. Loopback tests.
- **T6.8 TLS link.** **T6.9 Noise link** (after the library is approved). **T6.10 Choosing between the three** per L2, with the status showing the kind.
- **T6.11 Nearby transport and its screen:** runs only when the person asks (DESIGN.md), wait 15 s; permissions explained before they are asked; Location may be needed for discovery (spike finding).
- **T6.12 App wiring and hand back:** a real `Transport` in the app container (Nearby plus local), the Sync screen "Nearby" button, the manual two-phone checklist (invite by QR, scan, approve, history arrives, an expense on each side converges, airplane mode with Wi-Fi only), simulation still passing, docs.

## Open questions

1. **The Noise library.** Which one, and who vets it. Tink has no Noise. The owner decides (BUILD_PLAN: anything involving a new cryptographic dependency is the owner's).
2. **QR code library** (generate and scan). Scanning on Android needs camera permission and either Google's code scanner (Play Services) or an independent library. Phones without Play Services need the second.
3. **Who tests on real phones.** Nearby and local discovery cannot be tested on the emulator; the acceptance needs two phones and the owner's report.
