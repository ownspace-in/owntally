# Regenerates app/crypto/src/test/resources/crypto-vectors.json.
# Usage: python3 -I crypto_vectors.py <output path>   (needs Python 3 and the 'cryptography' package)
# An independent implementation of docs/CRYPTO.md sections 1, 2, 4, 5, 6 and 7 (and the envelope layout of docs/WIRE_FORMAT.md),
# written from the specs, not from the Kotlin code. The Kotlin tests compare byte for byte.
import base64, hashlib, hmac, json, struct, sys, zlib
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives import serialization as S

RAW = dict(encoding=S.Encoding.Raw, format=S.PublicFormat.Raw)
PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
SEED = hashlib.pbkdf2_hmac("sha512", PHRASE.encode(), b"mnemonic", 2048, 64)

def hkdf(ikm, salt, info, n):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest(); out, t, i = b"", b"", 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:n]

def keys_for(slot):
    sign_seed = hkdf(SEED, b"owntally/v1", f"group/{slot}/sign".encode(), 32)
    enc_priv = hkdf(SEED, b"owntally/v1", f"group/{slot}/encrypt".encode(), 32)
    return {
        "slot": slot, "signSeed": sign_seed.hex(),
        "signPub": Ed25519PrivateKey.from_private_bytes(sign_seed).public_key().public_bytes(**RAW).hex(),
        "encPriv": enc_priv.hex(),
        "encPub": X25519PrivateKey.from_private_bytes(enc_priv).public_key().public_bytes(**RAW).hex(),
    }

def envelope_unsigned(version, group_id, epoch, author, counter, nonce, ciphertext):
    return (b"OT" + bytes([version]) + group_id + epoch.to_bytes(4, "big") + author + counter.to_bytes(8, "big")
            + nonce + len(ciphertext).to_bytes(4, "big") + ciphertext)

def signed_envelope(slot, version, group_id, epoch, counter, nonce, ciphertext):
    k = keys_for(slot)
    unsigned = envelope_unsigned(version, group_id, epoch, bytes.fromhex(k["signPub"]), counter, nonce, ciphertext)
    sig = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(k["signSeed"])).sign(b"owntally/v1/event" + unsigned)
    return {"slot": slot, "version": version, "groupId": group_id.hex(), "epoch": epoch, "counter": counter,
            "nonce": nonce.hex(), "ciphertext": ciphertext.hex(), "signature": sig.hex(), "hex": (unsigned + sig).hex()}

# --- XChaCha20-Poly1305 = HChaCha20 (draft-irtf-cfrg-xchacha) then ChaCha20-Poly1305 with a 12-byte nonce
def _rotl(x, n): return ((x << n) & 0xffffffff) | (x >> (32 - n))
def _qr(s, a, b, c, d):
    s[a] = (s[a] + s[b]) & 0xffffffff; s[d] = _rotl(s[d] ^ s[a], 16)
    s[c] = (s[c] + s[d]) & 0xffffffff; s[b] = _rotl(s[b] ^ s[c], 12)
    s[a] = (s[a] + s[b]) & 0xffffffff; s[d] = _rotl(s[d] ^ s[a], 8)
    s[c] = (s[c] + s[d]) & 0xffffffff; s[b] = _rotl(s[b] ^ s[c], 7)
def hchacha20(key, nonce16):
    s = list(struct.unpack("<4I", b"expand 32-byte k")) + list(struct.unpack("<8I", key)) + list(struct.unpack("<4I", nonce16))
    for _ in range(10):
        _qr(s,0,4,8,12); _qr(s,1,5,9,13); _qr(s,2,6,10,14); _qr(s,3,7,11,15)
        _qr(s,0,5,10,15); _qr(s,1,6,11,12); _qr(s,2,7,8,13); _qr(s,3,4,9,14)
    return struct.pack("<8I", *(s[0:4] + s[12:16]))
# the published HChaCha20 test vector (draft-irtf-cfrg-xchacha, section 2.2.1)
assert hchacha20(bytes(range(32)), bytes.fromhex("000000090000004a0000000031415927")).hex() == "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"
def xchacha_seal(key, nonce24, aad, plaintext):
    return ChaCha20Poly1305(hchacha20(key, nonce24[:16])).encrypt(b"\0\0\0\0" + nonce24[16:], plaintext, aad)

