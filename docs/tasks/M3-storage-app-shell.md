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

Decided by the BUILD_PLAN or DESIGN already: Kotlin, Jetpack Compose with Material 3, minSdk 30 (changed 4 Nov 2026, see decision 17), Room, the Android Keystore for keys, optional app lock, screenshots blocked on the phrase screen, no network code in M3.

**Answered by the owner on 3 Nov 2026:**
1. **Application id: `in.ownspace.owntally`.** Permanent once published; the invite site's `assetlinks.json` is tied to it.
2. **Test members: debug builds only, groups limited to three members until M4.** A debug-only menu item adds pretend members (real keys, real `MemberJoined` events written by the owner), at most two, so a group has at most three members. Release builds contain no such code and no way to add members until M4 and M6.
3. **App lock: off or the phone's own fingerprint or screen lock.** The own PIN was dropped on 4 Nov 2026 (see below).
4. **Bottom bar: all four tabs as in the mockup** (Groups, Settle, Sync, About). The Sync tab is shown but disabled: tapping it opens a short placeholder ("Syncing with friends arrives in a later update. For now your data is stored only on this phone.") and nothing else. It becomes the real sync status screen (mockup 05) in M5 to M7.

**Answered by the owner on 4 Nov 2026** (each question asked with no recommended option):
5. **App lock:** only Off and the phone lock (no own PIN). Locks 30 seconds after the app leaves the screen, and at once when the phone locks (item 16); always asks on a fresh start. (Screenshots blocked while on: dropped, item 13.) Turning it on or off asks for the phone lock first.
6. **Settings:** a separate Settings screen opened from the About tab.
7. **Settle tab:** lists every group that can be opened, settled or not; each opens that group's Settle up.
8. **Settle up buttons:** Pay opens the share sheet with who, how much and the number. I paid, Send reminder and Mark received stay switched off until payments can be recorded.
9. **Dependency wiring:** by hand in one file (as built).
10. **Navigation:** our own small screen stack, not Jetpack Navigation (changed from the default).
11. **Android backup:** on for the database only (changed from the default). Keys are not in it, so a restored database shows its groups as "waiting for the group key".
12. **Number style:** follows the phone's language and region (changed from the default). Confirmed again on 4 Nov 2026.

**Second round, 4 Nov 2026** (the owner changed or confirmed these while reviewing the finished milestone):
13. **Screenshots are allowed on every screen, the recovery phrase included.** The secure-window code was removed. DESIGN.md is changed.
14. **Delete warning "This changes balances"** shows only when a payment marked paid or received involves someone on that expense (a disputed payment does not count).
15. **Backup contents:** kept as is (database including the settings table).
16. **App lock:** locks at once when the phone locks (its screen goes off), and opens again with the phone's fingerprint or screen lock. If the phone has no screen lock any more the app cannot ask and does not (as built; the owner's answer did not cover this case, so it is still as built).
17. **Minimum Android is 11 (API 30).** Android 10 and older are not supported; the biometric code no longer has older-version branches.
18. **No reference to UPI anywhere, and the app opens no other app.** So the Pay share sheet was removed: all four Settle up buttons are switched off. How it works card 4 and the Settle up intro say "pay however you like, outside the app". Card 3 (joining and syncing) is kept as is.
20. **No screen lock on the phone while the app lock is on:** the app stays locked until a screen lock is set again (changed from "stop asking"; item 16 is amended). The lock screen says what to do and has "Check again". Nothing is lost meanwhile: our keys are not tied to the screen lock.
21. **Cover when not in front:** the app draws a plain cover whenever it is not in front, and on Android 13 and newer asks Android not to keep a picture of it in the recent-apps list. Screenshots still work normally.
19. **Debug test-data button:** stays in debug builds only; both debug and release builds must build (they do).

