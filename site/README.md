# Invite site (joinowntally.ownspace.in)

Static files only. Nothing runs on the server and nothing is stored. The invite code is in the part of the link after `#`, which browsers never send to the server, and the nginx config turns access logging off for this site.

| File | Purpose |
|---|---|
| `index.html` | Short landing page at `/` |
| `j/index.html`, `j/invite.js` | The page behind `https://joinowntally.ownspace.in/j#<code>`. Shown only to people who do not have the app, or on a computer. With the app installed and App Links verified, Android opens the app directly. |
| `style.css` | Shared styles, light and dark |
| `nginx/joinowntally.ownspace.in.conf` | nginx config for the VM |
| `.well-known/assetlinks.json` | **Not in the repo yet.** See below. |

## Deploy

```
sudo mkdir -p /var/www/joinowntally
rsync -av --delete --exclude nginx --exclude README.md site/ <user>@<vm>:/tmp/joinowntally/
ssh <user>@<vm> 'sudo rsync -av /tmp/joinowntally/ /var/www/joinowntally/'
```

Certificates and nginx setup are in `relay/ws-relay/RUNBOOK.md` (step 4). The same nginx serves this site and the relay.

## assetlinks.json (needed for "tap opens the app")

Create `/var/www/joinowntally/.well-known/assetlinks.json` once the app is built and signed. It needs the final application id and the SHA-256 fingerprint of the release signing certificate (and of the Play app signing certificate, if the app is distributed through the Play Store):

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "<application id>",
    "sha256_cert_fingerprints": ["<SHA-256 fingerprint>"]
  }
}]
```

Check it with `curl -i https://joinowntally.ownspace.in/.well-known/assetlinks.json`: it must return 200, `Content-Type: application/json`, and no redirect. The app also has to declare the link in its manifest with `android:autoVerify="true"` (a later milestone).