def sealed_event(slot, group_id, epoch, counter, group_key, nonce, payload):
    k = keys_for(slot); author = bytes.fromhex(k["signPub"])
    aad = b"owntally/v1/payload" + group_id + epoch.to_bytes(4, "big") + author + counter.to_bytes(8, "big")
    ciphertext = xchacha_seal(group_key, nonce, aad, payload)
    env = signed_envelope(slot, 1, group_id, epoch, counter, nonce, ciphertext)
    env.update({"groupKey": group_key.hex(), "payload": payload.hex(), "aad": aad.hex()})
    return env

# --- HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305 (RFC 9180), docs/CRYPTO.md section 6
def _hmac(key, data): return hmac.new(key, data, hashlib.sha256).digest()
def _extract(salt, ikm): return _hmac(salt or bytes(32), ikm)
def _expand(prk, info, n):
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = _hmac(prk, t + info + bytes([i])); out += t; i += 1
    return out[:n]
def _lextract(suite, salt, label, ikm): return _extract(salt, b"HPKE-v1" + suite + label + ikm)
def _lexpand(suite, prk, label, info, n): return _expand(prk, struct.pack(">H", n) + b"HPKE-v1" + suite + label + info, n)
_KEM = b"KEM" + struct.pack(">H", 0x0020)
_SUITE = b"HPKE" + struct.pack(">HHH", 0x0020, 0x0001, 0x0003)
def hpke_seal(recipient_pub, eph_sk, info, plaintext):
    eph_pk = X25519PrivateKey.from_private_bytes(eph_sk).public_key().public_bytes(**RAW)
    dh = X25519PrivateKey.from_private_bytes(eph_sk).exchange(X25519PublicKey.from_public_bytes(recipient_pub))
    shared = _lexpand(_KEM, _lextract(_KEM, b"", b"eae_prk", dh), b"shared_secret", eph_pk + recipient_pub, 32)
    ctx = bytes([0]) + _lextract(_SUITE, b"", b"psk_id_hash", b"") + _lextract(_SUITE, b"", b"info_hash", info)
    secret = _lextract(_SUITE, shared, b"secret", b"")
    key = _lexpand(_SUITE, secret, b"key", ctx, 32); nonce = _lexpand(_SUITE, secret, b"base_nonce", ctx, 12)
    return eph_pk + ChaCha20Poly1305(key).encrypt(nonce, plaintext, b"")

def key_delivery(slot, group_id, epoch, group_key, eph_sk):
    info = b"owntally/v1/key-delivery" + group_id + epoch.to_bytes(4, "big")
    sealed = hpke_seal(bytes.fromhex(keys_for(slot)["encPub"]), eph_sk, info, group_key)
    return {"recipientSlot": slot, "groupId": group_id.hex(), "epoch": epoch, "groupKey": group_key.hex(), "info": info.hex(), "sealed": sealed.hex()}

# --- tickets, docs/CRYPTO.md section 7
def ticket_bytes(ticket_id, host_id, group_id, epoch, group_key, expires, hint_bytes, version=1, hint_len=None):
    body = (bytes([version]) + ticket_id + host_id + group_id + epoch.to_bytes(4, "big") + group_key + expires.to_bytes(8, "big")
            + bytes([len(hint_bytes) if hint_len is None else hint_len]) + hint_bytes)
    return body + (zlib.crc32(body) & 0xffffffff).to_bytes(4, "big")
