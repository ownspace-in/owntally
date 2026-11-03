# Regenerates app/crypto/src/test/resources/crypto-vectors.json.
# Usage: python3 -I crypto_vectors.py <output path>   (needs Python 3 and the 'cryptography' package)
# An independent implementation of docs/CRYPTO.md sections 1, 2 and 4 (and the envelope layout of docs/WIRE_FORMAT.md),
# written from the specs, not from the Kotlin code. The Kotlin tests compare byte for byte.
import hashlib, hmac, json, sys
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

out = {
    "description": "Key derivation and signing vectors for docs/CRYPTO.md, from an independent implementation (Python cryptography + hashlib). The phrase is the well-known all-'abandon' test phrase; never use it for a real group.",
    "phrase": PHRASE, "seed": SEED.hex(),
    "slots": [keys_for(n) for n in (0, 1, 2, 255, 4294967295)],
    "envelopes": [
        signed_envelope(0, 1, bytes([9]) * 16, 2, 5, bytes(range(1, 25)), bytes(range(0xA0, 0xB4))),
        signed_envelope(1, 1, bytes([7]) * 16, 4294967295, 1099511627776, bytes(24), b""),
    ],
}
json.dump(out, open(sys.argv[1], "w"), indent=1)
print(len(out["slots"]), len(out["envelopes"]))
