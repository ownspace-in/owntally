# BIP39 English word list

`english.txt` is the 2048-word English list from BIP-0039 (https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt, BIP licence: BSD 2-clause).
Downloaded 3 Nov 2026 and compared byte for byte with the copy in trezor/python-mnemonic. SHA-256:
`2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`. The code checks this hash before using the list and refuses to run if it differs.
