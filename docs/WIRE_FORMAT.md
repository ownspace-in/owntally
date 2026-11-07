# OwnTally wire format: events as bytes

Status: **APPROVED by the owner on 3 Nov 2026** (task T2.1 in [tasks/M2-identity-crypto.md](tasks/M2-identity-crypto.md)). Any change from here needs the owner's approval. Decision of 3 Nov 2026: the format is written by the project, not a standard such as CBOR. Because nobody else documents it, this file is the only specification.

Why it exists: a signature is made over bytes, so the same event must give exactly the same bytes on every phone and every app version. The rules below leave no choices. See [CRYPTO.md](CRYPTO.md) for what is signed and encrypted.

## 1. Primitives

All numbers are big-endian and fixed width. There are no variable-length numbers.

| Name | Encoding |
|---|---|
| `u8`, `u16`, `u32`, `u64` | 1, 2, 4, 8 bytes, unsigned |
| `i64` | 8 bytes, two's complement (money in paise, days, time) |
| `key` | 32 bytes (public key) |
| `bytes(n)` | exactly n bytes, no prefix |
| `str` | `u16` length in bytes, then UTF-8 bytes. No normalisation is done; names are stored as typed. Lengths over 65,535 are not allowed. |
| `set<key>` | `u16` count, then the keys in ascending unsigned byte order, no duplicates |
| `map<key, V>` | `u16` count, then (key, V) pairs in ascending key order, no duplicates |
| `id` (an `EventId`) | `key` author, `u64` counter (at least 1) |

## 2. Payload (what gets encrypted)

```
payload = after | time | body
after   = map<key, u64>        counters at least 1, as in PROTOCOL.md section 2
time    = i64                  wall clock milliseconds
body    = tag u8 | length u32 | content
```

`length` is the byte length of `content`. A decoder that does not know a `tag` skips `length` bytes and returns an unknown body (PROTOCOL.md section 3) that keeps its tag and its content, so old apps keep newer events in their log without understanding them and can write them back byte for byte. Tags 1 to 16 are always known events; a body can only be unknown when its tag is 0 or 17 and above.

### Example

A `ProfileUpdated` event with nothing seen yet (`after` empty), `time` 1000, name "Asha", phone "98765 43210":

```
00 00                                     after: count 0
00 00 00 00 00 00 03 E8                   time: 1000
03                                        tag 3 (ProfileUpdated)
00 00 00 13                               content length: 19
00 04 41 73 68 61                         name: length 4, "Asha"
00 0B 39 38 37 36 35 20 34 33 32 31 30    phone: length 11, "98765 43210"
```

That is 34 bytes. The same event always gives these 34 bytes.

### Body tags

| Tag | Body | Content |
|---|---|---|
| 1 | `GroupCreated` | `bytes(16)` groupId, `str` name, `bytes(3)` currency (ASCII), `key` encryptionKey |
| 2 | `MemberJoined` | `key` member, `key` encryptionKey, `str` name, `str` phone |
| 3 | `ProfileUpdated` | `str` name, `str` phone |
| 4 | `ExpenseAdded` | `str` description, `i64` total, `key` paidBy, `i64` day, split |
| 5 | `ExpenseDeleted` | `id` expense |
| 6 | `SettlementRecorded` | `key` from, `key` to, `i64` amount |
| 7 | `SettlementConfirmed` | `id` settlement |
| 8 | `PaymentDisputed` | `id` settlement, `u8` reason (0 nothing received, 1 different amount, 2 not checked) |
| 9 | `AdminAdded` | `key` member |
| 10 | `AdminRemoved` | `key` member |
| 11 | `OwnershipOffered` | `key` to |
| 12 | `OwnershipOfferCancelled` | empty |
| 13 | `OwnershipAccepted` | empty |
| 14 | `MemberRemoved` | `key` member, `u32` newEpoch, `map<key, sealedKey>` (see CRYPTO.md section 6; `sealedKey` is `u16` length and bytes) |
| 15 | `KeyRotated` | `u32` newEpoch, `map<key, sealedKey>` |
| 16 | `OwnershipDeclined` | empty (added 5 Nov 2026; an older decoder keeps it as an unknown body) |

`split` is `u8` kind then: kind 1 (`Equal`) `set<key>` participants; kind 2 (`Exact`) `map<key, i64>` amounts.

## 3. Envelope (what is stored and sent)

```
envelope  = 'OT' | version u8 | groupId bytes(16) | epoch u32 | author key | counter u64
            | nonce bytes(24) | ciphertextLength u32 | ciphertext | signature bytes(64)
```

- `'OT'` is the two ASCII bytes 0x4F 0x54. `version` is 1.
- `author`, `counter`, `groupId` and `epoch` are in the clear, because syncing needs them before anything is decrypted. `after`, `time` and `body` are inside the ciphertext, so the WebSocket relay operator or anyone holding a stored file learns who wrote how many events, but not what. (The connection itself is encrypted separately; see CRYPTO.md section 10.)
- The ciphertext is the encrypted payload (CRYPTO.md section 5). The signature covers every byte before it (CRYPTO.md section 4).

## 4. Strict decoding

A decoder rejects (treats the event as invalid) anything that is not exactly what the encoder would have produced:
- wrong magic, unknown envelope `version`, or fewer or more bytes than the lengths say (trailing bytes are an error);
- a `set` or `map` that is unsorted or has a duplicate;
- a `str` that is not valid UTF-8;
- a known body whose `content` is longer or shorter than its fields need, an unknown `split` kind, or a dispute reason other than 0, 1 or 2 (an unknown `tag` is the only thing that is skipped by length);
- a counter of 0, an `after` entry of 0, or a split that breaks the rules in PROTOCOL.md section 2 (the event constructors in `core-model` already check these).

Consequence: `decode(bytes)` succeeds only if `encode(decode(bytes)) == bytes`. Tests check this in both directions with random events and with fixed byte vectors.

## 5. Test vectors

`app/core-model/src/test/resources/fixtures/wire-vectors.json` holds 22 valid events, 21 bytes sequences that must be rejected (with the reason), 2 envelopes and 9 invalid envelopes, all with their exact hex. They were made before the Kotlin codec by a separate encoder written from this file (`app/core-model/src/test/tools/wire_vectors.py`), and they are the contract for other languages. The example in section 2 is vector `profile-updated`.

Note (4 Nov 2026): the 16 bytes of a `groupId` are not all random. They are 8 random bytes followed by the first 8 bytes of SHA-256(creator's public key + those 8 bytes), and a `GroupCreated` is valid only if its id matches its author (PROTOCOL.md). The bytes on the wire are unchanged.

Note (4 Nov 2026): a decoder also rejects an `ExpenseAdded` total or a `SettlementRecorded` amount above 10,000,000,000 paise (100,000,000 whole currency units), as an invalid value. The vectors `total-over-limit`, `total-huge` and `payment-over-limit` in `wire-vectors.json` cover it.
