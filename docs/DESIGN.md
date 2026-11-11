# OwnTally: design approach

Android app to split expenses between friends with no server of our own.
UI mockups: see the design/ folder (screens to be exported there).

## Decisions

| Question | Decision |
|---|---|
| Both phones online at the same moment to sync? | Yes, acceptable. No mailbox. |
| License | MIT. |
| Third party (relay) allowed? | Yes, as the last resort only: the app uses our relay when a direct connection fails. No setting and no admin switch; the app decides (owner decision, 4 Nov 2026, see Relay). |
| Google Play Services? | Optional. Use Nearby Connections when available, fall back to same-Wi-Fi discovery. |
| Encryption | Signed events, group-key encryption, encrypted transport (see Encryption). |
| Minimum Android | 11 (API 30). Owner decision, 4 Nov 2026: Android 10 and older are not supported. |
| Backup / recovery | One-time recovery phrase at first run (see Recovery). No backup files. |
| Who can add expenses? | Anyone in the group. |
| Edit expenses? | Not allowed. To fix a mistake, delete and re-add. |
| Delete expenses? | The creator, or the group owner (not admins; owner decision, 4 Nov 2026). Entry stays in history, struck through, excluded from balances. Warn if it changes settled amounts. |
| Remove members? | **Only the owner** (owner decision, 4 Nov 2026). Admins cannot remove anyone, themselves included; a plain member can leave. Removal records which of the member's entries were seen; newer ones are ignored. |
| Settlement plan | Simple matching: at most members minus 1 payments, not always the absolute minimum. |
| WebSocket relay language | Go. |
| Transfer ownership? | Yes: owner signs `OwnershipTransferred`, new owner accepts. Ownership moves only when the owner offers it and the new owner accepts. No admin takeover in v1. The owner can name co-admins, who can approve joins and invite (they cannot remove anyone or change the key). If the owner is away, members can still add and delete their own expenses and settle; joins by admins still work, but removals and key changes wait for the owner. |
| Sign-up / identity | No global account. A master seed is created on first run. Each group gets its own keypair derived from the seed, so members of different groups cannot link you. Only a name and a phone number are collected, set per group on join. The phone adds up cross-group totals locally. One seed backup restores every group. |
| Join approval | Admin approves or declines. No fingerprint check. Tickets: single use, 8 hours; an admin sends a new one if expired. |
| Removal with unsettled balance | Allowed. Balances are rebalanced as if the removed member were never in the group; their entries and payments involving them stay in history but are excluded. Before-and-after shown to the admin, who must confirm. |
| Recovery phrase step | Required at first run, cannot be skipped. |
| Invite link domain | `joinowntally.ownspace.in`, static page only. |
| Pairing method | Ticket shared via tappable link, copy-paste text, or QR (see below). |
| Profile data | Name and phone number only, nothing else. Set per group, visible to members of that group. The phone number is not verified (no OTP, no server): it is a label. |
| Settlement scope | Group level, payment by payment, no frozen round. See Profile and settling. |
| Currency / settlement | INR. The app never moves or checks money. Settling is done outside the app, then recorded. |

## Data model

- Each phone keeps an append-only, signed event log in a local database.
- Events: `ExpenseAdded`, `ExpenseDeleted`, `SettlementRecorded`, `PaymentDisputed`, `MemberJoined`, `ProfileUpdated`, `MemberRemoved`, `OwnershipTransferred`.
- An event is identified by (author key, counter). In v1 there is one phone per member, so the author key stands in for the device id (owner decision, 3 Nov 2026). Deletes are new events, so phones never conflict.
- Sync: swap a version summary (device id to highest counter), then send only the missing events. Merge is a union.
- Balances are derived by replaying the log, so all phones agree.
- Each phone has a keypair. Each group has a shared key. Everything sent is encrypted with the group key.

## Pairing: the ticket

A ticket is about 150 characters: host id, connection hint, group id, group key.

