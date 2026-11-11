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

`length` is the byte length of `content`. A decoder that does not know a `tag` skips `length` bytes and returns an unknown body (PROTOCOL.md section 3) that keeps its tag and its content, so old apps keep newer events in their log without understanding them and can write them back byte for byte. Tags 1 to 17 are always known events; a body can only be unknown when its tag is 0 or 18 and above.

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
| 8 | `PaymentDisputed` | `id` settlement, `u8` reason (0 nothing received, 1 different amount, 2 not checked, 3 duplicate: added 9 Nov 2026, decision P4) |
| 9 | `AdminAdded` | `key` member |
| 10 | `AdminRemoved` | `key` member |
| 11 | `OwnershipOffered` | `key` to |
| 12 | `OwnershipOfferCancelled` | empty |
| 13 | `OwnershipAccepted` | empty |
| 14 | `MemberRemoved` | `key` member, `u32` newEpoch, `map<key, sealedKey>` (see CRYPTO.md section 6; `sealedKey` is `u16` length and bytes) |
| 15 | `KeyRotated` | `u32` newEpoch, `map<key, sealedKey>` |
| 16 | `OwnershipDeclined` | empty (added 5 Nov 2026; an older decoder keeps it as an unknown body) |
| 17 | `KeyLinked` | `u32` epoch, 32 bytes `previousKey` (added 11 Nov 2026, M11, decision D1: the key of epoch `epoch - 1`; see CRYPTO.md section 6; an older decoder keeps it as an unknown body) |

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
- a known body whose `content` is longer or shorter than its fields need, an unknown `split` kind, or a dispute reason other than 0, 1, 2 or 3 (an unknown `tag` is the only thing that is skipped by length);
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

## 7. Join messages (M6, T6.2)

Status: **draft for the M6 plan (docs/tasks/M6-nearby-invites.md).** A person who holds a ticket (CRYPTO.md section 7) connects to the host named in it and asks to join. These messages use the same start as the sync messages (`'OS'`, version 1) with types from 16, so the first message tells a join from a sync (`JoinCodec.isJoin`). Decoding is strict: anything that is not exactly what the encoder makes is refused, and trailing bytes are an error.

