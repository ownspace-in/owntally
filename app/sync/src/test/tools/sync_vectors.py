# Regenerates app/sync/src/test/resources/fixtures/sync-vectors.json.
# Usage: python3 -I sync_vectors.py <output path>   (needs Python 3 only)
# An independent encoder written from docs/WIRE_FORMAT.md section 6, not from the Kotlin code. If the Kotlin codec and this script
# disagree, one of them (or the spec) is wrong; the tests compare them byte for byte.
import hashlib, json, sys

def u8(n): return n.to_bytes(1, "big")
def u16(n): return n.to_bytes(2, "big")
def u32(n): return n.to_bytes(4, "big")
def u64(n): return n.to_bytes(8, "big")
def key(n): return bytes([n]) + bytes(31)
def st(s):
    b = s.encode("utf-8"); return u16(len(b)) + b

def fp_step(prev, counter, envelopes):
    hashes = sorted(set(hashlib.sha256(e).digest() for e in envelopes))
    assert 1 <= len(hashes) <= 2
    return hashlib.sha256(b"OTFP1" + prev + u64(counter) + u8(len(hashes)) + b"".join(hashes)).digest()[:16]

def summary(entries):  # list of (author number, counter, fingerprint bytes)
    items = sorted(entries, key=lambda e: key(e[0]))
    return u16(len(items)) + b"".join(key(a) + u64(c) + f for a, c, f in items)

def msg(t, content, version=1, magic=b"OS"): return magic + u8(version) + u8(t) + content

ROOM = bytes(range(16))
def env(n, size=100): return b"OT" + bytes([n]) * (size - 2)   # opaque to the sync layer

F0 = bytes(16)
f1 = fp_step(F0, 1, [env(1)])
f2 = fp_step(f1, 2, [env(2)])
f2b = fp_step(f1, 2, [env(2), env(9)])
g1 = fp_step(F0, 1, [env(5)])

valid = []
def add(name, data, desc): valid.append({"name": name, "hex": data.hex(), "description": desc})

add("hello-empty", msg(1, ROOM + summary([])), "no events yet")
add("hello-two-authors", msg(1, ROOM + summary([(2, 1, g1), (1, 2, f2)])), "entries are sorted by author on the wire")
add("done", msg(3, summary([(1, 2, f2)])), "a summary after storing")
add("reconcile", msg(4, key(7)), "histories of author 7 disagree")
add("error-version", msg(5, u8(1) + st("unsupported version")), "code 1")
add("error-empty-text", msg(5, u8(6) + st("")), "code 6, empty text")
add("envelopes-one", msg(2, u16(1) + u32(100) + env(1)), "one envelope")
add("envelopes-two", msg(2, u16(2) + u32(100) + env(1) + u32(150) + env(2, 150)), "two envelopes of different sizes")
add("envelopes-max-size-one", msg(2, u16(1) + u32(65536) + env(3, 65536)), "one envelope at the 64 KB limit")

invalid = []
def bad(name, data, reason, code): invalid.append({"name": name, "hex": data.hex(), "refusal": code, "description": reason})
# codes: 1 unsupported version, 4 too large, 5 invalid or unknown type
bad("empty", b"", "no bytes", 5)
bad("short-header", b"OS\x01", "header cut short", 5)
bad("wrong-magic", msg(4, key(7), magic=b"XX"), "wrong magic", 5)
bad("newer-version", msg(4, key(7), version=2), "version 2 is refused with code 1", 1)
bad("version-zero", msg(4, key(7), version=0), "version 0", 1)
bad("unknown-type", msg(9, b""), "unknown type 9 (code 5)", 5)
bad("type-zero", msg(0, b""), "type 0", 5)
bad("hello-short-room", msg(1, bytes(10)), "room shorter than 16 bytes", 5)
bad("hello-trailing", msg(1, ROOM + summary([]) + b"\x00"), "trailing byte", 5)
bad("summary-unsorted", msg(3, u16(2) + key(2) + u64(1) + g1 + key(1) + u64(1) + g1), "entries not in ascending order", 5)
bad("summary-duplicate", msg(3, u16(2) + key(1) + u64(1) + g1 + key(1) + u64(2) + g1), "the same author twice", 5)
bad("summary-counter-zero", msg(3, u16(1) + key(1) + u64(0) + g1), "counter 0", 5)
bad("summary-cut", msg(3, u16(1) + key(1) + u64(1)), "entry cut short", 5)
bad("summary-count-too-big", msg(3, u16(1001) + b"".join(key(i % 250 + 1) + u64(1) + g1 for i in range(1001))), "1,001 entries", 5)
bad("envelopes-empty", msg(2, u16(0)), "an empty batch", 5)
bad("envelopes-zero-length", msg(2, u16(1) + u32(0)), "an envelope of length 0", 5)
bad("envelopes-too-long", msg(2, u16(1) + u32(65537) + env(1, 65537)), "an envelope over 64 KB (code 4)", 4)
bad("envelopes-short", msg(2, u16(1) + u32(100) + env(1, 90)), "fewer bytes than the length says", 5)
bad("envelopes-trailing", msg(2, u16(1) + u32(100) + env(1) + b"\x00"), "a byte after the last envelope", 5)
bad("envelopes-201", msg(2, u16(201) + b"".join(u32(10) + env(1, 10) for _ in range(201))), "201 envelopes in one batch", 5)
bad("envelopes-over-bytes", msg(2, u16(5) + b"".join(u32(60000) + env(1, 60000) for _ in range(5))), "300,000 bytes in one batch (code 4)", 4)
bad("reconcile-short", msg(4, bytes(31)), "author key of 31 bytes", 5)
bad("error-unknown-code", msg(5, u8(7) + st("x")), "code 7 does not exist", 5)
bad("error-code-zero", msg(5, u8(0) + st("x")), "code 0", 5)
bad("error-text-too-long", msg(5, u8(5) + u16(201) + b"a" * 201), "text over 200 bytes", 5)
bad("error-bad-utf8", msg(5, u8(5) + u16(2) + b"\xff\xfe"), "text that is not UTF-8", 5)
bad("error-trailing", msg(5, u8(5) + st("x") + b"\x00"), "trailing byte", 5)

fingerprints = [
    {"name": "chain-two", "steps": [{"counter": 1, "envelopes": [env(1).hex()]}, {"counter": 2, "envelopes": [env(2).hex()]}], "fp": f2.hex(),
     "description": "two events, one version each"},
    {"name": "conflict-at-2", "steps": [{"counter": 1, "envelopes": [env(1).hex()]}, {"counter": 2, "envelopes": [env(2).hex(), env(9).hex()]}], "fp": f2b.hex(),
     "description": "two versions of the second event: a different fingerprint"},
    {"name": "conflict-order-free", "steps": [{"counter": 1, "envelopes": [env(1).hex()]}, {"counter": 2, "envelopes": [env(9).hex(), env(2).hex()]}], "fp": f2b.hex(),
     "description": "the versions may be given in any order"},
    {"name": "other-first", "steps": [{"counter": 1, "envelopes": [env(5).hex()]}], "fp": g1.hex(), "description": "a different first event"},
    {"name": "empty", "steps": [], "fp": F0.hex(), "description": "no events: sixteen zero bytes"},
]
json.dump({"room": ROOM.hex(), "valid": valid, "invalid": invalid, "fingerprints": fingerprints}, open(sys.argv[1], "w"), indent=1)
