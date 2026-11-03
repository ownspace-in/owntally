# M3 tasks: storage and app shell (local only)

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M3. Rules: [DESIGN.md](../DESIGN.md), [PROTOCOL.md](../PROTOCOL.md), [WIRE_FORMAT.md](../WIRE_FORMAT.md), [CRYPTO.md](../CRYPTO.md), mockups in [design/README.md](../../design/README.md). Tests first. No network code in this milestone. No new cryptography: Tink (already chosen) and the Android Keystore only.

Goal (BUILD_PLAN): a user can create a group, add and delete expenses and see correct balances, fully offline, and the data survives an app restart. Screens: Welcome, Recovery phrase (show, then confirm, cannot be skipped), Groups, Group, Add expense, Settle up (computed plan, buttons inert), How it works. Optional app lock. Screenshots blocked on the phrase screens.

This file is a draft: **nothing below is built until the owner approves it** (section "Decisions"). The owner's answers of 3 Nov 2026 are recorded below.

## Progress

| Task | Status |
|---|---|
| T3.1 plan and decisions | Drafted. Owner answers recorded (application id, test members, app lock, bottom bar). Still open: approval of the PIN design in T3.11, the defaults, and what the Settle tab shows. |
| T3.2 Android build in the root project | Done (4 Nov 2026), see below. |
| T3.3 money and text formatting | Done (4 Nov 2026), see below. |
| T3.4 Keystore and secret storage | Done (4 Nov 2026), see below. |
| T3.5 Room database and repositories | Done (4 Nov 2026), see below. |
| T3.6 App state from the log | Done (4 Nov 2026), see below. |
| T3.7 First run: Welcome and recovery phrase | Done (4 Nov 2026), see below. |
| T3.8 to T3.12 | Not started. |

## Decisions

Decided by the BUILD_PLAN or DESIGN already: Kotlin, Jetpack Compose with Material 3, minSdk 26, Room, the Android Keystore for keys, optional app lock, screenshots blocked on the phrase screen, no network code in M3.

**Answered by the owner on 3 Nov 2026:**
1. **Application id: `in.ownspace.owntally`.** Permanent once published; the invite site's `assetlinks.json` is tied to it.
2. **Test members: debug builds only, groups limited to three members until M4.** A debug-only menu item adds pretend members (real keys, real `MemberJoined` events written by the owner), at most two, so a group has at most three members. Release builds contain no such code and no way to add members until M4 and M6.
3. **App lock: support all three:** off, the phone's own biometric or screen lock, or our own PIN (details in T3.11).
4. **Bottom bar: all four tabs as in the mockup** (Groups, Settle, Sync, About). The Sync tab is shown but disabled: tapping it opens a short placeholder ("Syncing with friends arrives in a later update. For now your data is stored only on this phone.") and nothing else. It becomes the real sync status screen (mockup 05) in M5 to M7.

**Still open:** approval of the PIN design in T3.11, the defaults above, and what the Settle tab shows. The builder proposes: the Settle tab lists the groups that have payments to make (group name and what you owe or are owed), and tapping one opens that group's Settle up screen (mockup 04); with no payments it says everyone is settled up.