**Defaults the builder proposes (change any of them):**
1. **Dependency wiring by hand**, no Hilt. The app is small; Hilt adds annotation processing and build time for little gain. (BUILD_PLAN allows either.)
2. **Navigation:** ~~Jetpack Navigation~~ (replaced by the owner's choice, item 10: `NavStack`, a list of plain `Screen` values), state as plain Kotlin data classes so it can be unit tested on the JVM.
3. **Where things live** (BUILD_PLAN layout, using the names already in the repo): `app/data` (Room, repositories, Keystore wrapping), `app/ui` (Compose screens and view models), `app/app` (the application, manifest, navigation). `core-model` and `crypto` stay pure JVM. The old `spike/` build stays separate and is not shipped.
4. **What is stored.** One table of events per group: the **envelope bytes exactly as sent** (already encrypted and signed, docs/WIRE_FORMAT.md), plus author and counter for lookup. State is rebuilt by decrypting and replaying; it is never written to disk decrypted. Room tables: `groups` (group id, key slot, creation time), `events`, `settings`. **The master seed and the epoch keys are not in Room:** they are small sealed files in the app's `noBackupFilesDir` (changed in T3.4: the seed is written before any group or database exists, and a damaged database must not take the keys with it).
5. **Keys.** A non-exportable AES key in the Android Keystore wraps everything secret that must survive a restart (the master seed and each epoch's group key), through Tink's Keystore support. The recovery phrase itself is never stored. Key slots: each group records its slot (this closes the open item from M2), and slots come from a counter in the settings that only goes up, so a slot is never handed out twice, even after its group is deleted (a reused slot would give a new group the same member key as the old one, and anyone who was in both could link them).
6. ~~No cloud backup~~ (changed by the owner, item 11): the database only is backed up; the keys never are.
7. **Money display:** rupees from paise, never floating point. ~~Indian digit grouping~~ now follows the phone (owner, item 12): lakh grouping (₹1,50,000) in India and Hindi, thousands elsewhere, the phone's decimal sign, always digits 0 to 9.
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

Do: Welcome (name and phone), phrase screen (12 numbered words; screenshots were blocked here at first, allowed since 4 Nov 2026, decision 13), confirm screen (asks for 3 words, cannot be skipped), then the groups list. The seed is created, wrapped and stored; the phrase is dropped from memory.

Done when: tests cover the confirm rules (wrong word, right words, cannot continue without confirming); the screens have the screenshot block set (checked on the emulator); the phrase is not in logs, saved state or the database.

**Result (4 Nov 2026):** *Screens* (`app/ui`, theme colours read from the mockups: background #F3F5F1, green #0E6B5C, ink #10231F, amber #FFF1D0, mint #7FE0C4, red #B4412B): Welcome (design/07), the phrase (08) and "Check you wrote it down" (08b), then the Groups placeholder (T3.8 replaces it). Edge to edge with window insets handled, and the keyboard's Next key moves from the name to the phone field. *Flow* (`OnboardingViewModel`, a plain state machine on an androidx ViewModel, so it survives rotation and is tested on the JVM): details are checked with the same limits as the events they end up in; the phrase is made once (going back and forward shows the same one); the only way to finish is typing the three asked words correctly (case and spaces ignored), and only then are the profile and then the seed saved; a wrong word saves nothing; a save failure keeps the person on the confirm screen to retry; a double tap saves once; afterwards the phrase and answers are dropped. The phrase is held only as a `RecoveryPhrase` and the typed answers as a redacted wrapper, so even a printed state shows no word, and a source scan fails if the UI module uses saved state, preferences or logging. The confirm fields use a password-type keyboard (no suggestions, text stays visible). *Real stack:* `AppContainer` builds the Keystore, files and Room by hand; `MainActivity` shows a blank start, the first run, the placeholder, or a plain message if the saved seed cannot be opened or secure storage is unavailable.

**What was found:** the first picture of the phrase screen showed words cut off ("aband" for "abandon"). A truncated recovery word would make someone write down the wrong word, so words now shrink to fit their chip and are never clipped; with large text sizes the screen uses two wider columns instead of three. A test renders the 12 longest words out of 4,000 phrases at text sizes 1.0, 1.3 and 2.0 and fails if any word is clipped or wrapped (it fails on the old code).

*JVM:* 25 tests in `app/ui` (the rules, and the whole state machine including that the steps cannot be skipped). *Emulator:* 15 UI tests (each screen's text; the secure window flag is set on exactly the phrase and confirm screens and lifted when leaving them or finishing; wrong words refused; right words save; "Show the words again" shows the same phrase; back never skips ahead; long words fit) and **an end-to-end test of the real app** (real activity, Keystore, secret files and Room): the whole first run through the screens, the window really secure while the words are shown, nothing saved after wrong words, everything saved after the right ones, and then every file the app owns searched for the phrase in the ways it could be written (it finds none, and finds a planted one, so the scan works; with the phrase deliberately also saved in the database the test fails, and with the secure window removed it fails). Also by hand on the emulator: Android's own screen capture of the phrase screen comes back completely black (15 KB against about 150 KB), and after killing the app's process on the phrase screen it restarts at the welcome screen with empty fields and nothing of the phrase (so it is not in saved state). Lint: no errors.

**Differences from the mockups** (for the owner): the font is the system font, not the mockups' typeface (bundling one is a later task); a disabled "Finish" is shown paler until all three words are typed; the confirm step can ask for any three of the 12 positions. Not done here: the optional app lock step (T3.11) and restoring from a phrase (M6). Note: the emulator is slow and sometimes shows Android system "isn't responding" dialogs (they were in Google's own processes, not the app); a cold boot clears them.

## T3.8 Groups and Group screens

Do: Groups (totals card, group cards, empty state), Group (balance card, expense list with deleted entries struck through, total spent), create group (name, colour), as in the mockups.

The bottom bar has all four tabs (Groups, Settle, Sync, About). Sync opens the placeholder from the decisions above; Settle lists groups with payments to make.

Done when: view model tests for the numbers and for the bottom bar (four tabs, Sync shows the placeholder and does nothing else); emulator screenshots compared with the mockups.

### T3.8 result

Done on the emulator (Android 16). Tests: core-model 181 on the JVM, plus new data and ui JVM tests (all pass); instrumented tests for the screens and the real-app first run, which now also makes a group, opens it and checks the four tabs.

- Core: `sharesOf` and `totalSpent` (deleted expenses are not counted), `GroupId.fromHexOrNull`.
- Data: `Sessions` (one `GroupSession` per group, a summaries flow, create and delete group, the group colour kept as a local setting). Its constructor does no I/O; `refresh()` reads the database and must be called off the main thread. The end-to-end test found this: the first version read the database in the constructor and crashed on the main thread.
- UI: models in plain Kotlin (`GroupsModels.kt`) with the texts under test: totals, "N members · M expenses", role text, "X owes", "X is owed", settled names joined, "you lent / you owe", deleted rows. Compose screens, four-tab bottom bar, Jetpack Navigation, icons drawn in code (no icon library).
- Debug builds only: "Add test friends and expenses" on the Group screen (two "(test)" friends, three expenses, slots from 2,000,000 up, so at most 3 members). The release build has a stub that returns null, so the control and code are absent.
- Screens were compared with design/01 and 02 (`10-groups`, `11-group`, `12-create-group` from `ScreenImagesInstrumentedTest`).

Deviations from the mockups, each because a control would do nothing yet (no dead controls):
- No "Join with link or code", sync button, "not yet shared" notice, members/invite icons or "Settle up the group" button: they arrive with M4 and T3.10. The Groups screen has a "New group" button where the mockup has "Add expense" (that button arrives in T3.9).
- Cards say "members", not "friends/flatmates/players", because the app does not know what a group is.
- Expense icons show the first letter, not a picture per category.
- The Settle tab is my proposal, **awaiting the owner**: groups where you owe or are owed, each opening the group. Sync shows only the placeholder text. About is a short text until T3.10.
- Tab and form state is kept in a ViewModel, never in saved state (a test forbids `rememberSaveable`).
- Group name is limited to 40 characters (the core's rule); the icon colour is local to this phone.

## T3.9 Add expense and delete

Do: Add expense (amount, description, paid by, date, equal or exact split with live shares), delete (creator or admin, with the "this changes balances" warning from DESIGN.md).

Done when: tests for the rounding shown to the user matching `core-model`, exact splits that do not add up being blocked, and delete permissions; entries cannot be edited.

### T3.9 result

Add expense and delete work on the emulator (end-to-end test: save is blocked until the form is valid, the expense is listed, delete asks first, the row shows "Deleted by Asha · not counted"). All JVM tests, the release build and lint pass; every new rule was checked by breaking the code on purpose and seeing a test fail.

- **Shown shares equal what the core computes.** The form calls the core's `equalShares` with the counter the new event will get (`GroupSession.nextCounter()`), so the leftover paise are shown on the right people. Tests save an expense and compare the stored shares with the shown ones, for several expenses in a row (the start point rotates).
- **Exact splits** must add up to the total: Save stays off and the form says "₹300 left to assign" or "₹100 too much". Someone left blank does not take part. A bad amount names the person.
- **Limits:** description 1 to 80 characters (the core's rule), amount up to ₹100 crore with at most 2 decimals, the date cannot be in the future.
- **No editing.** The form says so; the row dialog says to delete and add again.
- **Delete:** only the creator or an admin sees the Delete button (the core's rule); a confirmation always appears first. "This changes balances" is added when a payment in the group already involves someone on that expense. **Owner to confirm this reading** of "warn if it changes settled amounts" in DESIGN.md: payments cannot be recorded until T3.10, so it cannot show yet.
- Wording: "split 1 way", and "no balance change" for an expense I paid and share fully.
- The form is kept in a ViewModel (survives turning the phone, never in saved state). Reading the counter touches the database, so the form is built off the main thread.
- Screenshot `13-add-expense` compared with design/03. Difference: the date wraps onto two lines between its arrows, and the date is changed with arrows, not a calendar.

## T3.10 Settle up and How it works

Do: Settle up shows the computed plan; buttons are present but do nothing yet (BUILD_PLAN). How it works as in the mockup.

Done when: the plan on screen equals `settlementPlan` for the group.

### T3.10 result

Settle up and How it works are done and checked on the emulator. JVM tests, the release build and lint pass; the end-to-end test adds the debug test friends, opens Settle up and checks both plan rows (You → Meera (test) ₹2,850, Ben (test) → Meera (test) ₹9,550), then opens How it works from About. Every rule in the new models was checked by breaking the code on purpose; two gaps were found that way and closed.

- **The plan on screen is the core's `settlementPlan`, row for row and in order**, with the amounts and the two people. A test also adds the rows up and checks they clear every balance.
- Rows are shown for the whole group, including payments between other people. Under each of my own rows the other person's phone number is shown as they entered it (no "+91" is added).
- **Buttons are present but switched off**, as BUILD_PLAN says (Pay / I paid when I owe, Send reminder / Mark received when I am owed). They are drawn greyed, announced as disabled to screen readers, and a note says recording payments arrives later. The test that checked this found they were not announced as disabled at first; fixed.
- Reached from "Settle up the group" on the Group screen (shown when there is someone else in the group) and from the Settle tab, which now opens this screen directly. How it works is a button on the About tab.
- **How it works wording** follows design/06 with two corrections, both **for the owner to confirm**: card 4 says "a short list of payments" (DESIGN.md says the plan is not always the fewest) and "you pay in any UPI app or cash" (the app does not open UPI apps yet). Card 3 still talks about joining by link and syncing, which arrive in M4.
- Compared with design/04 and 06 (`14-settle-up`, `15-how-it-works`). Differences: no "payment already confirmed" line (no payments can be recorded yet), and the mockup's tip box is replaced by the note above.
- A Compose test harness quirk: after work on a background thread, a coroutine can resume off the main thread in tests. Delete and the debug action now return to the main dispatcher explicitly; the end-to-end waits were lengthened because the build host was busy.

## T3.11 App lock (optional)

Off by default. Two modes, set in Settings (opened from About): Off, or **Phone lock** (owner decision, 4 Nov 2026; the own-PIN mode was dropped, so no PIN, salt, hash or try counter exists in the code).

- Locks 30 seconds after the app leaves the screen (so a quick trip to another app and back does not ask), and at once when the phone locks (its screen goes off); always asks on a fresh start. Screenshots are allowed (decision 13).
- Turning the lock on or off shows the phone's lock first (so it is proven to work before it is relied on).
- The phone's lock is Android's `BiometricPrompt`: a strong fingerprint or face, or the screen lock PIN, pattern or password on Android 11 and newer; the screen lock on Android 10; a fingerprint on Android 9 and older (Android cannot combine the two there). We store only the chosen mode; Android applies its own wrong-try limits.
- If the phone no longer has any lock (the person removed their screen lock), the app lock cannot protect anything, so it stops asking instead of trapping the person out of their own data; Settings says so.
- Adds the normal `USE_BIOMETRIC` permission through the biometric library.

Done when: JVM tests for the state machine, the Settings choices and the stored mode; the real prompt checked on the emulator.

### T3.11 result

JVM tests (state machine 15, Settings model and gate, stored mode, shared secure flag) pass, with every rule checked by breaking the code on purpose. **Checked by hand on the emulator with a real device PIN** (set with `adb shell locksettings set-pin`; setup test `LockSetupInstrumentedTest`, run only with `-e lockSetup yes`):
- the lock screen and the system prompt show on a fresh start; a screen capture of it is black (secure window);
- the right PIN opens the app; Android rejects a wrong PIN itself; cancelling shows "OwnTally is locked" with "That did not unlock it. Try again." and an Unlock button;
- leaving for 10 seconds and coming back stays open; leaving for 35 seconds asks again;
- turning the lock off asks for the PIN, then screenshots work and a restart opens straight in; turning it on again asks for the PIN and survives a restart;
- after removing the device PIN while the lock was on, the app opens without asking and Settings explains why.
Not checked: a real fingerprint (the emulator was used with a PIN only), and Android 9 or 10 behaviour.

## T3.12 Hand back

Checklist: `./gradlew test` and the debug build pass locally and in CI; the data survives an app restart on the emulator (create a group, add and delete expenses, kill and reopen the app); balances are correct offline; no network permission in the manifest except what later milestones add (none now); no secret in logs; docs match; interpretations listed in the pull request.

### T3.12 result (hand back)

- `./gradlew` tests for every module, the debug and release builds and `lintDebug` pass locally; CI passed on every earlier push of this branch (the last run is linked in the pull request).
- **Restart check on the emulator (by hand):** with the debug test friends, three expenses were added, one deleted, the app killed and reopened: the Group screen was identical (balances me −₹3,800, Ben ₹8,600, Meera ₹12,400, total spent ₹25,800, the deleted entry struck through). The same persistence is also covered by the instrumented restart tests from T3.5.
- **Balances are correct offline:** there is no network code (a test scans for it); the figures above match the core's balances worked out by hand.
- **Permissions:** none requested by us. The merged manifest has only `USE_BIOMETRIC` and `USE_FINGERPRINT` from the biometric library, no `INTERNET`.
- **No secret in logs:** `NoLoggingTest` and `NoPhraseLeaksTest` pass (no log calls, no saved state in the UI module); the lock stores only its mode.
- **Interpretations for the owner to confirm:** (1) the "This changes balances" delete warning shows when a payment in the group involves someone on the expense; (2) How it works wording changed in two places (short list instead of "fewest", UPI wording); (3) the debug build's test-data button; (4) a phone without a screen lock makes the app lock stop asking instead of trapping the person; (5) amounts follow the phone's region, so two friends can see different grouping; (6) backup of the database includes this person's name and phone (settings table) and group ids in plain form, with the events encrypted: say if the settings should be excluded or kept out of the database.
- **Known gaps:** one end-to-end test timeout was seen about once in five runs while the build host was busy and did not reproduce in six later runs (the test now prints the screen when it times out); a real fingerprint and Android 9 and 10 were not tried.

### Changes after review, 4 Nov 2026

Rebased onto `main` (PRs #5 to #7 were merged). Then the owner's second-round answers above (items 13 to 19) were applied: screenshot blocking removed (and its tests now assert the opposite), delete warning narrowed to marked or confirmed payments, the app locks when the phone locks, minSdk 30, UPI and the share sheet removed. Older result sections above describe what was true when they were written; where they differ, this section and items 13 to 19 win. T3.10 result: Pay no longer opens a share sheet. T3.12 interpretations 1 to 3 and 5 are now decided; number 4 (backup) is kept as is.

Checked by hand on the emulator after these changes: with the lock on and a real device PIN, a fresh start asks; after the phone's screen went off and on again a few seconds later the app was locked; a screen capture of the open app is a normal picture (not black). Unit tests, emulator tests, debug and release builds and lint pass.



Checked by hand on the emulator (second review round): lock on and no phone lock shows "OwnTally is locked" with the instruction; after setting a PIN and returning, the system prompt appears and the PIN opens the app (this found and fixed a stale-check bug: the state did not change on returning, so the screen kept saying there was no lock); after going Home, the recent-apps preview of the app is blank. Opening recents directly from the app still shows the live screen, which Android does by design.
\n

### Decisions of 4 Nov 2026, third round (after the review)

22. **Delete rule:** only the creator of an expense or the group owner can delete it; **admins cannot** (changed from "creator or any admin"). It follows the owner when ownership moves (the old owner becomes an admin and loses it). Core rule, tests, the delete button, DESIGN.md, PROTOCOL.md and BUILD_PLAN are updated.
23. **Relay:** a last resort used by the app on its own. No setting, no admin switch, nobody decides. Order: Nearby, direct (Iroh), then the relay. DESIGN.md and BUILD_PLAN M7 are updated; the "hosting your own relay" section and the custom relay address are kept but are not in the app for now. Timeouts for this order are still to be chosen. The "Can't reach a member" screen in the mockups mentions turning the relay on: it needs rewording when M7 is built.
24. **Keep all three ways of syncing:** Nearby, Iroh and the WebSocket relay, all behind the `Transport` interface. Iroh already has a relay fallback of its own (our Iroh relay), so the WebSocket relay overlaps with it; it stays as a second path for networks where Iroh's connection type is blocked. This makes M5 to M7 the largest milestones. Still open: the timeouts, and real-phone numbers on how often Iroh connects directly.
25. **Order of the three ways (owner, 4 Nov 2026, after item 24): Nearby first, WebSocket relay second, Iroh third.** This is the 2 Nov order. It conflicts with item 23 (relay as last resort, direct first): with the relay second, every internet sync goes through our relay and Iroh direct is only the fallback, so the relay sees every member's address, timing and data volume every time. **Open: which wins.** Until then the Relay section of DESIGN.md still says "last resort".