def ticket_text(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def ticket(name, hint, ticket_id=bytes(range(1, 17)), host=bytes([0xAA]) * 32, group=bytes([9]) * 16, epoch=2, key=bytes(range(32)), expires=1793000000):
    raw = ticket_bytes(ticket_id, host, group, epoch, key, expires, hint.encode())
    return {"name": name, "ticketId": ticket_id.hex(), "hostId": host.hex(), "groupId": group.hex(), "epoch": epoch, "groupKey": key.hex(),
            "expires": expires, "hint": hint, "text": ticket_text(raw), "bytes": len(raw)}
T = [ticket("no-hint", ""), ticket("with-hint", "relay.example.org", expires=1793028800), ticket("longest-hint", "h" * 100, epoch=4294967295),
     ticket("multibyte-hint", "\u00e9\u20ac", key=bytes([0xFF]) * 32)]
assert len(T[0]["text"]) == 152 and T[0]["bytes"] == 114
def bad(name, text, why): return {"name": name, "text": text, "why": why}
base = ticket_bytes(bytes(range(1, 17)), bytes([0xAA]) * 32, bytes([9]) * 16, 2, bytes(range(32)), 1793000000, b"")
def recrc(body): return body + (zlib.crc32(body) & 0xffffffff).to_bytes(4, "big")
body = base[:-4]
odd = ticket_bytes(bytes(range(1, 17)), bytes([0xAA]) * 32, bytes([9]) * 16, 2, bytes(range(32)), 1793028800, b"relay.example.org")  # 131 bytes: the last base64 character has 2 spare bits
odd_text = ticket_text(odd)
IT = [
    bad("changed-check-value", ticket_text(base[:-1] + bytes([base[-1] ^ 1])), "last byte of the CRC changed"),
    bad("changed-secret-byte", ticket_text(base[:40] + bytes([base[40] ^ 1]) + base[41:]), "a byte of the ticket changed, CRC unchanged"),
    bad("truncated", T[0]["text"][:-1], "one character missing"),
    bad("extra-character", T[0]["text"] + "A", "one character too many"),
    bad("empty", "", "nothing"),
    bad("unknown-version", ticket_text(recrc(bytes([2]) + body[1:])), "version 2, valid CRC"),
    bad("version-zero", ticket_text(recrc(bytes([0]) + body[1:])), "version 0, valid CRC"),
    bad("epoch-zero", ticket_text(recrc(body[:1 + 16 + 32 + 16] + bytes(4) + body[1 + 16 + 32 + 16 + 4:])), "epoch 0, valid CRC"),
    bad("hint-longer-than-allowed", ticket_text(ticket_bytes(bytes(range(1, 17)), bytes([0xAA]) * 32, bytes([9]) * 16, 2, bytes(range(32)), 1793000000, b"h" * 101)), "hint of 101 bytes, valid CRC"),
    bad("hint-length-beyond-data", ticket_text(recrc(body[:-1] + bytes([5]))), "says a 5 byte hint, none follows, valid CRC"),
    bad("hint-not-utf8", ticket_text(ticket_bytes(bytes(range(1, 17)), bytes([0xAA]) * 32, bytes([9]) * 16, 2, bytes(range(32)), 1793000000, b"\xff\xfe")), "hint bytes are not UTF-8, valid CRC"),
    bad("padding-added", T[1]["text"] + "=", "base64 padding is not used"),
    bad("space-inside", T[0]["text"][:50] + " " + T[0]["text"][50:], "whitespace inside"),
    bad("not-base64url", "+" * 152, "characters outside the URL-safe alphabet"),
    bad("non-canonical-base64", odd_text[:-1] + base64.urlsafe_b64encode(b"\x00" * 3).decode()[0:0] + chr(ord("B") if odd_text[-1] == "A" else ord("A")), "spare bits of the last character are not zero"),
]
# make the non-canonical vector precisely: same text with the last character's lowest bit set
alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
last = alphabet.index(odd_text[-1]); assert last % 4 == 0
IT[-1] = bad("non-canonical-base64", odd_text[:-1] + alphabet[last | 1], "the last character has a spare bit set")
assert alphabet[last | 1] != odd_text[-1]

# the 34-byte worked example of docs/WIRE_FORMAT.md (a ProfileUpdated for counter 1)
EXAMPLE_PAYLOAD = bytes.fromhex("0000" "00000000000003e8" "03" "00000013" "0004" "41736861" "000b" "3938373635203433323130")

out = {
    "description": "Key derivation and signing vectors for docs/CRYPTO.md, from an independent implementation (Python cryptography + hashlib). The phrase is the well-known all-'abandon' test phrase; never use it for a real group.",
    "phrase": PHRASE, "seed": SEED.hex(),
    "slots": [keys_for(n) for n in (0, 1, 2, 255, 4294967295)],
    "envelopes": [
        signed_envelope(0, 1, bytes([9]) * 16, 2, 5, bytes(range(1, 25)), bytes(range(0xA0, 0xB4))),
        signed_envelope(1, 1, bytes([7]) * 16, 4294967295, 1099511627776, bytes(24), b""),
    ],
    "tickets": T,
    "invalidTickets": IT,
    "keyDeliveries": [
        key_delivery(1, bytes([9]) * 16, 2, bytes(range(32, 64)), bytes([9]) * 32),
        key_delivery(2, bytes([9]) * 16, 3, bytes(range(64, 96)), bytes([5]) * 32),
    ],
    "sealed": [
        sealed_event(0, bytes([9]) * 16, 2, 1, bytes(range(32)), bytes(range(0x40, 0x58)), EXAMPLE_PAYLOAD),
    ],
}
json.dump(out, open(sys.argv[1], "w"), indent=1)
print(len(out["slots"]), len(out["envelopes"]), len(out["sealed"]), len(out["keyDeliveries"]), len(T), len(IT))
