# OwnTally cryptography: keys, signatures, encryption, tickets

Status: **APPROVED by the owner on 3 Nov 2026** (task T2.1 in [tasks/M2-identity-crypto.md](tasks/M2-identity-crypto.md)). Any change from here needs the owner's approval. Behaviour comes from [DESIGN.md](DESIGN.md) ("Encryption", "Recovery", "Pairing"); bytes come from [WIRE_FORMAT.md](WIRE_FORMAT.md).

Rules: no cryptography is written by the project. Every primitive below comes from Google Tink (decided 3 Nov 2026) or from the JDK. The project only chooses which primitive is used where, and in what order. **Verified (T2.2, 3 Nov 2026):** the pinned Tink 1.24.0 exposes every call named below, and reproduces or opens values made by an independent implementation (details in the task list). If a later Tink release drops one, the builder stops and asks the owner.

## 1. Recovery phrase and master seed

- The app makes 16 random bytes (128 bits) from the platform's secure random source and encodes them as 12 words with **standard BIP39**: the published English list of 2048 words, 4 checksum bits taken from SHA-256 of the entropy. A mistyped word is detected by the checksum.
- The master seed is the standard BIP39 seed: PBKDF2-HMAC-SHA512 over the NFKD-normalised phrase, salt `"mnemonic"` (no passphrase), 2048 rounds, 64 bytes. This is a JDK call.
- Tests use the published BIP39 test vectors (phrase to seed) and the reference word list's SHA-256, so the list in the repository is checked byte for byte.
- Never logged, never written to a file by the core. The Android Keystore wrapping is M3.

## 2. Per-group keys

Each group uses a numbered slot `n` (u32, starting at 0). From the master seed, with HKDF-SHA256 (Tink), salt `"owntally/v1"`:

| Key | HKDF info string | Use |
|---|---|---|
| signing seed (32 bytes) | `"group/<n>/sign"` | Ed25519 key pair; the public key is the member's `PublicKey` in that group |
| encryption private key (32 bytes) | `"group/<n>/encrypt"` | X25519 key pair; the public key travels as `encryptionKey` in `GroupCreated` and `MemberJoined` |

`<n>` is written as decimal ASCII. Different groups give unrelated keys, so members of different groups cannot link a person (DESIGN.md). The slot used is recorded by the phone; restoring tries slots in order until the group's `PublicKey` matches (DESIGN.md "Recovery").

Why a separate encryption key: Tink cannot convert an Ed25519 signing key into an X25519 key, and using one key for two jobs is worse practice anyway.

## 3. Group key and epochs

- A group key is 32 random bytes from the secure random source. The first, made when the group is created, belongs to **epoch 1**. Each epoch has its own key.
- A phone keeps every epoch key it has been given, so it can read old events.
- The group key is never derived from the seed (DESIGN.md: group keys are random).

## 4. Signing

An author signs with Ed25519 (Tink) over:

```
"owntally/v1/event" | envelope bytes up to but not including the signature
```

The fixed prefix stops a signature made for one purpose being replayed as another. The envelope already contains `groupId` and `epoch`, so a signature cannot be moved to another group or epoch. The signature is checked before decrypting; a wrong signature, a malformed envelope, or an `author` that the receiver's state says is not a member is rejected. (Whether the author is a member is a replay rule from M1; the crypto layer only checks the signature against the key it is given.)

## 5. Encrypting an event

XChaCha20-Poly1305 (Tink) with the epoch's group key, a fresh random 24-byte nonce for every event (Tink's `XChaCha20Poly1305` makes the nonce itself and returns it in front of the ciphertext; the envelope's `nonce` field is those first 24 bytes, split out), plaintext = the payload (WIRE_FORMAT.md section 2), associated data =

```
"owntally/v1/payload" | groupId | epoch | author | counter
```

so a ciphertext cannot be moved to another event slot. A phone that has no key for the envelope's `epoch` keeps the envelope undecrypted and asks for the key (it arrives with the next sync).

Order: encrypt first, then sign the whole envelope. A phone can reject forged events cheaply, before decrypting.

## 6. Removing a member: a new epoch

