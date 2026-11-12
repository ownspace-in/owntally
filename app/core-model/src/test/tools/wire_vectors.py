# Regenerates app/core-model/src/test/resources/fixtures/wire-vectors.json.
# Usage: python3 -I wire_vectors.py <output path>   (needs Python 3 only)
# This is a second, independent encoder written from docs/WIRE_FORMAT.md, not from the Kotlin code. If the Kotlin
# codec and this script disagree, one of them (or the spec) is wrong; the tests compare them byte for byte.
# Independent encoder written from docs/WIRE_FORMAT.md only. Produces wire-vectors.json.
import json, sys
NAMES = ["asha", "ben", "chitra", "dev", "eve", "fay"]
KEY = {n: bytes([i + 1]) + bytes(31) for i, n in enumerate(NAMES)}
def u8(n): return n.to_bytes(1, "big")
def u16(n): return n.to_bytes(2, "big")
def u32(n): return n.to_bytes(4, "big")
def u64(n): return n.to_bytes(8, "big")
def i64(n): return n.to_bytes(8, "big", signed=True)
def key(n): return KEY[n]
def st(s):
    b = s.encode("utf-8"); assert len(b) <= 65535; return u16(len(b)) + b
def kmap(d):  # name -> already encoded value bytes
    items = sorted(((KEY[k], v) for k, v in d.items()), key=lambda x: x[0])
    return u16(len(items)) + b"".join(k + v for k, v in items)
def kset(names):
    ks = sorted(KEY[n] for n in names); assert len(set(ks)) == len(ks)
    return u16(len(ks)) + b"".join(ks)
def eid(x): return KEY[x[0]] + u64(x[1])
REASON = {"NOTHING_RECEIVED": 0, "DIFFERENT_AMOUNT": 1, "NOT_CHECKED": 2, "DUPLICATE": 3}
def sealed(h): b = bytes.fromhex(h); return u16(len(b)) + b

def body(b):
    t = b["type"]
    if t == "GroupCreated":
        return 1, bytes.fromhex(b["groupId"]) + st(b["name"]) + b["currency"].encode("ascii") + bytes.fromhex(b["encryptionKey"])
    if t == "MemberJoined": return 2, key(b["member"]) + bytes.fromhex(b["encryptionKey"]) + st(b["name"]) + st(b["phone"])
    if t == "ProfileUpdated": return 3, st(b["name"]) + st(b["phone"])
    if t == "ExpenseAdded":
        sp = b["split"]
        if sp["type"] == "Equal": split = u8(1) + kset(sp["participants"])
        else: split = u8(2) + kmap({k: i64(v) for k, v in sp["amounts"].items()})
        return 4, st(b["description"]) + i64(b["total"]) + key(b["paidBy"]) + i64(b["day"]) + split
    if t == "ExpenseDeleted": return 5, eid(b["expense"])
    if t == "SettlementRecorded": return 6, key(b["from"]) + key(b["to"]) + i64(b["amount"])
    if t == "SettlementConfirmed": return 7, eid(b["settlement"])
    if t == "PaymentDisputed": return 8, eid(b["settlement"]) + u8(REASON[b["reason"]])
    if t == "AdminAdded": return 9, key(b["member"])
    if t == "AdminRemoved": return 10, key(b["member"])
    if t == "OwnershipOffered": return 11, key(b["to"])
    if t == "OwnershipOfferCancelled": return 12, b""
    if t == "OwnershipAccepted": return 13, b""
    if t == "OwnershipDeclined": return 16, b""
    if t == "MemberRemoved": return 14, key(b["member"]) + u32(b["newEpoch"]) + kmap({k: sealed(v) for k, v in b["sealedKeys"].items()})
    if t == "KeyRotated": return 15, u32(b["newEpoch"]) + kmap({k: sealed(v) for k, v in b["sealedKeys"].items()})
    if t == "Unknown": return b["tag"], bytes.fromhex(b["content"])
    raise SystemExit("unknown body " + t)

def payload(e):
    tag, content = body(e["body"])
    return kmap({k: u64(v) for k, v in e["after"].items()}) + i64(e["time"]) + u8(tag) + u32(len(content)) + content

def envelope(v):
    return (b"OT" + u8(v["version"]) + bytes.fromhex(v["groupId"]) + u32(v["epoch"]) + key(v["author"]) + u64(v["counter"])
            + bytes.fromhex(v["nonce"]) + u32(len(bytes.fromhex(v["ciphertext"]))) + bytes.fromhex(v["ciphertext"]) + bytes.fromhex(v["signature"]))

