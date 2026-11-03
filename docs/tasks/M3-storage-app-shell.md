# M3 tasks: storage and app shell (local only)

Parent: [BUILD_PLAN.md](../BUILD_PLAN.md), milestone M3. Rules: [DESIGN.md](../DESIGN.md), [PROTOCOL.md](../PROTOCOL.md), [WIRE_FORMAT.md](../WIRE_FORMAT.md), [CRYPTO.md](../CRYPTO.md), mockups in [design/README.md](../../design/README.md). Tests first. No network code in this milestone. No new cryptography: Tink (already chosen) and the Android Keystore only.

Goal (BUILD_PLAN): a user can create a group, add and delete expenses and see correct balances, fully offline, and the data survives an app restart. Screens: Welcome, Recovery phrase (show, then confirm, cannot be skipped), Groups, Group, Add expense, Settle up (computed plan, buttons inert), How it works. Optional app lock. Screenshots blocked on the phrase screens.

This file is a draft: **nothing below is built until the owner approves it** (section "Decisions"). The owner's answers of 3 Nov 2026 are recorded below.

## Progress

| Task | Status |
|---|---|
| T3.1 plan and decisions | Drafted. Owner answers recorded (application id, test members, app lock, bottom bar). Still open: approval of the PIN design in T3.11, the defaults, and what the Settle tab shows. |
| T3.2 to T3.12 | Not started. |

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
4. **What is stored.** One table of events per group: the **envelope bytes exactly as sent** (already encrypted and signed, docs/WIRE_FORMAT.md), plus author and counter for lookup. State is rebuilt by decrypting and replaying; it is never written to disk decrypted. Tables: `groups` (group id, key slot, creation time), `events`, `epoch_keys` (epoch to group key, each wrapped), `settings`.
5. **Keys.** A non-exportable AES key in the Android Keystore wraps everything secret that must survive a restart (the master seed and each epoch's group key), through Tink's Keystore support. The recovery phrase itself is never stored. Key slots: the next unused slot is recorded per group (this closes the open item from M2).
6. **No cloud backup:** `allowBackup=false` and no data extraction, so keys and events are not copied off the phone.
7. **Money display:** rupees from paise, Indian digit grouping (₹1,50,000), never floating point.
8. **Mockups are the contract** for layout, wording and colours; differences are listed in the pull request.
9. **Tests without a phone:** unit tests on the JVM for view models, repositories (with an in-memory fake) and formatting; Room, Keystore wrapping and Compose screens are checked on the emulator (Android 36 image is installed). Screens are compared with the mockup images.

## T3.2 Android build in the root project

Do: add the Android Gradle plugin to the root build (same versions as the spike: AGP 8.9.2, Kotlin 2.2.0), create `app/data`, `app/ui`, `app/app`, make CI build them (the GitHub runner already has the Android SDK), keep `core-model` and `crypto` free of Android.

Done when: an empty app installs and starts on the emulator, `./gradlew test` and the debug build pass locally and in CI.

## T3.3 Money and text formatting (pure Kotlin)

Do: format `Money` as in the mockups (₹3,200; Indian grouping; negative as "you owe"), parse what a person types ("2,400", "2400.50", more than two decimals rejected), dates as in "Today, 13 Oct".

Done when: tests cover grouping, rounding to paise, bad input, and every amount shown in the mockups.

## T3.4 Keystore and secret storage

Do: `SecretStore` that wraps and unwraps bytes with a Keystore-backed Tink AEAD; store the master seed and epoch keys.

Done when: the secret survives an app restart (emulator test); a wrong or deleted Keystore key fails safely; nothing secret appears in logs; `SecretStore` has a fake for JVM tests.

## T3.5 Room database and repositories

Do: schema and DAOs as in decision 4, `GroupRepository` (create group, list groups, append an event envelope, read all envelopes of a group), `IdentityRepository` (first run, slot allocation).

Done when: JVM tests with an in-memory fake cover the repository rules; emulator tests cover the real database, including migration from an empty file and data surviving a restart.

## T3.6 App state from the log

Do: a `GroupSession` that decrypts a group's envelopes with its keyring, replays them with `core-model`, and exposes the state, balances and settlement plan as a flow; adds new events (seal, sign, store) for the local user.

Done when: tests show that adding and deleting expenses gives the balances of `core-model`; envelopes that fail to open are kept but not shown; replaying after a restart gives the same state.

## T3.7 First run: Welcome and recovery phrase

Do: Welcome (name and phone), phrase screen (12 numbered words, screenshots blocked), confirm screen (asks for 3 words, cannot be skipped), then the groups list. The seed is created, wrapped and stored; the phrase is dropped from memory.

Done when: tests cover the confirm rules (wrong word, right words, cannot continue without confirming); the screens have the screenshot block set (checked on the emulator); the phrase is not in logs, saved state or the database.

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
