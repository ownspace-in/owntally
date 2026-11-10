# Releasing OwnTally (runbook for the owner)

Decision S1: **Google Play App Signing.** You make an *upload key* and keep it. Google holds the real *app signing key* and re-signs what you upload. Nothing secret is ever in the repository: the build reads the upload key from your own machine.

## 1. Make the upload key (once)

```
keytool -genkeypair -v -keystore ~/owntally-upload.jks -alias owntally-upload \
  -keyalg RSA -keysize 4096 -validity 9125
```

It asks for two passwords (the keystore's and the key's; the same one is fine) and a name. Keep the file and the passwords **outside the repository** (`.gitignore` also blocks `*.jks` and `*.keystore`).

**Always back up the keystore file and both passwords** in two places you control (a password manager and an offline copy). If the upload key is lost, Play can reset it, but only through a support request.

## 2. Tell Gradle where it is (once per machine)

Put these in `~/.gradle/gradle.properties` (never in the project), or set them as environment variables of the same names:

```
OWNTALLY_UPLOAD_STORE_FILE=/home/you/owntally-upload.jks
OWNTALLY_UPLOAD_STORE_PASSWORD=...
OWNTALLY_UPLOAD_KEY_ALIAS=owntally-upload
OWNTALLY_UPLOAD_KEY_PASSWORD=...
```

Without them the release build is simply not signed (that is how CI builds it).

## 3. Raise the version, then build

The version is in `app/app/version.properties`. **Raise `VERSION_CODE` for every upload**: Play refuses a code it has already seen. `VERSION_NAME` is what people see.

```
./gradlew :app:app:bundleRelease     # the App Bundle for Play: app/app/build/outputs/bundle/release/app-release.aab
./gradlew :app:app:assembleRelease   # a signed APK, to send to a friend as a file
```

Check the signature of what you built: `keytool -printcert -jarfile app/app/build/outputs/apk/release/app-release.apk` (an APK) shows the upload key's SHA-256.

## 4. First upload to Play

1. In the Play Console create the app, then **Setup, App signing**: choose Play App Signing (the default for new apps).
2. Upload the `.aab` to the **internal test track** first and install it from there on a real phone.
3. The listing, the data-safety form and the privacy page address come from `docs/STORE_LISTING_DRAFT.md` (the owner fills in what it marks TO FILL IN).

## 5. The two fingerprints for the invite link (`assetlinks.json`)

Android opens an invite link in the app only if the site's `assetlinks.json` names the certificate the *installed* app is signed with. That is **one of two certificates**, so the file lists **two fingerprints**:

1. **Play's app signing key**: Play Console, Setup, App signing, "SHA-256 certificate fingerprint" of the *app signing key certificate*. This is what a phone that installs from Play has.
2. **Your upload key**: `keytool -list -v -keystore ~/owntally-upload.jks -alias owntally-upload`, the `SHA256:` line. This is what a friend has who installs the APK you sent as a file.

```
python3 site/assetlinks.py make \
  --fingerprint <Play app signing key SHA-256> \
  --fingerprint <upload key SHA-256> \
  --out site/.well-known/assetlinks.json
python3 site/assetlinks.py check --file site/.well-known/assetlinks.json \
  --fingerprint <Play app signing key SHA-256> --fingerprint <upload key SHA-256>
```

Deploy the site (see `site/README.md`), then check from outside with `check --url https://joinowntally.ownspace.in/.well-known/assetlinks.json`, and on a phone `adb shell pm get-app-links in.ownspace.owntally` must say `verified`.

## 6. The release gate

Before the last upload run:

```
OWNTALLY_RELEASE=1 ./gradlew :app:app:testDebugUnitTest --tests '*ReleaseGateTest'
```

It fails while the operator's name or contact in the privacy text, the store draft's TO FILL IN, or the version are not final, or if a real `assetlinks.json` is still a template or does not hold two fingerprints.

## Never

- Never commit the keystore, a password or a `keystore.properties`. `ReleaseSigningTest` fails if a key file, a private key or a signing password is tracked by git.
- Never sign a build you ship with the debug key.