**Defaults the builder proposes (change any of them):**
1. **Dependency wiring by hand**, no Hilt. The app is small; Hilt adds annotation processing and build time for little gain. (BUILD_PLAN allows either.)
2. **Navigation:** Jetpack Navigation for Compose, one `ViewModel` per screen, state as plain Kotlin data classes so it can be unit tested on the JVM.
3. **Where things live** (BUILD_PLAN layout, using the names already in the repo): `app/data` (Room, repositories, Keystore wrapping), `app/ui` (Compose screens and view models), `app/app` (the application, manifest, navigation). `core-model` and `crypto` stay pure JVM. The old `spike/` build stays separate and is not shipped.
4. **What is stored.** One table of events per group: the **envelope bytes exactly as sent** (already encrypted and signed, docs/WIRE_FORMAT.md), plus author and counter for lookup. State is rebuilt by decrypting and replaying; it is never written to disk decrypted. Room tables: `groups` (group id, key slot, creation time), `events`, `settings`. **The master seed and the epoch keys are not in Room:** they are small sealed files in the app's `noBackupFilesDir` (changed in T3.4: the seed is written before any group or database exists, and a damaged database must not take the keys with it).
5. **Keys.** A non-exportable AES key in the Android Keystore wraps everything secret that must survive a restart (the master seed and each epoch's group key), through Tink's Keystore support. The recovery phrase itself is never stored. Key slots: each group records its slot (this closes the open item from M2), and slots come from a counter in the settings that only goes up, so a slot is never handed out twice, even after its group is deleted (a reused slot would give a new group the same member key as the old one, and anyone who was in both could link them).
6. **No cloud backup:** `allowBackup=false` and no data extraction, so keys and events are not copied off the phone.
7. **Money display:** rupees from paise, Indian digit grouping (₹1,50,000), never floating point.
8. **Mockups are the contract** for layout, wording and colours; differences are listed in the pull request.
9. **Tests without a phone:** unit tests on the JVM for view models, repositories (with an in-memory fake) and formatting; Room, Keystore wrapping and Compose screens are checked on the emulator (Android 36 image is installed). Screens are compared with the mockup images.

## T3.2 Android build in the root project

Do: add the Android Gradle plugin to the root build (same versions as the spike: AGP 8.9.2, Kotlin 2.2.0), create `app/data`, `app/ui`, `app/app`, make CI build them (the GitHub runner already has the Android SDK), keep `core-model` and `crypto` free of Android.

Done when: an empty app installs and starts on the emulator, `./gradlew test` and the debug build pass locally and in CI.

**Result (4 Nov 2026):** modules `app/app` (application id `in.ownspace.owntally`, code in `owntally.app` because `in` is a Kotlin keyword), `app/ui` (Compose, placeholder root screen) and `app/data` (empty until T3.4). AGP 8.9.2, Kotlin 2.2.0, **compileSdk and targetSdk 36**, minSdk 26 (Gradle installed the platform 36 package into the SDK on this machine). Compose BOM 2025.09.00 and activity-compose 1.11.0: the newest versions (BOM 2026.09.00) need compile API 37 and a newer Android Gradle plugin, so upgrading them is a separate task. Five rule tests (`ManifestRulesTest`) check that the manifest requests no permission, has no backup of any kind (`allowBackup`, `fullBackupContent`, data extraction rules excluding every domain for cloud backup and device transfer), exports only the launcher activity, has an icon and name, uses the permanent application id, and that the app modules contain no network code. Each rule was broken on purpose and the tests caught it (a first attempt did not, because Gradle did not know the tests read the manifest; the files are now declared as test inputs). Android Lint: 0 errors; the only warnings say newer Compose versions exist. CI has a new `android` job that builds, tests and lints. On the emulator (Android 16, API 36): installed, started in 3.9 s, no crash, screenshot shows the placeholder. The merged manifest has one entry that is not ours, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX adds for itself; it is not a permission asked of the user.

**Note for the screens (T3.7 onwards):** with target API 36 Android draws apps edge to edge, so the placeholder text sits behind the status bar. Every screen must handle window insets (for example `Scaffold` and `safeDrawingPadding`) and the emulator screenshots must show it.

## T3.3 Money and text formatting (pure Kotlin)

Do: format `Money` as in the mockups (₹3,200; Indian grouping; negative as "you owe"), parse what a person types ("2,400", "2400.50", more than two decimals rejected), dates as in "Today, 13 Oct".

Done when: tests cover grouping, rounding to paise, bad input, and every amount shown in the mockups.

**Result (4 Nov 2026):** in `core-model`, package `owntally.model.format` (plain Kotlin, no `Locale`, so a phone's settings cannot change a number). `MoneyFormat`: ₹2,400, Indian grouping (₹1,00,000, ₹1,00,00,000), paise only when there are some (₹2,400.50), negative as `-₹1,850`, signed as `+₹3,200` (zero has no sign), `describe` giving owed, owes or settled with an unsigned amount for sentences, `editable` for text fields (`2400.50`). `MoneyInput.parse`: accepts `2,400`, `2400.5`, `₹ 2,400.50`, `.5`, `2400.` (while typing); refuses a third decimal instead of rounding; reports bad characters (letters, signs, spaces inside, other-script digits), misplaced separators, and amounts above ₹100 crore; never throws; what `MoneyFormat` prints can always be typed back. `DateText`: "Today, 13 Oct", "Yesterday, 12 Oct", "9 Oct", "31 Dec 2025" for another year, and "Unknown date" (never a crash) for a day off the wire that is not a real date. Every amount drawn in all 22 mockups is in the tests (20 amounts, plus the signed before and after of the remove-member screen). The largest-number test uses a value worked out by hand. Six rules were broken on purpose and the tests caught each. 173 `core-model` tests. Not done here: relative times ("2 hrs ago", "expires in 5 hrs 10 min"); they belong to the milestones that show them (M4, M6).

## T3.4 Keystore and secret storage

Do: `SecretStore` that wraps and unwraps bytes with a Keystore-backed Tink AEAD; store the master seed and epoch keys.

Done when: the secret survives an app restart (emulator test); a wrong or deleted Keystore key fails safely; nothing secret appears in logs; `SecretStore` has a fake for JVM tests.

**Result (4 Nov 2026):** in `app/data`, package `owntally.data.secrets`. `SecretBox` seals bytes for one named slot (the context is bound in as associated data, so a blob cannot be moved between the seed slot and a group's epoch slots); `AeadSecretBox` is it on a Tink AEAD; `KeystoreSecretBox.create()` is the phone version, an AES-256-GCM key in the Android Keystore made through Tink's Keystore support, created on first use and not exportable. `BlobStore` (`FileBlobStore`: one file per blob, written to a temporary file, synced, then moved into place; names limited to letters, digits, `-` and `_` so no name can escape the folder; `InMemoryBlobStore` for tests). `SecretStore` / `BoxedSecretStore`: save and load the master seed and each group's epoch keys; every blob is a version byte plus the sealed secret. Loading answers `Found`, `Missing` or `Unreadable` (something is stored but cannot be opened: key lost or changed, damaged, moved to another slot, wrong version); a keyring load also lists the unreadable epochs and still returns the readable ones. Any failure while sealing becomes a `SecretStoreException` that carries only a class name, never a message (a test found that a box throwing a plain exception could leak its message; fixed). The fake for JVM tests is `BoxedSecretStore` on `InMemoryBlobStore` with a Tink AES-GCM key held in memory. **In production** the folder is `noBackupFilesDir/secrets` (to be wired in T3.7).

*On the emulator (Android 16, real Keystore, Tink's Android artifact):* 12 instrumented tests pass, covering: another instance opens what the first sealed; the key is not exportable (`encoded` is null), is AES-256 with GCM, and needs no unlock; a deleted Keystore key makes stored secrets `Unreadable` with no crash and a new seed can then be saved; another Keystore key cannot open them; and the crypto module's main operations run on Android and match the independent Python values (keys from the phrase, signing, sealing, HPKE, tickets). **Restart:** wrote the seed and two group keys in one process, stopped it, read them in a new process (OK), rebooted the emulator and read again (OK). To repeat: `./gradlew :app:data:installDebugAndroidTest`, then `adb shell am instrument -w -e restartPhase write -e class owntally.data.secrets.RestartInstrumentedTest#write owntally.data.test/androidx.test.runner.AndroidJUnitRunner`, then `adb shell am force-stop owntally.data.test`, then the same with `read` (and `cleanup` at the end). The normal full run, `./gradlew :app:data:connectedDebugAndroidTest`, skips those three.

*JVM:* 27 tests in `app/data` (every protection was broken on purpose and a test caught it; one, the version byte, was first missed and got a better test). The Android modules have a new test that fails if they log or print anything. Lint: no errors.

*Tink on Android:* `app/crypto` keeps Tink's JVM artifact for its own tests; the Android modules exclude it and use `tink-android` (same classes plus the Keystore support), so the APK has one copy (the build would fail on a duplicate class). Debug APK size: 15.24 MB at the end of T3.2, 15.61 MB now with the crypto, core and data modules included (unshrunk; the release size comes with R8 in M10).

## T3.5 Room database and repositories

Do: schema and DAOs as in decision 4, `GroupRepository` (create group, list groups, append an event envelope, read all envelopes of a group), `IdentityRepository` (first run, slot allocation).

Done when: JVM tests with an in-memory fake cover the repository rules; emulator tests cover the real database, including migration from an empty file and data surviving a restart.

**Result (4 Nov 2026):** Room 2.8.4 with KSP 2.2.0-2.0.2 (the schema is exported to `app/data/schemas` and committed; there is deliberately no destructive migration fallback, so a missing migration is an error and never a silent loss of events). Tables `groups` (id, key slot unique, created), `events` (the envelope bytes exactly as sent, author, counter, a SHA-256 of the envelope; unique on group, author, counter and hash, so the same bytes are stored once but two different envelopes with the same id are both kept for the protocol to see; cascade delete with the group) and `settings`. Layers: `EventStore` and `SettingsStore` (persistence only; Room versions and in-memory fakes with the same behaviour), `IdentityRepository` (first run: the profile is saved first and the seed last, because the seed's presence counts as "done"; the phrase is never stored; one first run only; default profile; keys per slot; slot reservation), `GroupRepository` (`createGroup`: validates the name first, reserves a slot, makes the group key, seals `GroupCreated` and `ProfileUpdated`, saves the key, then stores group and events in one transaction, removing the key again if that fails; `appendEnvelope`: refuses an unknown group, another group's envelope, more than 64 KB, or a bad signature, and answers added or duplicate; `envelopes` reads them back and counts damaged entries; `deleteGroup` removes events first and keys second). What was found while writing the tests: a slot allocated as "highest plus one" would be reused after the highest group is deleted, giving a new group the same member key as the deleted one; slots now come from a counter that only goes up.

*JVM:* 60 tests in `app/data` (33 new); five protections were broken on purpose and each was caught. *Emulator (Android 16, real Room and Keystore):* 29 instrumented tests pass: the Room stores behave like the fakes (atomic group creation, refused ids and slots, identical bytes stored once, different bytes with the same id both kept, order, cascade delete, settings); a database is made from nothing and its data is there after closing and reopening; a version 1 file built from the exported schema (not from the code) opens and validates against the current code; foreign keys are on; and the real stack (Room file, Keystore, secret files) creates a group, adds expenses, is opened again and replays to the same state, and with a lost Keystore key the groups stay but their events cannot be opened. **Restart, for real:** first run, a group and two expenses written in one process, the process stopped, read and replayed in a new process (OK), the emulator rebooted, read again (OK). To repeat: `./gradlew :app:data:installDebugAndroidTest`, then `adb shell am instrument -w -e restartPhase write -e class owntally.data.repo.FullStackInstrumentedTest#restartPhaseWrite owntally.data.test/androidx.test.runner.AndroidJUnitRunner`, then `adb shell am force-stop owntally.data.test`, then `read` (`#restartPhaseRead`), and `cleanup` (`#restartPhaseCleanup`).

Lint: no errors (a newer Room patch version exists). Not done here: sealing and adding the local user's later events (T3.6); the production wiring of the folders and the database (T3.7).

## T3.6 App state from the log

Do: a `GroupSession` that decrypts a group's envelopes with its keyring, replays them with `core-model`, and exposes the state, balances and settlement plan as a flow; adds new events (seal, sign, store) for the local user.

Done when: tests show that adding and deleting expenses gives the balances of `core-model`; envelopes that fail to open are kept but not shown; replaying after a restart gives the same state.

**Result (4 Nov 2026):** `GroupSession` in `app/data` (package `owntally.data.session`; no Android code). It opens a group's stored envelopes with the group's keys, replays them with `core-model`, and publishes a `StateFlow<GroupView>` holding the state, this phone's member key, the balances, the settlement plan, and counts of what could not be read. It keeps the opened events in memory and only opens envelopes it has not seen, so adding one event does not re-decrypt everything; `refresh()` picks up newly stored envelopes and newly arrived keys. **New events by the local user** (`append`, `addExpense`, `deleteExpense`) are first put through the core's replay rules on a copy of the log: if the rules would ignore the event (a member who does not exist, deleting something that is not there, deleting twice, a plain member's admin-only action) nothing is sealed or stored and `Refused(reason)` is returned, so the core and not the UI decides; otherwise it is sealed with the current epoch's key, signed, stored, and the new state published once. It uses the next counter and `after` from the opened log. If the key for the current epoch is not on the phone, nothing can be written (`NoKey`). **Envelopes that cannot be opened stay stored and are never shown:** those waiting for an epoch key (listed by epoch, opened when the key arrives), those that do not open with the key held, and stored bytes that cannot be decoded are counted separately, and counted once, not again on every refresh. Cases covered by tests: balances from adding and deleting expenses equal the core's (a hand-calculated example: Hotel 900 and Taxi 600 among three gives +400, +100, -500 and two payments); the view equals what the core computes from the stored events; deleting keeps the entry in the history; a new session over the same storage shows an equal view and continues the counters; after a key rotation new events use the new key only and nothing is written if the new epoch's key is missing even though the old key is there; 24 expenses added from 8 threads get distinct counters and all land.

*JVM:* 83 tests in `app/data` (23 new). Seven protections were broken on purpose; one (always using the epoch 1 key) was first missed because no test wrote after an epoch change, and got two tests. *Emulator:* the 29 instrumented tests pass with the session on the real stack (Room, Keystore, files). The restart test now has real numbers: first run, a group, a friend and two expenses in one process; the process stopped; a new process reads and replays them (Asha +300, Ben -300, one payment of 300 from Ben to Asha) with no ignored event; and the same after rebooting the emulator. Lint: no errors. Not done here: settlements, member changes and key rotation as actions (M4), receiving events from other phones (M5); the debug-only test members are a screen feature (T3.8 onward).

## T3.7 First run: Welcome and recovery phrase

Do: Welcome (name and phone), phrase screen (12 numbered words, screenshots blocked), confirm screen (asks for 3 words, cannot be skipped), then the groups list. The seed is created, wrapped and stored; the phrase is dropped from memory.

Done when: tests cover the confirm rules (wrong word, right words, cannot continue without confirming); the screens have the screenshot block set (checked on the emulator); the phrase is not in logs, saved state or the database.

**Result (4 Nov 2026):** *Screens* (`app/ui`, theme colours read from the mockups: background #F3F5F1, green #0E6B5C, ink #10231F, amber #FFF1D0, mint #7FE0C4, red #B4412B): Welcome (design/07), the phrase (08) and "Check you wrote it down" (08b), then the Groups placeholder (T3.8 replaces it). Edge to edge with window insets handled, and the keyboard's Next key moves from the name to the phone field. *Flow* (`OnboardingViewModel`, a plain state machine on an androidx ViewModel, so it survives rotation and is tested on the JVM): details are checked with the same limits as the events they end up in; the phrase is made once (going back and forward shows the same one); the only way to finish is typing the three asked words correctly (case and spaces ignored), and only then are the profile and then the seed saved; a wrong word saves nothing; a save failure keeps the person on the confirm screen to retry; a double tap saves once; afterwards the phrase and answers are dropped. The phrase is held only as a `RecoveryPhrase` and the typed answers as a redacted wrapper, so even a printed state shows no word, and a source scan fails if the UI module uses saved state, preferences or logging. The confirm fields use a password-type keyboard (no suggestions, text stays visible). *Real stack:* `AppContainer` builds the Keystore, files and Room by hand; `MainActivity` shows a blank start, the first run, the placeholder, or a plain message if the saved seed cannot be opened or secure storage is unavailable.

**What was found:** the first picture of the phrase screen showed words cut off ("aband" for "abandon"). A truncated recovery word would make someone write down the wrong word, so words now shrink to fit their chip and are never clipped; with large text sizes the screen uses two wider columns instead of three. A test renders the 12 longest words out of 4,000 phrases at text sizes 1.0, 1.3 and 2.0 and fails if any word is clipped or wrapped (it fails on the old code).

*JVM:* 25 tests in `app/ui` (the rules, and the whole state machine including that the steps cannot be skipped). *Emulator:* 15 UI tests (each screen's text; the secure window flag is set on exactly the phrase and confirm screens and lifted when leaving them or finishing; wrong words refused; right words save; "Show the words again" shows the same phrase; back never skips ahead; long words fit) and **an end-to-end test of the real app** (real activity, Keystore, secret files and Room): the whole first run through the screens, the window really secure while the words are shown, nothing saved after wrong words, everything saved after the right ones, and then every file the app owns searched for the phrase in the ways it could be written (it finds none, and finds a planted one, so the scan works; with the phrase deliberately also saved in the database the test fails, and with the secure window removed it fails). Also by hand on the emulator: Android's own screen capture of the phrase screen comes back completely black (15 KB against about 150 KB), and after killing the app's process on the phrase screen it restarts at the welcome screen with empty fields and nothing of the phrase (so it is not in saved state). Lint: no errors.

**Differences from the mockups** (for the owner): the font is the system font, not the mockups' typeface (bundling one is a later task); a disabled "Finish" is shown paler until all three words are typed; the confirm step can ask for any three of the 12 positions. Not done here: the optional app lock step (T3.11) and restoring from a phrase (M6). Note: the emulator is slow and sometimes shows Android system "isn't responding" dialogs (they were in Google's own processes, not the app); a cold boot clears them.

## T3.8 Groups and Group screens

Do: Groups (totals card, group cards, empty state), Group (balance card, expense list with deleted entries struck through, total spent), create group (name, colour), as in the mockups.

The bottom bar has all four tabs (Groups, Settle, Sync, About). Sync opens the placeholder from the decisions above; Settle lists groups with payments to make.

Done when: view model tests for the numbers and for the bottom bar (four tabs, Sync shows the placeholder and does nothing else); emulator screenshots compared with the mockups.

## T3.9 Add expense and delete

Do: Add expense (amount, description, paid by, date, equal or exact split with live shares), delete (creator or admin, with the "this changes balances" warning from DESIGN.md).

Done when: tests for the rounding shown to the user matching `core-model`, exact splits that do not add up being blocked, and delete permissions; entries cannot be edited.

## T3.10 Settle up and How it works

Do: Settle up shows the computed plan; buttons are present but do nothing yet (BUILD_PLAN). How it works as in the mockup.

Done when: the plan on screen equals `settlementPlan` for the group.

## T3.11 App lock (optional)

Off by default. A setting chooses one of three modes; the app locks when it goes to the background (after a short grace period, default 30 seconds, so switching to a UPI app and back is not annoying).

1. **Phone lock:** Android's own prompt (`BiometricPrompt`) accepting a strong biometric or the phone's PIN, pattern or password. We store nothing and write no lockout rules.
2. **Our own PIN, 4 to 6 digits.** This is security-sensitive code of our own, so its design needs the owner's approval before it is built:
   - The PIN is never stored. We store a salted hash made with PBKDF2-HMAC-SHA256 from the JDK (210,000 rounds, random 16-byte salt), then wrapped with the Keystore AEAD like the other secrets. No custom hashing.
   - Wrong tries are counted and stored: after 5 in a row the lock waits 30 seconds, doubling each further failure (up to 1 hour). The data is never wiped by wrong PINs.
   - Changing or turning off the PIN needs the current PIN. Forgotten PIN: no reset by us; the person can reinstall and restore from the recovery phrase (the data is re-synced from friends).
   - Comparison in constant time. No PIN in logs, saved state or screenshots; the PIN screen blocks screenshots like the phrase screen.
3. **Off.**

Done when: tests on the JVM for the lock state machine (locked, unlocked, grace period, background), for the PIN rules (hash and compare, wrong-try counting, waiting times, persisted counter, constant-time compare) with a fake clock; the real prompt and Keystore are checked on the emulator.

## T3.12 Hand back

Checklist: `./gradlew test` and the debug build pass locally and in CI; the data survives an app restart on the emulator (create a group, add and delete expenses, kill and reopen the app); balances are correct offline; no network permission in the manifest except what later milestones add (none now); no secret in logs; docs match; interpretations listed in the pull request.
