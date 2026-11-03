# M2 tasks: identity and crypto

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M2. Rules: [DESIGN.md](../DESIGN.md), [PROTOCOL.md](../PROTOCOL.md) (approved), and the two files this milestone adds: [WIRE_FORMAT.md](../WIRE_FORMAT.md) and [CRYPTO.md](../CRYPTO.md). Tests first. **No cryptography is written by us**: only Google Tink and the JDK.

Where things live:
- `app/core-model` stays free of libraries (its `PurityTest` enforces this). It gets the new event fields and the wire codec, which is plain byte handling.
- `app/crypto` is a new JVM module (no Android) that depends on `core-model` and Google Tink. It holds the phrase, key derivation, signing, encryption, sealing and tickets.

Each task ends with "Done when", which must be checkable.

## Progress

| Task | Status |
|---|---|
| T2.1 specs | Approved by the owner on 3 Nov 2026. |
| T2.2 library check | Done, see below. |
| T2.3 event model changes | Done: encryption keys, `MemberRemoved` with epoch and sealed keys, `KeyRotated`, numeric `Unknown` (tag and content kept), `epoch` in the state. 127 `core-model` tests. |
| T2.4 wire codec | Done: `EventCodec` and `Envelope` in `core-model`, 21 valid, 17 invalid payload and 11 envelope vectors from an independent encoder, strictness and "changed bytes are rejected or rewrite identically" property tests. 143 `core-model` tests. |
| T2.5 phrase and master seed | Done: `Bip39`, `RecoveryPhrase`, `MasterSeed` in `app/crypto`. The word list is the reference list, checked by SHA-256 and compared byte for byte with a second source; the 8 published 12-word BIP39 vectors pass (entropy to words, words to entropy, seed). 24 `crypto` tests. |
| T2.6 per-group keys | Done: `KeyDerivation.groupIdentity` and `GroupIdentity` (redacted `toString`). Keys for 5 slots (0, 1, 2, 255, 2^32 - 1) match an independent Python implementation (`app/crypto/src/test/tools/crypto_vectors.py`). |
| T2.7 signing and verification | Done: `EnvelopeSigning.sign`, `verify`, `verifyFrom`. Signatures match the independent implementation byte for byte; every changed byte, a moved signature (group, epoch, counter, nonce, ciphertext, version), another signer, the encryption key standing in for the signing key, a missing or different prefix and a non-member author are all rejected. 46 `crypto` tests. |
| T2.8 event encryption | Done: `EventSealing.seal` and `open`, `GroupKey`, `GroupKeyring`, `OpenResult` (opened, rejected with a reason, or "need the key for epoch N"). An event sealed by an independent implementation (HChaCha20 checked against the published vector) opens. A ciphertext re-signed into another epoch, counter, author or group fails to decrypt; a changed nonce or ciphertext fails; the same event sealed twice differs; a wrong key fails; any flipped bit in a stored envelope is refused. 62 `crypto` tests. |
| T2.9 key rotation | Done: `KeyRotation.seal`, `open`, `receive`, `recipients` (HPKE through Tink). `GroupState.epochStarts` (core) records which accepted event started each epoch, and a key is only taken from that event. Keys sealed by an independent RFC 9180 implementation open; every remaining member gets the same key; the removed member reads old-epoch events but gets `NeedKey` for new ones and cannot open another member's sealed key; a sealed key is bound to its group, epoch and recipient; a forged rotation by a plain member and an event that is not in the log deliver nothing. 73 `crypto` and 144 `core-model` tests. |
| T2.10 tickets | Done: `Ticket` (encode, strict decode, `fromShared` for links), `TicketRegistry` (issue, accept once, expiry, host check), `Clock`. Texts match an independent implementation; no hint is 152 characters; 15 invalid texts are rejected; every changed character and every shorter prefix is rejected; an expired, repeated, unissued or other-host ticket is refused; 200 simultaneous tries give exactly one win. 91 `crypto` tests. |
| T2.11 no secrets in logs | Done: every secret-bearing type prints as `[redacted]`; a test searches every printed form for each secret in hex, Base64, byte-list and word form; a scan fails if the core or crypto main code prints or logs. |
| T2.12 hand back | Done: see the pull request. `PROTOCOL.md`, `WIRE_FORMAT.md` and `CRYPTO.md` updated; clarifications for the owner at the end of `CRYPTO.md` (section 11). 144 `core-model` and 103 `crypto` tests, no compiler warnings, CI runs both. There is still no `lint` task in the project. |

---

## T2.1 Specs (owner approves)

Write `docs/WIRE_FORMAT.md` and `docs/CRYPTO.md`, list what they change in approved files, and ask the open questions.

Done when: the owner has approved both files.

## T2.2 Library check (throwaway test)

