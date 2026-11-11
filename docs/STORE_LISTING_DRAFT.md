# Store listing: facts, not text (M10, T10.5)

The store listing text is the owner's (BUILD_PLAN, "What the owner decides"). This file is only the **facts** the listing and the store's forms must agree with, taken from the app and checked against it. Each line marked **TO FILL IN** is the owner's to answer. Not legal advice; check Google's current forms before submitting.

## What the app is
- OwnTally: splits expenses with friends and settles up. Android 11 and newer. No account. Data stays on the phone; phones sync directly with each other, through a relay that cannot read the data when a direct link is not possible.
- It never holds or moves money and names no payment service.
- Languages: English. Currency: Indian rupee (as made).
- **TO FILL IN:** the short description (80 characters), the long description, the screenshots, the icon and the feature graphic (the owner's).
- **TO FILL IN:** the category, the contact email, the website (the page at `https://joinowntally.ownspace.in/` can be it).
- **Privacy policy address:** `https://joinowntally.ownspace.in/privacy` (the same text as the in-app Privacy screen, from `PrivacyFacts`; the operator's name and contact are **TO FILL IN** there too).

## Permissions the app asks for, and why (from the merged manifest, `ManifestRulesTest`)
| Permission | Why | When the person is asked |
|---|---|---|
| Internet | sync with the other members | never asked (a normal permission) |
| Nearby devices (Bluetooth and Wi-Fi), Bluetooth, Wi-Fi state, and location on Android 11 and 12 | share in the same room (Google's Nearby Connections); location is how Android 11 and 12 name Bluetooth scanning, and the app does not read it | after an explanation, when the person taps Continue on the Nearby screen |
| Wake lock, receive boot completed, network state | the background sync job (about every 15 minutes) | never asked (normal permissions) |
| Biometric | the optional app lock | never asked |

No camera, contacts, storage, microphone, SMS or background location. The app opens one other app: the phone's dialer (Call on a payment, which does not place the call).

## Data safety (draft answers; confirm against Google's form)
- **Does the app collect or share user data?** Nothing is sent to the developer to be stored: there is no account and no developer database. What leaves the phone: encrypted entries to other members' phones; routing data to the relay and to n0's discovery service (below).
- **Processed by the relay run by the developer (`relay.ownspace.in`), in memory, not stored:** the two phones' public keys, a code made from the group's id, internet addresses (for rate limits), connection times and sizes. Google's form treats data processed only in memory as not "collected": **TO FILL IN** (the owner decides how to answer, with this fact).
- **Shared with a third party (n0, the Iroh discovery service), for app functionality:** a public key the phone uses in each group, the phone's internet address, and when it is online. Types on the form: "Device or other IDs" and possibly "Approximate location" (an IP address): **TO FILL IN**.
- **Google Play services (Nearby Connections):** used only when the person starts Nearby; Google's own policy applies.
- **Encrypted in transit:** yes (TLS between the phones, even through the relay).
- **Can the person ask for data to be deleted?** There is nothing to delete on a server of ours; the person deletes the app's data on the phone. The relay and n0 keep nothing the person can ask about beyond what they say.
- **Not collected:** location, contacts, photos, files, messages, financial information (no payment details; the app never holds money), health, audio, identifiers for advertising, crash logs, analytics.
- **Ads:** none. **In-app purchases:** none. **Account:** none.

## Content and policy answers that follow from the app
- **User-generated content:** members enter names and expense descriptions that the other members of the same group see; nothing is public.
- **Financial features:** none; the app records who owes whom and never moves money.
- **Target audience:** not made for children. **TO FILL IN** (the owner's).
- **Restore:** restoring from the recovery phrase works since M11 (11 Nov 2026): the text may say that on a new phone the 12 words give back the identity and that a friend's invitation brings each group back as the same member, with no approval. It must not say that the phrase restores data or groups by itself.
- **Open-source libraries and licences:** listed in the release notes **TO FILL IN** (Tink, Room, Compose, OkHttp, Iroh, WorkManager, Play services, the QR generator).