| type | message | content |
|---|---|---|
| 16 | `Hello` (joiner) | room `bytes(16)`, ticketId `bytes(16)` (never the ticket: the group key stays on the joiner's phone) |
| 17 | `Challenge` (host) | nonce `bytes(32)` (fresh for this connection), signature `bytes(64)` (the host's, CRYPTO.md section 7a) |
| 18 | `Request` (joiner) | `u16` length, sealed request |
| 19 | `Reply` (host) | `u8`: 1 kept, 0 not kept (the same for every reason) |
| 20 | `StatusHello` (joiner, later connection) | room `bytes(16)`, joiner key `bytes(32)` |
| 21 | `StatusChallenge` (host) | nonce `bytes(32)` |
| 22 | `StatusProof` (joiner) | signature `bytes(64)` |
| 23 | `StatusReply` (host) | `u8`: 0 unknown, 1 pending, 2 approved, 3 declined |
| 24 | `PreviewRequest` (joiner, after the host's proof) | empty |
| 25 | `Preview` (host) | `u16` length, sealed preview (decision J1) |

The sealed request holds `joinerKey bytes(32) | encryptionKey bytes(32) | u16 name length, name | u16 phone length, phone | signature bytes(64)` (name at most 160 bytes, phone at most 64, UTF-8). The host answers a `Hello` with a `Challenge` for any ticket id, so a wrong id is not told apart until the reply. A silent peer is given up on after 30 seconds. The status answer is a hint: the proof of being admitted is that the host lets this phone sync.

The sealed preview holds `u16 length, group name | u16 length, owner's name | u16 member count` (UTF-8, name at most 240 bytes). It is sealed like the request but under its own label. The host answers a `PreviewRequest` with a `Preview` only for a ticket that still works, and asking does not use the ticket up; for any other ticket it answers `Reply(false)`. The same connection may go on to send the `Request`. Someone who knows only a ticket id gets a sealed blob they cannot open.

## 8. The local link (M6, T6.7)

Status: **draft for the M6 plan (decisions L1 and L2).** Two phones on the same network connect over TCP. Every message (the sync and join messages above) travels as a **frame**: a four-byte big-endian length, then the bytes; a length over 1,048,576 (or negative) is an error and the connection is closed. A frame cut off in the middle is an error, never a short message.

**Handshake (plain link).** Three frames before anything else, so each side shows it holds its key:
- Caller to answerer, `Hello`: `'OL'`, version `u8` (1), kinds `u8` (bit 0: plain), room `bytes(16)`, caller key `bytes(32)`, key called `bytes(32)`, nonce `bytes(32)`.
- Answerer to caller, `Reply`: `'OL'`, version, chosen kind `u8`, nonce `bytes(32)`, signature `bytes(64)`.
- Caller to answerer, `Proof`: `'OL'`, version, signature `bytes(64)`.

The answerer routes by the room and key called; if it has no such listener, or anything is wrong, it closes without a word. Both signatures (CRYPTO.md section 7b) cover the transcript: the `Hello`, the version, the chosen kind and the answerer's nonce. The caller checks the answerer's signature against the key it meant to reach; the answerer checks the caller's against the key it claims. Only then is the connection handed on. What the group allows that key to have is the sync engine's rule (decision S2).

**Finding the other phone.** A phone announces itself with Android's own service discovery as `_owntally._tcp` under the name `LocalNames.serviceName(room, key)`: 20 characters (lower case letters and 2 to 7) from the first 13 bytes of SHA-256(`"owntally/v1/local"`, room, key). Someone who knows the room and the key can work out the name and look for it; to anyone else it says nothing about the group or the person.

**TLS link (T6.8, decision L2).** A phone also takes TLS 1.3 connections, on a port of its own for each (room, key) it answers for, announced under its own name (`LocalNames.serviceName(room, key, TLS)`: the same recipe with the label `"owntally/v1/local-tls"`). Inside TLS the messages are the same frames. Each side shows a self-made certificate (CRYPTO.md section 7c) and asks for the other's; only TLS 1.3 is accepted. The caller checks the answerer's certificate carries the key it meant to reach; the answerer takes the caller's key from the caller's certificate. There is no `Hello` and no routing frame: the port tells the answerer which key it is answering for.

**Which kind is used (T6.10, decision L6).** A phone normally calls with `secureFirstTransportFor`: TLS first; if TLS does not connect within 5 seconds (the other phone offers none, is not found under the TLS name, refuses, or the handshake fails) the plain link is tried. A phone that answers **with the wrong key** is a refusal, not a failure to connect: no plain attempt follows. Both kinds arrive in one inbox, and each link says which it is (`Link.security`), which the sync status shows ("Encrypted link" or "Unencrypted link"). **Known cost:** a hostile network that breaks the TLS handshake pushes everyone to the plain link, where it can see who syncs with whom (not what).

## 9. Nearby Connections link (M6, T6.11)

Status: **draft for the M6 plan.** Same-room sync over Google's Nearby Connections (P2P_CLUSTER). It runs only while the person has the Nearby screen open (DESIGN.md). Each phone advertises and looks under an endpoint name made from the room and its key (`NearbyNames.endpointName`: 20 hex characters from SHA-256 of `"owntally/v1/nearby"`, room and key; opaque to anyone else nearby) and a service id that is the same for the group (`owntally.nearby.<room>`). Of two phones, **the one with the smaller public key opens the connection**; the other only answers. Both accept the connection at once; who the other is gets proven by key, not by name or by Nearby's pairing digits. **Messages** travel as `Chunker` chunks (Nearby allows 32 KB a payload) and are put back together in order. **Handshake:** the same three messages as the plain local link (section 8: `Hello`, `Reply`, `Proof`, CRYPTO.md section 7b), sent as messages over the connection; a link is handed on only after both have shown their keys. Nearby encrypts what it carries, so the link says no `security` of its own; the status shows "Nearby". A silent or failed handshake drops the connection after 10 seconds; finding the phone and connecting wait 15 seconds (DESIGN.md).

## 10. Links through the relay (M7, T7.3)

Status: **draft for the M7 plan.** The relay (`relay/ws-relay/README.md`) forwards a frame to the key a client names and tells nobody who is connected; it proves nothing about who sends. A link through it (or through any such carrier) therefore runs **TLS 1.3 end to end between the two members**, with the same member-bound certificates as the local link (CRYPTO.md section 7c); there is no plain link over a carrier (the relay operator could change or insert messages, and a changed closing summary could pass for the proof that a leave arrived, D4). **Frames** to the carrier are `[stream id u32][flags u8: 0 data, 1 end][bytes]`, at most 60,000 bytes each, which several links to one phone share by stream id; the bytes are the TLS stream. A frame with a stream id not seen before opens a stream for the answering side, which answers as the TLS server; the member key in the caller's certificate must be the sender key the carrier named, or the link is dropped. Frames are paced at 40 a second (burst 20), under the relay's 50 a second (burst 100). The messages inside TLS are the same frames as everywhere (section 8). A link says `RELAYED` and `TLS`.

## 11. Links over Iroh (M7, T7.4)

Status: **draft for the M7 plan.** n0's Iroh library (QUIC, direct between the phones when hole punching works, through a relay when not). **A phone's Iroh endpoint id is its member key** (decision I2): its signing seed is Iroh's secret key, so the endpoint id that Iroh's TLS 1.3 handshake proves is the member key, and dialling a member (or the host named in a ticket) needs nothing but the member key. One QUIC connection per peer; one bidirectional stream per link (ALPN `owntally/1`); messages are a 4-byte big-endian length and the bytes, at most 1,048,576 bytes (a larger or negative length ends the link without being allocated); an empty message (length 0) opens the stream for the other side, which sees a stream only once something is written, and is never delivered. The dialler checks the authenticated remote id is the key it dialled (a refusal is not retried); it retries for up to the connect wait (15 s) while the other phone's address is not yet published (found in M0). The link says `TLS` (QUIC) and `DIRECT` or `RELAYED` **as the selected path is at the time it is asked**, so a link that starts through the relay and then goes direct says so. The endpoint's home relay is `relay.ownspace.in` and the only relay it lists; its address publishing and lookup go to n0's discovery (decision I1).

