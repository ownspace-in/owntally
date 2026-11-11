# Manual checklist before the first release (for the owner)

What a person must do because a test cannot. Tick each, with the date. The automated side is in `docs/tasks/M10-hardening-release.md` (T10.9 result); the signing steps are in `docs/RELEASE.md`.

## Decisions and text only you can give
- [x] Security decisions made on 10 Nov 2026: F2 known limit for 1.0, F8 later update, N3 is M12, F4 and N2 fixed (`docs/SECURITY_REVIEW.md`). Still to decide, if you want them before 1.0: the notes N4 (Nearby digits) and anything the human review finds.
- [ ] A person has read the security-relevant files and signed the gate at the end of `docs/SECURITY_REVIEW.md` (BUILD_PLAN rule 8).
- [ ] The operator's name and contact address in `PrivacyFacts.kt`; rewrite the page with `OWNTALLY_WRITE_PRIVACY_PAGE=1`; deploy `site/privacy/`.
- [ ] Look at the wording of the nine privacy sections (they are in `PrivacyFacts.kt`) and at the two light-theme colour changes of T10.4 (placeholder text 66716A, control outlines 7F8D85).
- [ ] The store texts, category, audience and the data-safety answers (`docs/STORE_LISTING_DRAFT.md`, every TO FILL IN).
- [ ] Decide the relay's lack of client authentication (N3), and start M11 (restore from the recovery phrase) when you want it.

## Keys and Play
- [ ] Make the upload key and back it up (`docs/RELEASE.md` steps 1 and 2).
- [ ] Play Console: create the app, choose Play App Signing, upload the `.aab` to the internal test track (step 4).
- [ ] Make `site/.well-known/assetlinks.json` with the two fingerprints, deploy it, check it from outside, and on a phone `adb shell pm get-app-links in.ownspace.owntally` says `verified` (step 5).
- [ ] `OWNTALLY_RELEASE=1 ./gradlew :app:app:testDebugUnitTest --tests '*ReleaseGateTest'` passes (step 6).

## On real phones
- [ ] **A real phone with 2 GB of memory:** first run, a group with several members, a sync over the internet, the invite link; nothing is killed; the app stays responsive. (The emulator numbers are in the T10.7 result; Android 11, the minimum, was not tested at all.)
- [ ] **An Android 11 phone or emulator:** install, first run, a group, an expense, an invitation, Nearby.
- [ ] **TalkBack:** go through first run, a group, Add expense, Settle up and Members; check the order things are read in, that a field with a value is read sensibly, and that the QR code has a useful description.
- [ ] **The dark theme on a real screen:** every main screen, and the recents screen.
- [ ] **Install from a file** (the signed APK sent to a friend) and **from Play's internal test track**.
- [ ] **The invite link on two phones:** made on one, tapped on the other, with and without the app installed; the verified fingerprints.
- [ ] **The privacy page** loads at `https://joinowntally.ownspace.in/privacy`, and the in-app Privacy screen says the same.
- [ ] **Two real phones over mobile networks:** a sync in both directions through Iroh, and while a third device (or a script) opens an Iroh connection to a phone and sends nothing, the phone still accepts the others (F7's real-device check).
- [ ] Read Iroh's release notes for the version in use (the vulnerability scan cannot see inside its Rust code).

## Added for M11 (restoring from the recovery phrase)
- [ ] **A real wipe and restore:** on a real phone with a group that has had a removal or a key change, install, choose "I already have a recovery phrase", type the phrase, then have a friend send an invitation to each group: you are back as the same member with no approval, and the balances equal your friends'. Do it once as the person who made a group and once as an admin.
- [ ] A wrong phrase and a typo in one word give the messages on the restore screen and save nothing.
- [ ] Look at the restore screen and the changed texts (the recovery-phrase screen, About, the "can't be opened" screen, the Privacy screen) in both themes and at a large text size; read the new sentences for wording.
- [ ] Read `docs/SECURITY_REVIEW.md` section 6 (R1 to R6), above all **R1**: a leaked phrase lets anyone with an invitation into every group as you, without approval. Decide whether the app should say so in one sentence on the phrase screen.
- [ ] The human review of the M11 files listed in that section (`KeySlots`, `GroupSession` key chaining, `JoinGroup.resolve`, `AppContainer.identityOf`, the restore screens).