ENC = lambda n: (bytes([n]) * 32).hex()
SEALED = lambda n: (bytes([n]) * 80).hex()
def ev(name, author, counter, after, time, body_):
    return {"name": name, "author": author, "counter": counter, "version": 1, "after": after, "time": time, "body": body_}

V = [
    ev("profile-updated", "asha", 1, {}, 1000, {"type": "ProfileUpdated", "name": "Asha", "phone": "98765 43210"}),
    ev("group-created", "asha", 1, {}, 1001, {"type": "GroupCreated", "groupId": ("09" * 16), "name": "Goa Trip", "currency": "INR", "encryptionKey": ENC(0x11)}),
    ev("member-joined", "asha", 3, {"asha": 2}, 1002, {"type": "MemberJoined", "member": "ben", "encryptionKey": ENC(0x22), "name": "Ben", "phone": "98765 00002"}),
    ev("expense-equal", "ben", 3, {"asha": 7, "ben": 2, "chitra": 1}, 1003, {"type": "ExpenseAdded", "description": "Taxi", "total": 250000, "paidBy": "ben", "day": 20001, "split": {"type": "Equal", "participants": ["dev", "ben", "chitra"]}}),
    ev("expense-exact", "chitra", 2, {"chitra": 1, "asha": 7}, 1004, {"type": "ExpenseAdded", "description": "Dinner", "total": 180000, "paidBy": "chitra", "day": 20001, "split": {"type": "Exact", "amounts": {"dev": 60000, "asha": 30000, "ben": 40000, "chitra": 50000}}}),
    ev("expense-deleted", "dev", 2, {"dev": 1, "ben": 3}, 1005, {"type": "ExpenseDeleted", "expense": ["ben", 3]}),
    ev("settlement-recorded", "eve", 1, {"asha": 8}, 1006, {"type": "SettlementRecorded", "from": "eve", "to": "asha", "amount": 200000}),
    ev("settlement-confirmed", "asha", 9, {"asha": 8, "eve": 1}, 1007, {"type": "SettlementConfirmed", "settlement": ["eve", 1]}),
    ev("payment-disputed", "chitra", 3, {"chitra": 2, "ben": 2}, 1008, {"type": "PaymentDisputed", "settlement": ["ben", 2], "reason": "DIFFERENT_AMOUNT"}),
    ev("payment-disputed-duplicate", "chitra", 4, {"chitra": 3, "ben": 2}, 1008, {"type": "PaymentDisputed", "settlement": ["ben", 2], "reason": "DUPLICATE"}),
    ev("admin-added", "asha", 10, {"asha": 9}, 1009, {"type": "AdminAdded", "member": "ben"}),
    ev("admin-removed", "asha", 11, {"asha": 10}, 1010, {"type": "AdminRemoved", "member": "ben"}),
    ev("ownership-offered", "asha", 12, {"asha": 11}, 1011, {"type": "OwnershipOffered", "to": "ben"}),
    ev("ownership-offer-cancelled", "asha", 13, {"asha": 12}, 1012, {"type": "OwnershipOfferCancelled"}),
    ev("ownership-accepted", "ben", 4, {"ben": 3, "asha": 13}, 1013, {"type": "OwnershipAccepted"}),
    ev("ownership-declined", "chitra", 3, {"chitra": 2, "asha": 13}, 1019, {"type": "OwnershipDeclined"}),
    ev("member-removed", "asha", 14, {"asha": 13}, 1014, {"type": "MemberRemoved", "member": "eve", "newEpoch": 2, "sealedKeys": {"ben": SEALED(2), "asha": SEALED(1), "dev": SEALED(4), "chitra": SEALED(3)}}),
    ev("leave", "dev", 3, {"dev": 2}, 1015, {"type": "MemberRemoved", "member": "dev", "newEpoch": 2, "sealedKeys": {}}),
    ev("key-rotated", "asha", 15, {"asha": 14}, 1016, {"type": "KeyRotated", "newEpoch": 3, "sealedKeys": {"asha": SEALED(1), "ben": SEALED(2)}}),
    ev("unknown-tag", "asha", 16, {"asha": 15}, 1017, {"type": "Unknown", "tag": 200, "content": "0102"}),
    ev("unknown-tag-empty", "asha", 17, {"asha": 16}, 1018, {"type": "Unknown", "tag": 18, "content": ""}),
    ev("large-numbers", "fay", 1099511627777, {"fay": 1099511627776, "asha": 4294967296}, -1, {"type": "ExpenseAdded", "description": "Big", "total": 10000000000, "paidBy": "fay", "day": -1, "split": {"type": "Equal", "participants": ["fay"]}}),
    ev("unicode-name", "asha", 2, {"asha": 1}, 1019, {"type": "ProfileUpdated", "name": "अशोक ☃", "phone": "+91 98765"}),
]
payloads = []
for v in V:
    h = payload(v).hex()
    payloads.append(dict(v, hex=h))
