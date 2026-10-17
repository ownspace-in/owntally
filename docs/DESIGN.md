# OwnTally: design approach

Android app to split expenses between friends with no server of our own.
UI mockups: see the design/ folder (screens to be exported there).

## Decisions

| Question | Decision |
|---|---|
| Both phones online at the same moment to sync? | Yes, acceptable. No mailbox. |
| Third party (relay) allowed? | Off by default. Admin can enable a relay per group (see Relay). |
| Google Play Services? | Optional. Use Nearby Connections when available, fall back to same-Wi-Fi discovery. |
| Encryption | Signed events, group-key encryption, encrypted transport (see Encryption). |
| Minimum Android | 8 (API 26). |
| Backup / recovery | One-time recovery phrase at first run (see Recovery). No backup files. |
| Who can add expenses? | Anyone in the group. |
| Edit expenses? | Not allowed. To fix a mistake, delete and re-add. |
| Delete expenses? | Creator, or an admin/owner. Entry stays in history, struck through, excluded from balances. Warn if it changes settled amounts. |
| Remove members? | Owner or any admin. Removal records which of the member's entries were seen; newer ones are ignored. |
| Transfer ownership? | Yes: owner signs `OwnershipTransferred`, new owner accepts. Owner can name co-admins, any of whom can remove members or transfer ownership, so a lost owner phone does not strand the group. |
| Sign-up / identity | No global account. A master seed is created on first run. Each group gets its own keypair derived from the seed, so members of different groups cannot link you. Only a name and a phone number are collected, set per group on join. The phone adds up cross-group totals locally. One seed backup restores every group. |
| Join approval | Admin approves or declines. No fingerprint check. Tickets: single use, 8 hours; an admin sends a new one if expired. |
| Removal with unsettled balance | Allowed. Balances are rebalanced as if the removed member were never in the group; their entries and payments involving them stay in history but are excluded. Before-and-after shown to the admin, who must confirm. |
| Recovery phrase step | Required at first run, cannot be skipped. |
| Pairing method | Ticket shared via tappable link, copy-paste text, or QR (see below). |
| Profile data | Name and phone number only, nothing else. Set per group, visible to members of that group. The phone number is not verified (no OTP, no server): it is a label. |
| Settlement scope | Group level, payment by payment, no frozen round. See Profile and settling. |
| Currency / settlement | INR. The app never moves or checks money. Settling is done outside the app, then recorded. |

## Data model

- Each phone keeps an append-only, signed event log in a local database.
- Events: `ExpenseAdded`, `ExpenseDeleted`, `SettlementRecorded`, `PaymentDisputed`, `MemberJoined`, `ProfileUpdated`, `MemberRemoved`, `OwnershipTransferred`.
- An event is identified by (device id, counter). Deletes are new events, so phones never conflict.
- Sync: swap a version summary (device id to highest counter), then send only the missing events. Merge is a union.
- Balances are derived by replaying the log, so all phones agree.
- Each phone has a keypair. Each group has a shared key. Everything sent is encrypted with the group key.

## Pairing: the ticket

A ticket is about 150 characters: host id, connection hint, group id, group key.

Ways to share it, in order of preference:
1. Tappable `https://` link with the ticket after `#` (never sent to the web host). Needs a small static page on a domain we own to hand off to the app. WhatsApp only linkifies `https://`, not `owntally://`.
2. Plain-text ticket through the share sheet, pasted into "Join group".
3. QR code, for people in the same room. QR holds under about 2.9 KB, so it carries the ticket only, never data.

Safety:
- The ticket contains the group key, so treat it as a secret. Prefer end-to-end encrypted chats. SMS is not encrypted.
- Tickets expire after a short time or one use.
- The host approves each new member ("Rohan wants to join, allow?").
- Joining still needs a live connection to a member to fetch history.

## Sync transports (ladder)

1. Same room: Nearby Connections (Bluetooth / Wi-Fi / Wi-Fi Direct), no internet needed.
2. Same network or IPv6: direct connection.
3. Different networks or cities: direct internet connection via hole punching. Needs STUN for discovery, which is a third party. Expect roughly 60 to 80 percent of phone pairs to connect. Carrier-grade NAT blocks the rest.
4. Fallback: if unreachable, tell the user to meet up or enable a relay for that group.

