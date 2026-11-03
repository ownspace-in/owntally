# Regenerates app/crypto/src/test/resources/crypto-vectors.json.
# Usage: python3 -I crypto_vectors.py <output path>   (needs Python 3 and the 'cryptography' package)
# An independent implementation of docs/CRYPTO.md sections 1, 2, 4 and 5 (and the envelope layout of docs/WIRE_FORMAT.md),
# written from the specs, not from the Kotlin code. The Kotlin tests compare byte for byte.
import hashlib, hmac, json, struct, sys
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
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
    "sealed": [
        sealed_event(0, bytes([9]) * 16, 2, 1, bytes(range(32)), bytes(range(0x40, 0x58)), EXAMPLE_PAYLOAD),
    ],
}
json.dump(out, open(sys.argv[1], "w"), indent=1)
print(len(out["slots"]), len(out["envelopes"]), len(out["sealed"]))