# the worked example in docs/WIRE_FORMAT.md, by independent calculation
assert payloads[0]["hex"] == "0000" "00000000000003e8" "03" "00000013" "0004" "41736861" "000b" "3938373635203433323130", payloads[0]["hex"]
assert len(payloads[0]["hex"]) // 2 == 34

def mut(name, base, fn, why):
    b = bytearray(bytes.fromhex(base["hex"])); out = fn(b); b = out if out is not None else b
    return {"name": name, "author": base["author"], "counter": base["counter"], "version": 1, "hex": bytes(b).hex(), "why": why}
byname = {p["name"]: p for p in payloads}
eq, ex, pd, pr, un = byname["expense-equal"], byname["expense-exact"], byname["payment-disputed"], byname["profile-updated"], byname["unknown-tag"]
def swap_first_two_keys_in_set(b):
    # expense-equal: find the participant set (count 3, then keys in order 02,03,04) and swap two keys
    i = bytes(b).rindex(bytes([0, 3]) + KEY["ben"]); b[i + 2:i + 34], b[i + 34:i + 66] = bytes(b[i + 34:i + 66]), bytes(b[i + 2:i + 34]); return b
def dup_key_in_set(b):
    i = bytes(b).rindex(bytes([0, 3]) + KEY["ben"]); b[i + 34:i + 66] = KEY["ben"]; return b
def add_len(delta, extra):
    def f(b):
        # the body length is the 4 bytes after the tag, which follows after (2+n*40) + 8 bytes
        n = int.from_bytes(b[0:2], "big"); p = 2 + n * 40 + 8 + 1
        L = int.from_bytes(b[p:p + 4], "big") + delta; b[p:p + 4] = L.to_bytes(4, "big"); return b + extra
    return f
INVALID_P = [
    mut("unsorted-set", eq, swap_first_two_keys_in_set, "participants not in ascending key order"),
    mut("duplicate-in-set", eq, dup_key_in_set, "the same participant twice"),
    mut("trailing-byte", pr, lambda b: b + b"\x00", "a byte after the body"),
    mut("declined-with-content", byname["ownership-declined"], add_len(1, b"\x00"), "OwnershipDeclined has no content, one byte was added"),
    mut("content-longer-than-fields", pr, add_len(1, b"\x00"), "body content has a byte left over"),
    mut("content-shorter-than-fields", pr, add_len(-1, b""), "body content ends in the middle of a field"),
    mut("unknown-split-kind", ex, lambda b: b.__setitem__(bytes(b).index(bytes([2, 0, 4])), 3) or b, "split kind 3"),
    mut("bad-dispute-reason", pd, lambda b: b.__setitem__(len(b) - 1, 5) or b, "dispute reason 5"),
    mut("bad-dispute-reason-four", pd, lambda b: b.__setitem__(len(b) - 1, 4) or b, "dispute reason 4: the first one after the last known (3 is duplicate)"),
    mut("invalid-utf8", pr, lambda b: b.__setitem__(bytes(b).index(b"Asha"), 0xFF) or b, "name has a byte that is not UTF-8"),
    mut("truncated", eq, lambda b: b[:-5], "payload cut short"),
    mut("empty", pr, lambda b: bytearray(), "no bytes at all"),
    mut("after-counter-zero", eq, lambda b: b.__setitem__(slice(2 + 32, 2 + 40), bytes(8)) or b, "an after entry of 0"),
    mut("after-unsorted", eq, lambda b: (b.__setitem__(slice(2, 42), bytes(b[42:82])), b.__setitem__(slice(42, 82), bytes(eq_first))) or b, "after entries out of order") if False else None,
]
INVALID_P = [x for x in INVALID_P if x]
# after-unsorted done explicitly: swap the first two (key, counter) entries of expense-equal's after map
b = bytearray(bytes.fromhex(eq["hex"])); e1, e2 = bytes(b[2:42]), bytes(b[42:82]); b[2:42], b[42:82] = e2, e1
INVALID_P.append({"name": "after-unsorted", "author": "ben", "counter": 3, "version": 1, "hex": bytes(b).hex(), "why": "after entries out of key order"})
# values the Event constructors refuse
def with_(base, changes, name, why):
    d = json.loads(json.dumps(base)); d["body"].update(changes); return {"name": name, "author": d["author"], "counter": d["counter"], "version": 1, "hex": payload(d).hex(), "why": why}
