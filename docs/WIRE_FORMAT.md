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

## 6. Sync messages (M5, T5.2)

Status: **draft for the M5 plan (docs/tasks/M5-sync.md), decided in S1 and S2.** Two phones that sync exchange these messages over a `Link` (one message per send; a transport that has a size limit cuts it into chunks and joins them again, so a message is always whole when it arrives). They carry envelopes as opaque bytes and never need a key.

```
message = 'OS' | version u8 | type u8 | content
```

`'OS'` is the two ASCII bytes 0x4F 0x53. `version` is 1. A decoder never throws: it returns the message, or a refusal with a reason, and the engine answers a refusal with an `Error` message.

| type | name | content |
|---|---|---|
| 1 | `Hello` | `room bytes(16)` \| `summary` |
| 2 | `Envelopes` | `count u16` (1 to 200) \| count times (`length u32` (1 to 65,536) \| envelope bytes) |
| 3 | `Done` | `summary` |
| 4 | `Reconcile` | `author key` |
| 5 | `Error` | `code u8` \| `text str` (at most 200 bytes) |

```
summary = count u16 (at most 1,000) | count times ( author key | counter u64 (at least 1) | fingerprint bytes(16) )
```

The entries are in ascending author order with no duplicates. `counter` is the highest counter such that every counter from 1 to it is present for that author (the same number as `EventLog.summary()`), and `fingerprint` is the fingerprint (below) at that counter. An author with no events is left out.

- **Hello** opens a sync; each side sends one. `room` is the group's room name (DESIGN.md, `GroupRoom`); a different room is refused with code 2.
- **Envelopes** carries whole envelopes (section 3), at most 200 and at most 262,144 bytes in all, each at most 65,536 bytes. A sender with more sends several messages.
- **Done** ends one side's sending and carries the sender's summary at that moment. The order is fixed: both send `Hello`; the **initiator** sends what the responder lacks and a `Done`; the **responder**, having stored all of it, sends what the initiator lacks and a `Done`, whose summary therefore shows what it stored of the initiator's events. A phone that wants proof that its leave arrived (it is the initiator) reads it from the responder's `Done` (T5.9).
- **Reconcile** says "our histories of this author disagree (the fingerprints differ for counters we both have): send me every version you hold of this author's events, and I send you mine". Only the phone whose history of that author is at least as long as the peer's can see the difference (it computes its own fingerprint at the peer's counter and compares); it compares right after the `Hello`s. The rounds with a difference: the initiator sends a `Reconcile` for each author it found, then the envelopes the responder lacks **and every version it holds of those authors**, then `Done`. The responder reads all that, sends a `Reconcile` for each author *it* found, then what the initiator lacks and every version of the authors either side named, then `Done`. If the responder sent any `Reconcile`, the initiator answers with every version it holds of those authors it had not already sent, and a last `Done`, which the responder waits for exactly when it sent a `Reconcile`. Both phones then hold the union of what each had (the two smallest versions of each id) and the same fingerprint.
- **Error** ends the sync. Codes: 1 unsupported version, 2 wrong group, 3 not allowed, 4 message too large, 5 invalid message, 6 too many bad envelopes. `text` is plain UTF-8 for the log, never shown as a rule.

**Strict decoding**, as in section 4: wrong magic, a version other than 1, an unknown type, a short or long message, trailing bytes, unsorted or duplicate summary entries, a counter of 0, a count or length over its limit, an empty `Envelopes`, an unknown error code, or text that is not valid UTF-8 are all refusals. A newer `version` is refused with code 1, an envelope or a batch over its size limit with code 4, and every other fault (including an unknown `type`) with code 5, so the other side can be told.

### Who may have what (S2, T5.7)

Before anything is sent, the phone decides by the key of the link what the peer may have (`PeerAccess`, `Admission`):
- **A key that was never a member of the group: refused.** A phone that was connected to answers with one `Error` (code 3) and nothing else, not even a `Hello`; one that connected says nothing. The peer learns no summary and receives no envelope, and whatever it sent is not read.
- **A member: everything**, as above.
- **Someone the owner removed: limited.** Their `Hello` is answered with a summary clipped to what the removal event had seen (its `after` clock, with the fingerprints at those counters) plus their own entry, they are sent exactly one envelope, the removal event (written under the key they hold, so they can read it), no fingerprints are compared with them, and no `Reconcile` from them is answered. What they hand over is stored as usual.
- **Someone who left on their own: sent nothing.** The summary they see holds only their own entry; the responder's closing `Done` carries that entry after storing what they sent, which proves their leave arrived.
- A phone decides again after it has read what the peer sent, before it sends its own part: a leaver's first connection arrives as a member's, and the leave is in what they send.

### Fingerprint (S1)

Counters alone cannot show that two phones hold different versions of the same event (same author and counter). The fingerprint of an author's events changes if any version differs:

```
fp(0) = 16 zero bytes
fp(c) = first 16 bytes of SHA-256( 'OTFP1' | fp(c-1) | c u64 | n u8 | h(1) ... h(n) )
```

`c` is the counter, from 1. `h(i)` is the SHA-256 of an envelope's bytes (section 3) stored for the id (author, c); the distinct hashes are in ascending unsigned byte order, and `n` is how many there are: **1 normally, 2 when two different versions exist.** A phone keeps at most the two smallest distinct versions of an id (the rules treat two or more alike: both are ignored, PROTOCOL.md section 3), so two phones that exchange their versions end with the same two and the same fingerprint. Phones with the same counters but different fingerprints for an author use `Reconcile`.

`summary()` uses the fingerprint at the author's highest contiguous counter; to compare with a peer whose counter is lower, a phone computes its own fingerprint at the peer's counter.

### Test vectors

`app/sync/src/test/resources/fixtures/sync-vectors.json` holds valid messages, invalid byte sequences (with the refusal), and fingerprint chains, with exact hex, made by an independent encoder written from this section (`app/sync/src/test/tools/sync_vectors.py`) before the Kotlin codec.
