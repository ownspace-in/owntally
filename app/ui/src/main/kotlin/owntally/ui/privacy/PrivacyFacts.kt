package owntally.ui.privacy

/** One heading of the privacy text and its sentences. */
data class PrivacySection(val id: String, val heading: String, val lines: List<String>)

/**
 * What OwnTally says about privacy (M10, T10.5): ONE source for the three places it is said, in the app (the Privacy screen under About), on the web
 * (`joinowntally.ownspace.in/privacy`, which the Play Store needs) and in the draft of the store's data-safety answers (`docs/STORE_LISTING_DRAFT.md`).
 * Tests keep the page and the draft in step with these sentences, and keep the sentences true (they name the relay, n0 and Google as they are used).
 *
 * The operator's name and a contact address are the owner's to supply before release; until then they are marked, and `ReleaseGateTest` fails a release build
 * that still has the marks.
 */
object PrivacyFacts {
    /** To be replaced by the owner before release (the operator of relay.ownspace.in and the person to write to). */
    const val OPERATOR = "[OPERATOR NAME: the owner fills this in before release]"
    const val CONTACT = "[CONTACT ADDRESS: the owner fills this in before release]"
    const val PLACEHOLDER_MARK = "the owner fills this in before release"

    const val RELAY = "relay.ownspace.in"
    const val PAGE = "https://joinowntally.ownspace.in/privacy"

    val sections: List<PrivacySection> = listOf(
        PrivacySection(
            "stays", "Where your data lives",
            listOf(
                "Your groups, expenses, payments and the keys that protect them are stored only on this phone, encrypted.",
                "There is no OwnTally account, and no server keeps your data.",
            ),
        ),
        PrivacySection(
            "leaves", "What leaves your phone",
            listOf(
                "Your entries go only to the other members of your groups, encrypted, straight from phone to phone or through the relay below.",
                "The name and phone number you give are shown to the members of each group you join. They are not checked, and you can use different ones in each group.",
            ),
        ),
        PrivacySection(
            "relay", "The relay",
            listOf(
                "$RELAY is run by $OPERATOR. It passes encrypted data between two phones that cannot reach each other directly.",
                "It can see the public keys of the two phones, a code made from the group's id, the phones' internet addresses, when they connect and how much they send. It cannot read what you send, and it stores nothing.",
            ),
        ),
        PrivacySection(
            "n0", "Finding each other",
            listOf(
                "To help phones find each other, OwnTally uses the public discovery service of n0, the makers of the Iroh connection library.",
                "n0 learns a public key that your phone uses in each group, your phone's internet address, and when your phone is online. It cannot read what you send.",
            ),
        ),
        PrivacySection(
            "nearby", "Sharing in the same room",
            listOf(
                "Sharing with a phone nearby uses Google's Nearby Connections, part of Google Play services on your phone, and only after you tap Start on the Nearby screen. It is Google's service: Google's own privacy policy applies to it.",
            ),
        ),
        PrivacySection(
            "never", "What OwnTally does not do",
            listOf(
                "No analytics, no advertising, no tracking, and no crash reports are sent anywhere.",
                "It does not read your contacts, your location, your photos or your messages.",
                "It never holds or moves money, and it does not know which payment app you use.",
            ),
        ),
        PrivacySection(
            "permissions", "Permissions and why",
            listOf(
                "Internet: to sync with the other members of your groups.",
                "Nearby devices, Bluetooth and Wi-Fi: only for sharing in the same room, when you start it. On Android 11 and 12 Bluetooth scanning is called \"location\"; OwnTally does not read your location.",
                "Running in the background: about every 15 minutes OwnTally may wake to sync, which Android allows without a question.",
            ),
        ),
        PrivacySection(
            "backup", "Backups and a lost phone",
            listOf(
                "Android may back up OwnTally's database to your Google account: the encrypted entries, and the name and phone number you gave when you set up OwnTally, which are kept there as plain text. The keys are never part of a backup, so a backup alone cannot read your groups.",
                "If you lose your phone, the groups on it are gone unless other members still have them. Your 12-word recovery phrase is your identity: on a new phone you type it to get your identity back, and when a friend invites you again you rejoin each group as the same member, with no approval, and its history syncs from the other members. OwnTally does not keep the phrase or send it anywhere.",
            ),
        ),
        PrivacySection("contact", "Questions", listOf("Write to $CONTACT.")),
    )

    /** Every sentence in order, for tests and for the pages built from it. */
    val allLines: List<String> get() = sections.flatMap { it.lines }
}