INVALID_P.append(with_(eq, {"total": 0}, "zero-total", "total must be greater than 0"))
INVALID_P.append(with_(eq, {"total": 10000000001}, "total-over-limit", "total is above the limit of 10,000,000,000 paise (100,000,000 whole units)"))
INVALID_P.append(with_(eq, {"total": 9223372036854775807}, "total-huge", "total is above the limit"))
INVALID_P.append(with_(byname["settlement-recorded"], {"amount": 10000000001}, "payment-over-limit", "amount is above the limit"))
INVALID_P.append(with_(byname["profile-updated"], {"name": ""}, "empty-name", "name must not be empty"))
INVALID_P.append(with_(byname["group-created"], {"currency": "inr"}, "lowercase-currency", "currency must be 3 upper case letters"))
INVALID_P.append(with_(ex, {"split": {"type": "Exact", "amounts": {"asha": 100000, "ben": 80001}}}, "exact-sum-mismatch", "exact amounts do not add up to the total"))
# the author's own previous counter missing from after
b3 = json.loads(json.dumps(eq)); b3["after"] = {"asha": 7}
INVALID_P.append({"name": "own-previous-counter-missing", "author": "ben", "counter": 3, "version": 1, "hex": payload(b3).hex(), "why": "after must hold the author's own previous counter"})

E = [
    {"name": "envelope", "version": 1, "groupId": "09" * 16, "epoch": 2, "author": "asha", "counter": 5, "nonce": bytes(range(1, 25)).hex(), "ciphertext": bytes(range(0xA0, 0xB4)).hex(), "signature": bytes((0xB0 + i % 16) for i in range(64)).hex()},
    {"name": "envelope-empty-ciphertext-large-numbers", "version": 1, "groupId": "07" * 16, "epoch": 4294967295, "author": "fay", "counter": 1099511627776, "nonce": ("00" * 24), "ciphertext": "", "signature": ("ff" * 64)},
]
for e in E: e["hex"] = envelope(e).hex()
base = bytes.fromhex(E[0]["hex"])
def bad_env(name, data, why): return {"name": name, "hex": data.hex(), "why": why}
INVALID_E = [
    bad_env("wrong-magic", b"XX" + base[2:], "does not start with OT"),
    bad_env("unknown-version", base[:2] + b"\x02" + base[3:], "envelope version 2"),
    bad_env("version-zero", base[:2] + b"\x00" + base[3:], "envelope version 0"),
    bad_env("truncated", base[:-1], "one byte short"),
    bad_env("trailing-byte", base + b"\x00", "a byte after the signature"),
    bad_env("ciphertext-length-too-big", base[:2 + 1 + 16 + 4 + 32 + 8 + 24] + (21).to_bytes(4, "big") + base[2 + 1 + 16 + 4 + 32 + 8 + 24 + 4:], "length says 21, only 20 follow before the signature"),
    bad_env("counter-zero", base[:2 + 1 + 16 + 4 + 32] + bytes(8) + base[2 + 1 + 16 + 4 + 32 + 8:], "counter 0"),
    bad_env("empty", b"", "no bytes"),
    bad_env("only-header", base[:20], "cut inside the header"),
]
out = {"description": "Wire format test vectors for docs/WIRE_FORMAT.md, made by an independent encoder written from the spec (not from the Kotlin code). 'hex' is the exact bytes. Keys are named; each name stands for a 32-byte key whose first byte is its position in 'keys' (asha=01, ben=02, ...) and whose other bytes are 0.",
       "keys": {n: KEY[n].hex() for n in NAMES}, "payloads": payloads, "invalidPayloads": INVALID_P, "envelopes": E, "invalidEnvelopes": INVALID_E}
json.dump(out, open(sys.argv[1], "w"), indent=1)
print(len(payloads), len(INVALID_P), len(E), len(INVALID_E))