Ways to share it, in order of preference:
1. Tappable link `https://joinowntally.ownspace.in/j#<ticket>`, with the ticket after `#` (never sent to the web host). Needs a small static page on that domain to hand off to the app (see Invite link page). WhatsApp only linkifies `https://`, not `owntally://`.
2. Plain-text ticket through the share sheet, pasted into "Join group".
3. QR code, for people in the same room. QR holds under about 2.9 KB, so it carries the ticket only, never data. **Showing the QR is built; scanning it inside the app is not in this version (owner decision L4, 8 Nov 2026: the scanner libraries add network permissions and Google's reporting, or need Play services); a friend pastes the link or code, or opens the link, instead.**

Safety:
- The ticket contains the group key, so treat it as a secret. Prefer end-to-end encrypted chats. SMS is not encrypted.
- Tickets expire after a short time or one use.
- The host approves each new member ("Rohan wants to join, allow?").
- Joining still needs a live connection to a member to fetch history.

## Sync transports (ladder)

1. Same room: Nearby Connections (Bluetooth / Wi-Fi / Wi-Fi Direct), no internet needed.
2. Same network or IPv6: direct connection.
3. Different networks or cities: direct internet connection via hole punching. Needs STUN for discovery, which is a third party. Expect roughly 60 to 80 percent of phone pairs to connect. Carrier-grade NAT blocks the rest.
4. Fallback: if unreachable, the app uses our relay by itself (owner decision, 4 Nov 2026); if that fails too, tell the user to meet up or try again later.

**Owner decision, 4 Nov 2026 (supersedes the 2 Nov decision below): the relay is the last resort, as in the Relay section. The app decides; there is no setting and no admin switch. The order becomes: 1. Nearby (same room), 2. direct connection (Iroh, with its hole punching), 3. our relay only if direct fails. **Final order (owner, 4 Nov 2026): 1. Nearby, 2. Iroh (direct first, with its own relay as help and fallback), 3. the WebSocket relay as the last resort.** All three ways stay in the app behind the `Transport` interface: Nearby, Iroh (direct, with our Iroh relay as its own fallback) and the WebSocket relay (owner decision, 4 Nov 2026: keep all three). Timeouts (owner decision, 4 Nov 2026): **Nearby runs only when the person asks for it** (the Nearby screen), with its own wait of 15 s; the internet chain is **staggered**: Iroh starts first, and if it has not connected after 3 s the WebSocket relay starts as well, both keep trying and the first to connect wins (the other is cancelled), with an overall connect wait of 15 s, a stall wait of 30 s, and retries after 1, 5 and 30 minutes. The two numbers the owner did not state (Nearby 15 s, overall 15 s) are taken from BUILD_PLAN. The 2 Nov text below is kept for history.**

**Owner decision, 2 Nov 2026 (superseded by the 4 Nov decision above; it supersedes the order above; the other sections below are not yet updated to match):** the app tries transports in this order: 1. Nearby (same room, no server), 2. the WebSocket relay at `wss://relay.ownspace.in/ws`, 3. Iroh (direct, with our Iroh relay at `relay.ownspace.in`). All three stay behind the `Transport` interface. Consequences still to settle with the owner:
- The WebSocket relay carries all data every time and never forms a direct link, so the "Relay" section's rule that nothing goes through a relay unless a group admin enables it needs rewording (or the relay becomes the default for internet sync).
- The relay then sees every user's IP address, timing and data volume on every sync, not only for users who cannot connect directly.
- Server load is higher than with Iroh first; the nginx limits and rate cap in `relay/iroh-relay/` and `relay/ws-relay/` matter more.
- The internet transports are staggered, not tried one after the other (see the timeouts above), so a blocked Iroh costs about 3 s before the relay is also tried. The relay is still the last resort in the sense that it only starts when Iroh has not connected within 3 s.

**M6 decisions (owner, 8 Nov 2026; details in docs/tasks/M6-nearby-invites.md).** The same-network link (L1) is found with Android's own service discovery and comes in two kinds, TLS 1.3 with certificates that carry the members' keys, and a plain link; a phone calls TLS first and falls back to plain if TLS does not connect, but never after a refusal for a wrong key, and the status says which was used (L6; a Noise link was dropped because the one library considered cannot do what it was needed for). Same-network links need the **INTERNET permission**, the only network permission, guarded by a build test that keeps all other code off the network (L5). Nearby needs Bluetooth and Wi-Fi permissions, asked only when the person opens the Nearby screen, and runs only while that screen is open.

## Relay

A relay forwards encrypted bytes between two phones that cannot reach each other directly (for example, both on carrier-grade NAT). It stores nothing and cannot read content.

- Last resort (owner decision, 4 Nov 2026): the app tries a direct link first and uses our relay only when that fails. Nothing is switched on or off by an admin or by anyone else; the app decides.
- When even the relay cannot connect, the app says so and offers: meet up (Nearby) or try again later.
- All members use the same relay: our own at `https://relay.ownspace.in`. (A custom relay address, and the "Hosting your own relay" steps below, are not part of the app for now; kept for later.)
- Both phones connect outbound to the relay, which forwards by public id. Hole punching runs in parallel; if it works, traffic moves to the direct link and the relay drops out.
- The relay sees public ids, IP addresses, timing and data volume. It never sees content: a link through it is encrypted end to end between the two phones (TLS, with each phone proving its group key), so even our relay cannot read it or change it.
- Phones also find each other with Iroh (decision I1), which uses n0's public discovery service as well as our relay. **n0, a third party, learns each phone's member key for the group, its IP address and when it is online.** It never sees content. The privacy text shown to people (M10) must say this in plain words, next to what our relay learns.
- Limits (measured, docs/tasks/M7-internet-sync.md T7.9): a group of 20 fits the relay's room size exactly, and a big history crosses quickly, but the relay allows only 30 new connections a minute from one address, which phones sharing a mobile carrier's address can hit together. The owner may raise it (relay README).
- A phone is a poor relay: it needs a public address and Android sleeps background apps. Recommend a small VPS, a home Raspberry Pi, or the public relay. Phones act as the connection point only for same-room sync.

## Encryption

Standard libraries only (libsodium or Google Tink). No custom cryptography.

1. Signatures: every event is signed with the author's Ed25519 group key. Receivers verify the signature and the author's role before accepting.
2. Group encryption: event bodies are encrypted with a shared group key using XChaCha20-Poly1305 (or AES-256-GCM). Events are stored encrypted on disk. The master seed is held in the Android Keystore.
3. Transport: Noise or TLS 1.3 / QUIC between phones, authenticated by public keys. The ticket pins the host's public key, so a relay cannot impersonate a member.

Consequences and rules:
- The relay never gets the group key, so it cannot decrypt anything.
- Removing a member rotates to a new group key (epoch), sent to each remaining member encrypted to their public key. The removed member can still read history they already had.
- The ticket contains the group key: short expiry, single use, host approval.
- No forward secrecy for stored events in v1 (MLS or a ratchet considered too heavy).
- Metadata is visible to a relay or network observers.
- Optional app lock: the phone's own fingerprint or screen lock (no PIN of our own; owner decision, 4 Nov 2026). It locks when the phone locks and after 30 seconds away; if the phone has no screen lock, the app stays locked until one is set. The app covers its own screen whenever it is not in front, so the recent-apps preview shows nothing.
- Screenshots are allowed on every screen, the recovery phrase included (owner decision, 4 Nov 2026). A screenshot of the phrase can be copied by photo backups and other apps.

## Resources (estimates)

- Install about 20 to 40 MB. Data under 10 MB for years of heavy use (about 300 to 500 bytes per event).
- RAM about 80 to 150 MB idle, plus 10 to 30 MB while syncing.
- A sync sends a few KB. Receipt photos are out of scope; if added, store them separately from the log.

## Privacy

One text, in three places: the Privacy screen under About, the page `joinowntally.ownspace.in/privacy` and the draft of the store's data-safety answers (`docs/STORE_LISTING_DRAFT.md`), built from the sentences in `PrivacyFacts` (M10). It says: nothing but encrypted entries leaves the phone and only to the group's members; who runs the relay and that it sees public keys, a hash of the group id, addresses, timing and sizes but never content; that **n0's public discovery learns a public key the phone uses in each group, its internet address and when it is online** (decision I1); that Google's Nearby Connections is used only when the person starts it; what the permissions are for; no analytics, ads or tracking; backups never hold the keys. The operator's name and a contact address are the owner's to supply before release.

## Recovery

The recovery phrase restores your identity, not your data. Expense data is re-synced from other members.

First run:
1. The app creates the master seed and shows a 12-word recovery phrase (BIP39-style word list), derived from it.
2. The user must write it down. The app then asks for 3 of the words to confirm before continuing.
3. The phrase is never stored by the app after this screen, never sent anywhere. Screenshots are not blocked on that screen (owner decision, 4 Nov 2026), and the screen says not to keep the words in a screenshot or a notes app.

**Status (owner decision X1, 9 Nov 2026): restoring from the phrase is NOT built; it is milestone M11.** Until then the app, its How it works text, the privacy text and the store text say plainly that restoring is not available yet. What follows is the design for M11.

Restoring on a new phone:
1. Enter the 12 words. The master seed is re-derived, and with it every per-group keypair: a group's key comes from the seed and the group's id (CRYPTO.md section 2), so the phone needs only the id, which an invitation carries.
2. The phone does not yet know its groups or the group keys, because those are random and not derived from the seed.
3. A member of each group sends a fresh ticket. Because the restored public key is already a member, the group recognises it without an approval step, sends the current group key encrypted to that key, and the phone syncs the full history.
4. If the phrase is lost, the user rejoins as a new member and an admin links the new key to the old name.

Limits:
- If every member loses their phone, the group data is gone. The phrase does not back up expenses.
- Anyone with the phrase can impersonate the user in every group. Keep it offline, not in notes apps or screenshots.

## Profile and settling

- Settling is at group level, not person level. Balances are per group; groups are never netted against each other (the home screen may show totals only). The app computes each member's net balance in the group and suggests a short list of payments that clear everyone (at most members minus 1, using a simple matching method that is not always the absolute minimum), so a member may be asked to pay someone they never spent with. All phones compute the same plan from the same log.
- Payments are recorded one by one (no frozen "settlement round"). Each suggested payment is marked "I paid" and "Received" separately. The group is settled when all balances reach zero.
- The plan updates when an expense is added or deleted. Payments already marked stay in the log and are counted.

- A member's profile is a name and a phone number. `MemberJoined` carries them, and `ProfileUpdated` lets the member change either later. No payment id, email or other data is stored.
- **Names are unique inside a group** (owner decision, 4 Nov 2026): "Rohan" and "rohan" cannot both be members, "Rohan V" can; a removed member's name stays taken. The admin who approves a join sets the name (for example "Rohan V") and the joiner is told which name they were given. The same person can use different names in different groups. Phone numbers may be typed with `-`, `(`, `)` and `.` as well as digits, `+` and spaces.
- The phone number is shown to group members, so the join screen says so. Using a different number or name per group is allowed.
- The phone number is unverified. It identifies nobody on its own: identity is the key.
- Settle up shows who pays whom and how much. OwnTally refers to no payment service (owner decision, 4 Nov 2026): the person pays outside the app, however they like, and marks it there. The one other app it opens is the phone's dialer, from **Call** on a payment (owner decision P1, 9 Nov 2026; it opens the dialer with the number and does not place the call, and needs no permission); **Copy number** puts the number on the clipboard.
- The debtor taps "I paid" and the creditor taps "Received". Until then the settlement is shown as unconfirmed, but it counts toward the balances from the moment it is recorded (owner decision, 3 Nov 2026); a dispute stops it counting. `SettlementRecorded` stores the amount, from, to, and the confirmation state.
- Cash is always an option.
- **The amount can be edited** when marking a payment (P2): it opens filled with what the plan asks for. A smaller payment counts at once and the plan shows the rest; **a larger one is allowed** (P5, with a warning): the group then owes the payer the extra, and the plan has someone who still owes pay them.
- **A payment recorded twice** (the payer and the receiver each recorded it before they synced) is noticed (same two people, same amount): the receiver sees "This looks like the same payment recorded twice" and one tap stops the payer's entry counting, by a dispute with the reason *duplicate* (P3, P4). Nothing is deleted.
- Settle up lists, in order: the plan (Payments to make), payments waiting for confirmation (the receiver can Confirm received or Not received; the payer waits), payments not accepted (with the reason in words; the receiver may still confirm), and a footer with the number already confirmed.
- Reminders: the creditor can send a message through the share sheet. It says the person's name and the amount only, never the group's name or a number (P6): "Hi Rohan, OwnTally says you owe me ₹1,850. Pay however suits you, then mark it as paid in the app."

## Invite link page

Domain: `joinowntally.ownspace.in`. Static hosting only (for example GitHub Pages with a DNS CNAME); no server code, no database, no logging of the ticket, which stays after the `#` and is never sent to the host.

Needs:
1. DNS record for `joinowntally.ownspace.in` pointing at the static host, with HTTPS.
2. A static page at `/j` for people without the app: install button (Play Store), "copy code" fallback, and a note to paste the code in "Join with link or code".
3. `/.well-known/assetlinks.json` with the app's package name and signing certificate SHA-256, so Android opens the app directly on tap. This can only be filled in once the app exists and is signed.

**In the app (M9):** the manifest claims `https://joinowntally.ownspace.in/j` (the exact path) as an Android App Link. A link tapped before first-run setup or while the app is locked is kept sealed on the phone until the person is in, then opens the join form; one for a group the person is already in opens that group; one that is not an invite, or has expired, says so. Only that host and path open the app.

If this is not set up, QR codes and pasted codes still work; only the tap-to-open experience is lost. The relay at `relay.ownspace.in` is the only server we run.

## Hosting your own relay

Written for the Iroh relay (`iroh-relay`). If M0 chooses the WebSocket relay instead, the same VM, domain and TLS steps apply with that program in place of `iroh-relay`. Verify steps against https://docs.iroh.computer/iroh-services/relays/self-hosted before use; not yet tested by us.

Needs:
- A VM with a public IP. 1 GB RAM is plenty: the relay is stateless and traffic is a few KB per sync.
- A domain name pointing at the VM (for example `relay.example.com`), for TLS.
- Open ports: 80 and 443, plus the UDP port for QUIC address discovery (check the guide).

Steps:
1. Install `iroh-relay` from Iroh's releases or with `cargo install`.
2. Write `/etc/iroh-relay/config.toml` with the domain name and TLS settings. Automatic certificates via Let's Encrypt (ACME) are built in.
3. Run it under systemd so it starts at boot and restarts on crash. Allow binding to ports 80 and 443 without root (`AmbientCapabilities=CAP_NET_BIND_SERVICE`).
4. In the app, an admin enters `https://relay.example.com` as the group's relay.
5. Test with two phones on different mobile networks. The app should show whether the link is direct or via relay.

Operations:
- Cost: a small VPS, a few hundred rupees a month, or a free cloud tier.
- Run two relays on different servers and list both. Clients fail over automatically, so one outage does not stop sync.
- Firewall to only the needed ports, automatic security updates, and an uptime alert.

Default relay: `relay.ownspace.in`, run by us using the steps above, with `-connects-per-min 300 -max-per-room 40` (decision R3, M9: phones behind one carrier address share the connection limit, and a room must hold the largest group, 20, plus members who were removed or are waiting to join). The relay has no accounts and does not check who connects; it cannot read or change what phones say (TLS between them), so what a stranger who knows a room and a member's key can do is disturb, not read or forge. Consider adding a second relay on a different server for failover.

Public relay caveat: the n0 public relays are free but rate-limited and documented as for development and hobby use, with no uptime guarantee. Use them while building. Run our own before a wider release.

## Flows

### First run
1. Welcome: one line saying data stays on this phone, no account.
2. Enter a name and phone number (these are only defaults; each group asks again).
3. Recovery phrase: show 12 words, then confirm 3 of them (see Recovery). Cannot be skipped.
4. Optional app lock (the phone's fingerprint or screen lock), turned on later in Settings.
5. Empty home screen with two actions: "Create group" and "Join with link or code".
Permissions are asked only when first needed: camera for scanning, Bluetooth/Nearby for same-room sync.

**Location for same-room sync (owner decision, 3 Nov 2026).** Nearby Connections needs Android's Location permission, and on some phones the Location switch must also be on, because Android treats scanning for nearby Bluetooth and Wi-Fi devices as something that can reveal where a phone is. There is no other way to use Nearby, so the app asks for it, with these rules:
- It is asked only when the person first starts same-room sync, never at first run.
- Before Android's prompt, our own screen says why in plain words: "Android needs this to find phones near you. OwnTally never reads or stores your location."
- The app never reads the location. It requests the permission only so that Nearby works, and the manifest asks for nothing beyond what Nearby needs.
- If the permission is granted but the Location switch is off, the app detects it and says "Turn on Location in your phone's settings so Android can find nearby phones", with a button that opens that setting. (Without this, Nearby fails with a confusing error; seen on a Samsung phone in M0.)
- If the person refuses, same-room sync is unavailable, and the app says how else to join or sync (the other transports in "Sync transports", or the same-Wi-Fi fallback). Nothing else in the app depends on Location.
- The Play Store listing explains the permission in the same words.

### Create group
Name, optional icon colour, currency (default INR). The creator becomes owner and admin. The app then offers "Invite friends" which opens the ticket share sheet.

### Join by link (new member)
1. Friend taps the `https://` link (or pastes the text ticket, or scans the QR). If the app is not installed, the link page says to install it and the link is kept to continue after install.
2. If first run: do First run steps 1 to 4, then continue.
3. Screen shows group name, owner name and member count, and asks for the name and phone number to use in this group.
4. Tapping "Request to join" connects to the ticket's host. Status: "Waiting for approval".
5. When approved, the phone receives the group key and history and opens the group.
6. Failure states, each with a plain message:
   - ticket expired or already used: ask an admin for a new ticket
   - host offline or unreachable: "Ask Rohan to open OwnTally. Retry" and, if relay is on, say so
   - group full or invalid link: show the reason

### Host approval
- When a join request arrives, any admin online sees a banner and a "Join requests" entry in the group.
- Request card: name and phone number only. No fingerprint check.
- Actions: Approve or Decline. Decline sends a neutral "request declined".
- Approval creates a `MemberJoined` event signed by the admin. Requests expire after 8 hours.
- A restored member (same public key as an existing member) is recognised and skips approval.
- Tickets are single use and expire after 8 hours. When one expires, an admin sends a new ticket. Admins can revoke unused tickets.

### Remove member
1. The owner opens a member, chooses Remove, sees what happens: they stay in history, can no longer add entries, and the group key will change.
2. Removal rebalances the group. The removed member is taken out of the calculation, as if they had never been part of it:
   - Expenses they paid are excluded, so nobody owes them for those.
   - Their share is removed from expenses others paid. Equal splits are re-split among the remaining participants. For exact-amount splits, their share is divided equally among the remaining participants of that expense (owner decision, 3 Nov 2026), so the payer is repaid in full.
   - Settlements (payments) involving them are excluded too. A remaining member who already paid the removed member will owe that amount again in the recalculation, so the before-and-after screen lists these payments and the admin must confirm.
   - Their entries stay in the history, shown as "excluded (member removed)", and are not counted in balances.
   - No `BalanceWrittenOff` event is needed. Balances are derived by replaying the log with the `MemberRemoved` rule, so every phone gets the same result.
   Before confirming, the admin sees a before-and-after balance for every remaining member (for example "Rohan: owed ₹3,200, becomes owed ₹2,400"). They must confirm.
3. Confirm. Creates `MemberRemoved` (with the member's last seen entry counter) and starts a new group key (epoch) sent to remaining members. Remaining members who are offline get it on next sync.
4. The removed member's phone, when it next syncs, shows "You were removed from Goa Trip". They keep a read-only copy of history up to removal.
5. A member can also leave on their own. The same event is used, and a leave shows the same before-and-after balances as a removal. **After a leave the group disappears from the leaver's phone at once (owner decision, 5 Nov 2026); the phone keeps the encrypted data hidden only until the leave has reached another member, then deletes it, keys included.** The leaver does not keep a read-only copy.
6. The owner cannot be removed or leave until ownership is transferred.

### Transfer ownership and admins
- Owner opens Members, picks a person, chooses "Make owner". Sheet explains: you become an admin, they become owner. The target gets a request and must accept; until then nothing changes.
- On acceptance, `OwnershipTransferred` is recorded (signed by the old owner, accepted by the new one). Offline phones converge on the next sync.
- Make admin / remove admin: owner only. Admins can approve joins and invite; only the owner removes members and changes the key. Only the creator of an expense or the group owner can delete it. There is no relay setting.
- Admin takeover when the owner is unreachable is not in v1. If the owner loses their phone, co-admins can keep admitting people (they cannot remove anyone, change the key, or delete other people's entries), but removals and key changes wait for the owner; the owner can restore their identity with the recovery phrase.

### Delete expense
The creator or the group owner. Shows "This changes balances" when the entry is part of settled amounts. The entry stays in the list as struck through with who deleted it and when.

### Payment not received
- Shown to the creditor as "Not received" next to a payment the debtor marked as paid.
- The creditor picks a reason: nothing arrived, the amount is different, or they haven't checked yet. Undo is available.
- The debtor sees the answer on next sync. The payment returns to "Payments to make" and stays on the plan. Nothing is deleted.
- A dispute is only a note on the payment (a new event). It does not need both people to agree before it clears. Either person can change it later, and "Mark received" ends it.

### Can't reach a member
- Shown when a member cannot be reached directly (for example Isha on a mobile network that blocks direct links).
- Explains why and that no entries are lost: they stay on this phone and any member holding them can pass them on.
- Options: meet up and use Nearby, or try again later. (The relay is used automatically as the last resort, so there is no "turn on the relay" option; owner decision, 4 Nov 2026.)
- Turning on the relay applies to the whole group, and the screen states that the relay sees who connects but never the content.

### Sync states shown to the user
Up to date with N members, syncing, waiting for a friend to come online, not reachable directly, relay unreachable (retrying, changes saved on this phone). Each member row has a chip: Direct, Via relay, Nearby, Syncing, Not yet, Offline (the other phone is not there), Needs relay (direct failed and this phone cannot reach the relay). Offline and Needs relay rows open "Can't reach <name>": why, that the relay is used automatically (no button to turn it on, since the app decides), Try now, Meet and sync nearby, Try again later, and that entries are never lost.

### Background sync
Decision B1 (8 Nov 2026). While the app is closed, Android's WorkManager runs one pass about every 15 minutes (the shortest Android allows; it picks the real time and may delay it much longer when the phone is in Doze or low on battery): every group is tried once, then any leave still to be delivered. It runs only with a network connection, never while the app is in front (the app then syncs by itself), and never before the phone is set up. It stops itself after 8 minutes. Limits, said plainly: there is no mailbox, so the other phone must be online at about the same time for anything to move; the job helps less than it sounds. A pass that fails is not retried at once; the next run is soon enough, and the entries stay on the phone. The job adds three normal Android permissions (keep awake while it runs, know whether the network is up, run again after a restart); no question is asked of the person.

## Build order

1. Local app: groups, expenses, balances, settle up, event log, encryption.
2. Same-room sync (Nearby, or same-Wi-Fi fallback).
3. Pairing: ticket, share link, QR.
4. Direct internet sync, using the public relay for testing.
5. Our own relay, before wider release.

## Open questions

- None at the moment.