## Relay

A relay forwards encrypted bytes between two phones that cannot reach each other directly (for example, both on carrier-grade NAT). It stores nothing and cannot read content.

- Off by default. Nothing goes through a relay unless an admin enables it for the group.
- When a direct link fails, the app says so and offers: enable relay for this group, or meet up.
- Setting is per group and set by an admin, so all members use the same relay.
- Server choice: the default is our own relay at `https://relay.ownspace.in`; the admin can enter a different relay address. Relay is still off until an admin enables it.
- Both phones connect outbound to the relay, which forwards by public id. Hole punching runs in parallel; if it works, traffic moves to the direct link and the relay drops out.
- The relay sees public ids, IP addresses, timing and data volume. It never sees content.
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
- Optional app lock (fingerprint or PIN).

## Resources (estimates)

- Install about 20 to 40 MB. Data under 10 MB for years of heavy use (about 300 to 500 bytes per event).
- RAM about 80 to 150 MB idle, plus 10 to 30 MB while syncing.
- A sync sends a few KB. Receipt photos are out of scope; if added, store them separately from the log.

## Recovery

The recovery phrase restores your identity, not your data. Expense data is re-synced from other members.

First run:
1. The app creates the master seed and shows a 12-word recovery phrase (BIP39-style word list), derived from it.
2. The user must write it down. The app then asks for 3 of the words to confirm before continuing.
3. The phrase is never stored by the app after this screen, never sent anywhere, and screenshots are blocked on that screen.

Restoring on a new phone:
1. Enter the 12 words. The master seed and all per-group keypairs are re-derived (group keys come from numbered slots of the seed; `MemberJoined` records which slot a group used).
2. The phone does not yet know its groups or the group keys, because those are random and not derived from the seed.
3. A member of each group sends a fresh ticket. Because the restored public key is already a member, the group recognises it without an approval step, sends the current group key encrypted to that key, and the phone syncs the full history.
4. If the phrase is lost, the user rejoins as a new member and an admin links the new key to the old name.

Limits:
- If every member loses their phone, the group data is gone. The phrase does not back up expenses.
- Anyone with the phrase can impersonate the user in every group. Keep it offline, not in notes apps or screenshots.

## Profile and settling

- Settling is at group level, not person level. Balances are per group; groups are never netted against each other (the home screen may show totals only). The app computes each member's net balance in the group and suggests the fewest payments that clear everyone, so a member may be asked to pay someone they never spent with. All phones compute the same plan from the same log.
- Payments are recorded one by one (no frozen "settlement round"). Each suggested payment is marked "I paid" and "Received" separately. The group is settled when all balances reach zero.
- The plan updates when an expense is added or deleted. Payments already marked stay in the log and are counted.

- A member's profile is a name and a phone number. `MemberJoined` carries them, and `ProfileUpdated` lets the member change either later. No UPI id, email or other data is stored.
- The phone number is shown to group members, so the join screen says so. Using a different number or name per group is allowed.
- The phone number is unverified. It identifies nobody on its own: identity is the key.
- Settle up shows who pays whom and how much. A UPI payment link needs a UPI id, which we do not store, so the app cannot pre-fill a payment. Instead the debtor taps "Pay Rohan ₹1,850", which shows the phone number with copy and call buttons and opens their UPI app chooser, and pays to that number in their own UPI app.
- The debtor taps "I paid" and the creditor taps "Received". Until then the settlement is shown as unconfirmed. `SettlementRecorded` stores the amount, from, to, and the confirmation state.
- Cash is always an option.
- Reminders: the creditor can send a message through the share sheet.

## Hosting your own relay

Assumes the Iroh relay (`iroh-relay`). Verify steps against https://docs.iroh.computer/iroh-services/relays/self-hosted before use; not yet tested by us.

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

Default relay: `relay.ownspace.in`, run by us using the steps above. Consider adding a second relay on a different server for failover.

