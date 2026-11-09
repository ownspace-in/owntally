# Invite site (joinowntally.ownspace.in)

Static files only. Nothing runs on the server and nothing is stored. The invite code is in the part of the link after `#`, which browsers never send to the server, and the nginx config turns access logging off for this site.

| File | Purpose |
|---|---|
| `index.html` | Short landing page at `/` |
| `j/index.html`, `j/invite.js` | The page behind `https://joinowntally.ownspace.in/j#<code>`. Shown only to people who do not have the app, or on a computer. With the app installed and App Links verified, Android opens the app directly. |
| `style.css` | Shared styles, light and dark |
| `nginx/joinowntally.ownspace.in.conf` | nginx config for the VM |
| `assetlinks.py`, `assetlinks.template.json`, `test_assetlinks.py` | The tools that make and check the file below (not deployed). |
| `.well-known/assetlinks.json` | **Not in the repo yet.** Made from the signing certificate: see below. |

## Deploy

```
sudo mkdir -p /var/www/joinowntally
rsync -av --delete --exclude nginx --exclude README.md --exclude assetlinks.py --exclude assetlinks.template.json --exclude test_assetlinks.py --exclude __pycache__ site/ <user>@<vm>:/tmp/joinowntally/
ssh <user>@<vm> 'sudo rsync -av /tmp/joinowntally/ /var/www/joinowntally/'
```

Certificates and nginx setup are in `relay/ws-relay/RUNBOOK.md` (step 4). The same nginx serves this site and the relay.

## assetlinks.json (needed for "tap opens the app")

The file tells Android that the app may open links of this site. It is made, not typed, from the SHA-256 fingerprint of the certificate the app is signed with. The manifest already claims the link (`android:autoVerify="true"`, host `joinowntally.ownspace.in`, path `/j`); until the file matches the installed app's certificate Android does not verify it, and the link opens in the browser, where this site's page shows the code, so nothing is lost.

**1. Get the fingerprint.** Which one depends on how the app is signed (M10):
- A key you keep yourself (release signing): `keytool -list -v -keystore <your keystore> -alias <alias>` and copy the line `SHA256:`.
- Google Play app signing (the usual case for the Play Store): in the Play Console, Setup, App signing, copy "SHA-256 certificate fingerprint" of the **app signing key** (and, if you also install builds signed with your upload key, the upload key's too).
- To try it before a release, on a debug build: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` (the debug certificate; do not publish it for a release build).

**2. Make the file** (any of the usual ways of writing a fingerprint is accepted; use `--fingerprint` once for each certificate):

```
python3 site/assetlinks.py make --fingerprint 14:6D:E9:... --out site/.well-known/assetlinks.json
```

**3. Deploy** it with the rest of the site (the deploy command above copies `.well-known/`; the tools are left off the server).

**4. Check it** from the outside: it must answer 200, `Content-Type: application/json`, with no redirect, and name this app and the certificate:

```
python3 site/assetlinks.py check --url https://joinowntally.ownspace.in/.well-known/assetlinks.json --fingerprint 14:6D:E9:...
curl -i https://joinowntally.ownspace.in/.well-known/assetlinks.json
```

**5. On a phone:** install the build, then `adb shell pm get-app-links in.ownspace.owntally` should show `joinowntally.ownspace.in: verified` (on Android 12 and newer); if it does not say verified, wait a minute and ask again, or run `adb shell pm verify-app-links --re-verify in.ownspace.owntally` (these commands come from Android's own documentation and have not been run by us: the first signed build is the first time they can be). Tapping an invite link then opens the app with no question asked. Without the verification, Settings, Apps, OwnTally, Open by default, Add link lets a person switch the link on by hand.

The tests for the tools run with `cd site && python3 -m unittest -v test_assetlinks` (CI runs them).