`MemberRemoved` (WIRE_FORMAT.md tag 14) now carries `newEpoch` and, for every remaining member, the new group key **sealed** to that member's `encryptionKey` with HPKE (RFC 9180, via Tink: X25519, HKDF-SHA256, ChaCha20-Poly1305). Rules:

- `newEpoch` must be exactly the current epoch plus 1, else the event is `INVALID`.
- The HPKE context ("info") for every sealed key is the ASCII text `owntally/v1/key-delivery`, then the **sender's public key (32 bytes; the author of the event that carries the sealed keys)**, then the group id (16 bytes), then the new epoch as 4 bytes big-endian. This ties a sealed key to its sender, group and epoch (the sender was added on 4 Nov 2026, owner decision, clarification C1 option B). (Added while building T2.9; the first draft did not name the context. For the owner to confirm.)
- A phone takes a new epoch's key only from the event that replay accepted as the start of that epoch (`GroupState.epochStarts`), never from an ignored event or one that is not yet in the log. Otherwise a plain member could hand everyone a key of their choosing.
- The event itself is encrypted under the current (old) epoch key, like any event. It carries the sealed keys in the clear inside that payload, which is fine: only the recipients can open them.
- Events written from then on use the new epoch. The removed member holds the old key only, so cannot read them.
- A remaining member who has not yet synced the removal keeps writing under the old epoch until they see it; those events stay readable by the removed member. This is inherent in the design (DESIGN.md: no forward secrecy in v1) and is accepted.
- **Leaving.** A member who removes themselves (author and `member` are the same) cannot safely hand out a new key, and an admin may be offline. So a leave does not rotate: `newEpoch` must equal the current epoch and the map must be empty. The app then tells the admins "Dev left, rotate the key?" (owner decision, 3 Nov 2026).
- **`KeyRotated`** (WIRE_FORMAT.md tag 15) is that rotation. Only the owner or an admin may write it. `newEpoch` must be the current epoch plus 1, and the map must hold a sealed key for **exactly** the current members, the author included. Otherwise it is `INVALID`.
- For `MemberRemoved` by someone else the same two rules apply to the remaining members, so the removal and the new key are one atomic event.

## 7. Tickets

A ticket is a secret: it carries the group key. Bytes (before encoding as text):

```
version u8 (1) | ticketId bytes(16) | hostId key | groupId bytes(16) | epoch u32 | groupKey bytes(32)
| expires u64 (seconds since 1970-01-01 UTC) | hintLength u8 | hint (UTF-8, at most 100 bytes) | crc u32
```

- Shared as text with the URL-safe base64 alphabet, no padding. With no hint it is 110 bytes plus the 4-byte check, 114 bytes, which is 152 characters (DESIGN.md says about 150).
- `hostId` is the host's transport public key. The joiner connects only to that key (DESIGN.md: the ticket pins the host's key). `hint` is optional connection help, for example a relay address; empty means the default relay.
- `crc` is a plain CRC-32 of everything before it. It only catches typing and copy mistakes; it is **not** security. The ticket is a bearer secret.
- Expiry: 8 hours after creation, checked by the host and by the joiner. (The host is exact; the joiner allows 60 seconds of clock difference.) Single use: the host remembers each `ticketId` it has accepted and refuses a repeat. The registry lives on the host phone, so a ticket works only while the host is reachable.
- Expired, reused, malformed or wrongly checked tickets are rejected without revealing which.

## 8. Never in logs

Key and phrase types print as `[redacted]` in `toString`. Their bytes are copied out only by explicit calls. A test searches the output of every public type's `toString` for the secret bytes in hex and Base64.

## 9. Changes this draft makes to approved files

For the owner to approve together with this file. Nothing in `docs/PROTOCOL.md` or the code is changed until you do.

1. `GroupCreated` and `MemberJoined` gain `encryptionKey`.
2. `MemberRemoved` gains `newEpoch` and the sealed keys (and gets a rule: `newEpoch` is the current epoch plus 1).
3. `Event.version` becomes the envelope version.
4. `EventBody.Unknown` carries the numeric tag and the raw content instead of a name.
5. A new event `KeyRotated(newEpoch, sealed keys)`, and the leave rule for `MemberRemoved` (section 6).
6. DESIGN.md says "Noise or TLS 1.3 / QUIC" for transport. M2 does not touch transport security.