Public relay caveat: the n0 public relays are free but rate-limited and documented as for development and hobby use, with no uptime guarantee. Use them while building. Run our own before a wider release.

## Flows

### First run
1. Welcome: one line saying data stays on this phone, no account.
2. Enter a name and phone number (these are only defaults; each group asks again).
3. Recovery phrase: show 12 words, then confirm 3 of them (see Recovery). Cannot be skipped.
4. Optional app lock (fingerprint or PIN).
5. Empty home screen with two actions: "Create group" and "Join with link or code".
Permissions are asked only when first needed: camera for scanning, Bluetooth/Nearby for same-room sync.

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
1. Admin opens a member, chooses Remove, sees what happens: they stay in history, can no longer add entries, and the group key will change.
2. Removal rebalances the group. The removed member is taken out of the calculation, as if they had never been part of it:
   - Expenses they paid are excluded, so nobody owes them for those.
   - Their share is removed from expenses others paid. Equal splits are re-split among the remaining participants. For exact-amount splits, their share is dropped and others' shares stay as entered.
   - Settlements (payments) involving them are excluded too. A remaining member who already paid the removed member will owe that amount again in the recalculation, so the before-and-after screen lists these payments and the admin must confirm.
   - Their entries stay in the history, shown as "excluded (member removed)", and are not counted in balances.
   - No `BalanceWrittenOff` event is needed. Balances are derived by replaying the log with the `MemberRemoved` rule, so every phone gets the same result.
   Before confirming, the admin sees a before-and-after balance for every remaining member (for example "Rohan: owed ₹3,200, becomes owed ₹2,400"). They must confirm.
3. Confirm. Creates `MemberRemoved` (with the member's last seen entry counter) and starts a new group key (epoch) sent to remaining members. Remaining members who are offline get it on next sync.
4. The removed member's phone, when it next syncs, shows "You were removed from Goa Trip". They keep a read-only copy of history up to removal.
5. A member can also leave on their own. The same event is used, and a leave with an unsettled balance shows a warning.
6. The owner cannot be removed or leave until ownership is transferred.

### Transfer ownership and admins
- Owner opens Members, picks a person, chooses "Make owner". Sheet explains: you become an admin, they become owner. The target gets a request and must accept; until then nothing changes.
- On acceptance, `OwnershipTransferred` is recorded (signed by the old owner, accepted by the new one). Offline phones converge on the next sync.
- Make admin / remove admin: owner only. Admins can approve joins, remove members, delete any entry and edit the relay setting.
- If the owner is unreachable, any admin can transfer ownership to another admin after a confirmation that explains the old owner loses ownership. This avoids a stranded group.

### Delete expense
Creator or an admin. Shows "This changes balances" when the entry is part of settled amounts. The entry stays in the list as struck through with who deleted it and when.

### Payment not received
- Shown to the creditor as "Not received" next to a payment the debtor marked as paid.
- The creditor picks a reason: nothing arrived, the amount is different, or they haven't checked yet. Undo is available.
- The debtor sees the answer on next sync. The payment returns to "Payments to make" and stays on the plan. Nothing is deleted.
- A dispute is only a note on the payment (a new event). It does not need both people to agree before it clears. Either person can change it later, and "Mark received" ends it.

### Can't reach a member
- Shown when a member cannot be reached directly (for example Isha on a mobile network that blocks direct links).
- Explains why and that no entries are lost: they stay on this phone and any member holding them can pass them on.
- Options: turn on the relay and sync (owner or admin only; members are told to ask an admin), meet up and use Nearby, or try again later.
- Turning on the relay applies to the whole group, and the screen states that the relay sees who connects but never the content.

### Sync states shown to the user
Up to date with N members, syncing, waiting for a friend to come online, not reachable directly (offer relay or meet up), relay unreachable (retrying, changes saved on this phone).

## Build order

1. Local app: groups, expenses, balances, settle up, event log, encryption.
2. Same-room sync (Nearby, or same-Wi-Fi fallback).
3. Pairing: ticket, share link, QR.
4. Direct internet sync, using the public relay for testing.
5. Our own relay, before wider release.

## Open questions

- None at the moment.