Do: create `app/crypto` with the pinned Tink Java release. In a test, confirm each call CRYPTO.md names exists and works: HKDF-SHA256, Ed25519 key pair from a 32-byte seed, XChaCha20-Poly1305 with a caller-supplied nonce and associated data, X25519 key pair from 32 bytes, HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) seal and open, CRC-32 and PBKDF2-HMAC-SHA512 from the JDK. Check against published test vectors where they exist (RFC 8032, RFC 7748, RFC 9180).

Done when: each call is shown working, or the builder has stopped and told the owner which one is missing. Record the Tink version and the app size added.

**Result (3 Nov 2026):** Tink 1.24.0 (released 16 Oct 2026), module `app/crypto`, test `TinkCapabilitiesTest` (9 tests, all pass). Every expected value comes from an independent implementation (Python `cryptography` 49.0.0 and `hashlib`, plus hand-written RFC 9180 HPKE and XChaCha20-Poly1305 sealing), not from Tink: HKDF-SHA256 key derivation, Ed25519 key pair and signature from a seed, X25519 public key from 32 bytes, opening an XChaCha20-Poly1305 message, opening an HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) message, plus JDK PBKDF2-HMAC-SHA512 and CRC-32. Differences from the spec text: (1) Tink's public `XChaCha20Poly1305` cannot take a caller-supplied nonce; it makes a random one and puts it in front of the ciphertext, so the envelope's `nonce` is those 24 bytes split out (no change to the format, and the nonce is random as required); (2) HPKE sealing with a fixed ephemeral key is not possible through the public API, so sealing is checked by round trip and opening is checked against the independent RFC 9180 value. For the JVM modules and tests we use `tink` (2.9 MB jar, plus protobuf-java and gson); the Android app should use `tink-android` (checked: version 1.24.0 exists, jar). The size added to the APK is measured in M3, when it is first included.

## T2.3 Event model changes

Do: update `core-model` as listed in CRYPTO.md section 9: `encryptionKey` on `GroupCreated` and `MemberJoined`, `newEpoch` and sealed keys on `MemberRemoved` with the rule that `newEpoch` is the current epoch plus 1, numeric tag on `Unknown`, the new `KeyRotated` event, and the leave rule. Update replay, the M1 fixtures and their tests.

Done when: all M1 tests pass again, with new tests for the epoch rules (removal by someone else, a leave that does not rotate, `KeyRotated` with a missing member).

## T2.4 Wire codec (in `core-model`)

Do: write the test vectors by hand first (`wire-vectors.json`), then `encode` and `decode` for the payload and the envelope as in WIRE_FORMAT.md.

Done when:
- Every vector encodes to exactly the hex given.
- `decode(encode(x)) == x` and `encode(decode(b)) == b` for random events (property test).
- Each strictness rule in WIRE_FORMAT.md section 4 has a test that rejects a bad input.
- An unknown body tag decodes to `Unknown` and is skipped by length.

## T2.5 Phrase and master seed

Do: BIP39 word list resource (checked by SHA-256 against the reference), phrase from 16 bytes, phrase to seed, phrase check (unknown word, wrong checksum, wrong word count).

Done when: the published BIP39 vectors pass, and a changed word is rejected.

## T2.6 Per-group keys

Do: derive the signing and encryption key pairs for slot `n` as in CRYPTO.md section 2.

Done when: the same phrase gives the same keys (fixed test vectors, written down once), different slots give different keys, and a key cannot be used for the other purpose.

## T2.7 Signing and verification

Do: sign and verify the envelope as in CRYPTO.md section 4.

Done when: a tampered byte anywhere in the envelope, a signature by another key, a signature from another group's `groupId`, and a wrong prefix are all rejected.

## T2.8 Event encryption

Do: encrypt and decrypt payloads as in CRYPTO.md section 5.

Done when: round trip works; a changed ciphertext, nonce, epoch, author, counter or `groupId` fails to decrypt; the same event encrypted twice gives different bytes; a wrong key fails.

## T2.9 Key rotation

Do: seal a new group key to each remaining member's `encryptionKey` with HPKE and open it; apply `MemberRemoved` with `newEpoch`.

Done when: every remaining member can open their sealed key; the removed member can decrypt old-epoch events but not new-epoch ones; a sealed key opened with another member's private key fails.

## T2.10 Tickets

Do: encode and decode tickets as in CRYPTO.md section 7, and a host-side registry that checks expiry (with a clock that tests can control) and single use.

Done when: a fresh ticket is accepted once; an expired one, a repeated one, one with a changed byte (CRC), a truncated one and one for another host are rejected; the length with no hint is 152 characters.

## T2.11 No secrets in logs

Do: redacted `toString` on every key, seed, phrase and ticket type. A test checks them all.

Done when: no output of any public type contains a secret in hex, Base64 or as words.

## T2.12 Hand back

Checklist: `./gradlew :app:core-model:test :app:crypto:test` passes and CI runs `:app:crypto:test`; `core-model` still imports no libraries; `docs/WIRE_FORMAT.md` and `docs/CRYPTO.md` match the code; `docs/PROTOCOL.md` is updated for the changes in CRYPTO.md section 9; any rule that was interpreted is listed in the pull request.
