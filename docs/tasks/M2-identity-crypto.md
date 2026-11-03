# M2 tasks: identity and crypto

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M2. Rules: [DESIGN.md](../DESIGN.md), [PROTOCOL.md](../PROTOCOL.md) (approved), and the two files this milestone adds: [WIRE_FORMAT.md](../WIRE_FORMAT.md) and [CRYPTO.md](../CRYPTO.md). Tests first. **No cryptography is written by us**: only Google Tink and the JDK.

Where things live:
- `app/core-model` stays free of libraries (its `PurityTest` enforces this). It gets the new event fields and the wire codec, which is plain byte handling.
- `app/crypto` is a new JVM module (no Android) that depends on `core-model` and Google Tink. It holds the phrase, key derivation, signing, encryption, sealing and tickets.

Each task ends with "Done when", which must be checkable.

## Progress

| Task | Status |
|---|---|
| T2.1 specs | Drafted, waiting for the owner's approval of `WIRE_FORMAT.md` and `CRYPTO.md`. |
| T2.2 to T2.12 | Not started: nothing may be coded until T2.1 is approved. |

---

## T2.1 Specs (owner approves)

Write `docs/WIRE_FORMAT.md` and `docs/CRYPTO.md`, list what they change in approved files, and ask the open questions.

Done when: the owner has approved both files.

## T2.2 Library check (throwaway test)

Do: create `app/crypto` with the pinned Tink Java release. In a test, confirm each call CRYPTO.md names exists and works: HKDF-SHA256, Ed25519 key pair from a 32-byte seed, XChaCha20-Poly1305 with a caller-supplied nonce and associated data, X25519 key pair from 32 bytes, HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) seal and open, CRC-32 and PBKDF2-HMAC-SHA512 from the JDK. Check against published test vectors where they exist (RFC 8032, RFC 7748, RFC 9180).

Done when: each call is shown working, or the builder has stopped and told the owner which one is missing. Record the Tink version and the app size added.

## T2.3 Event model changes

Do: update `core-model` as listed in CRYPTO.md section 9: `encryptionKey` on `GroupCreated` and `MemberJoined`, `newEpoch` and sealed keys on `MemberRemoved` with the rule that `newEpoch` is the current epoch plus 1, numeric tag on `Unknown`. Update replay, the M1 fixtures and their tests.

Done when: all M1 tests pass again, with new tests for the epoch rule.

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