## 10. Decided and still open

Decided by the owner on 3 Nov 2026:
- A leave does not rotate the key; an admin rotates with `KeyRotated` (section 6).
- `after` and `time` are hidden inside the encrypted payload. The author, counter, group and epoch stay readable. Who can see them: the operator of the WebSocket relay (TLS ends at our server, so it forwards the envelopes), and anyone holding a stored copy such as a database file or backup. They see who wrote how many events, not what is in them or who has seen whose events. People on the network (Wi-Fi, ISP, carrier) see only the encrypted connection, with IP addresses, timing and sizes. Iroh's relay forwards end-to-end encrypted QUIC packets and sees none of the envelope.

Still to confirm: epochs start at 1, and tickets last 8 hours as in DESIGN.md.

## 11. Clarifications found while implementing M2 (3 Nov 2026, for the owner to confirm)

None of these changes a decision the owner made. Each has a test.

1. **HPKE context.** The context for a sealed key is `owntally/v1/key-delivery`, the sender's public key, the group id and the new epoch (section 6), so a sealed key cannot be used for another epoch or group, nor copied into another member's event. **Changed and confirmed by the owner, 4 Nov 2026 (option B: the sender is part of the context).** The independent test vectors (`crypto_vectors.py`) were regenerated with it.
2. **A key comes only from the event that started the epoch.** **Confirmed by the owner, 4 Nov 2026, with option B: only the owner changes the key (removals and rotations), so two events can never start the same epoch at once.** The state records it (`epochStarts`); an ignored event, or one not yet in the log, delivers nothing (section 6).
3. **Nonce.** Tink's `XChaCha20Poly1305` makes the random nonce itself and returns it in front of the ciphertext. The envelope's `nonce` field is those 24 bytes, split out (section 5). **Confirmed by the owner, 4 Nov 2026 (option A: as written).**
4. **Only issued tickets are accepted.** Section 7 says the host remembers the tickets it accepted. The host also remembers the ones it issued, and accepts only a ticket identical to one it issued, once, before it expires. Otherwise a well-formed ticket made up by anyone would be taken as new. The records live in memory until the storage milestone (M3). **Confirmed by the owner, 4 Nov 2026 (option B):** and the host's record of issued tickets **must survive an app restart**, including which tickets were already used (otherwise a used ticket could work again after a restart). It is saved on the phone in M6 and must not keep a second plain copy of the group key (seal it, or keep only a hash of each ticket with its id and expiry). M6 is done only when a test shows: issue, restart, accept once (works), accept again (refused), and an expired ticket refused after a restart.
5. **Ticket text is exact.** Decoding rejects padding, spaces, characters outside the URL-safe alphabet and base64 with spare bits set, so a ticket has one text. A link is accepted by taking the part after `#`. **Changed and confirmed by the owner, 4 Nov 2026 (option B):** when a person pastes a ticket or link, spaces, line breaks and invisible characters anywhere in it are ignored first (a chat app may wrap or space a long text); the decoder itself is unchanged and still accepts exactly one text per ticket (no padding, no other characters, no spare bits).
6. **Expiry.** The expiry time is the first second at which the ticket no longer works. **Confirmed by the owner, 4 Nov 2026, with option B:** the host checks exactly; the joiner's own check forgives a clock up to 60 seconds behind (so a slightly wrong phone clock does not refuse a ticket at the edge), and the host's check still decides.
7. **Verifying.** `verify` answers true or false and never throws. `verifyFrom` also needs the author to be a member the caller lists; whether the member may do the thing is a replay rule.
8. **Not done in M2.** Recording which slot a group used, and trying slots in order when restoring (section 2), belong to storage (M3) and joining (M6). Android uses the `tink-android` artifact; the APK size it adds is measured in M3.
9. **Pinned library.** Google Tink 1.24.0 (released 16 Oct 2026).
