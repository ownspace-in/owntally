package owntally.ui.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.data.session.NearbyPeer
import owntally.data.session.NearbyStatus
import owntally.model.PublicKey

class NearbyModelTest {
    private val rohan = PublicKey(ByteArray(32) { 1 })
    private val meera = PublicKey(ByteArray(32) { 2 })
    private val names = mapOf(rohan to "Rohan", meera to "meera")

    @Test fun beforeStartThereIsOnlyAStartButtonAndEveryoneIsNotStarted() {
        val m = NearbyModels.build("Goa Trip", names, NearbyStatus(), false)
        assertEquals("Ready", m.headline)
        assertEquals("Start", m.startLabel); assertNull(m.stopLabel); assertNull(m.lookAgainLabel); assertNull(m.note)
        assertEquals(listOf("meera", "Rohan"), m.lines.map { it.name }, "by name, ignoring case")
        assertEquals(listOf("Not started"), m.lines.map { it.text }.distinct())
        assertEquals(NearbyModels.INTRO, m.intro)
    }

    @Test fun whileLookingEveryoneIsBeingLookedForAndTheOnlyButtonIsStop() {
        val m = NearbyModels.build("Goa Trip", names, NearbyStatus(true, mapOf(rohan to NearbyPeer.LOOKING, meera to NearbyPeer.LOOKING)), false)
        assertEquals("Looking for friends…", m.headline)
        assertEquals(listOf("Looking for meera…", "Looking for Rohan…"), m.lines.map { it.text })
        assertNull(m.startLabel); assertEquals("Stop", m.stopLabel); assertNull(m.lookAgainLabel)
        assertEquals("Nearby uses Bluetooth and Wi-Fi and runs only while this screen is on. Stop turns it off.", m.note)
    }

    @Test fun syncingAndDoneAreEachSaid() {
        val m = NearbyModels.build("Goa Trip", names, NearbyStatus(true, mapOf(rohan to NearbyPeer.SYNCING, meera to NearbyPeer.DONE)), false)
        assertEquals("Syncing…", m.headline)
        assertEquals(listOf("Synced with meera", "Syncing with Rohan…"), m.lines.map { it.text })
        assertEquals(listOf(NearbyTone.GOOD, NearbyTone.WORKING), m.lines.map { it.tone })
        val done = NearbyModels.build("Goa Trip", names, NearbyStatus(true, mapOf(rohan to NearbyPeer.DONE, meera to NearbyPeer.DONE)), false)
        assertEquals("Everyone is up to date", done.headline)
    }

    @Test fun aMemberNotFoundOrNotFinishedOffersToLookAgainAndSaysWhatToDo() {
        val m = NearbyModels.build("Goa Trip", names, NearbyStatus(true, mapOf(rohan to NearbyPeer.NOT_FOUND, meera to NearbyPeer.FAILED)), false)
        assertEquals(listOf("meera: Syncing with meera did not finish.", "Rohan: Rohan was not found nearby. Ask them to open this screen too."), m.lines.map { "${it.name}: ${it.text}" })
        assertEquals("Look again", m.lookAgainLabel)
        assertEquals(listOf(NearbyTone.PROBLEM, NearbyTone.PROBLEM), m.lines.map { it.tone })
    }

    @Test fun aPhoneWithoutGooglePlayServicesIsToldAndOfferedNothingToStart() {
        val m = NearbyModels.build("Goa Trip", names, NearbyStatus(), true)
        assertEquals("Nearby is not available on this phone", m.headline)
        assertNull(m.startLabel); assertNull(m.stopLabel); assertEquals(emptyList(), m.lines)
        assertTrue("Google Play services" in m.note!!)
    }

    @Test fun thePermissionsAskedForAreTheOnesEachAndroidVersionNeedsAndNothingElse() {
        assertEquals(listOf(NearbyPermissions.FINE_LOCATION), NearbyPermissions.required(30))
        assertEquals(listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION"), NearbyPermissions.required(31))
        assertEquals(listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"), NearbyPermissions.required(32), "no location from Android 12L")
        assertEquals(listOf(NearbyPermissions.FINE_LOCATION, NearbyPermissions.COARSE_LOCATION), NearbyPermissions.request(30))
        assertEquals(NearbyPermissions.required(31) + NearbyPermissions.COARSE_LOCATION, NearbyPermissions.request(31))
        assertEquals(NearbyPermissions.required(32), NearbyPermissions.request(32))
        assertEquals(NearbyPermissions.required(34), NearbyPermissions.request(34))
        assertEquals(listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.NEARBY_WIFI_DEVICES"), NearbyPermissions.required(33))
        assertEquals(NearbyPermissions.required(33), NearbyPermissions.required(36))
        assertTrue(NearbyPermissions.required(33).none { it.contains("LOCATION") }, "no location from Android 13")
        assertTrue(NearbyPermissions.request(32).none { it.contains("LOCATION") })
    }

    @Test fun whatIsMissingIsWhatWasNotGranted() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.NEARBY_WIFI_DEVICES"), NearbyPermissions.missing(34) { it.endsWith("ADVERTISE") || it.endsWith("CONNECT") })
        assertEquals(emptyList(), NearbyPermissions.missing(30) { true })
    }

    @Test fun theExplanationNeverPromisesLessThanTheTruthAboutLocation() {
        assertTrue("does not read your location" in NearbyPermissions.explanation(30))
        assertTrue("does not read your location" in NearbyPermissions.explanation(31))
        assertTrue("location" !in NearbyPermissions.explanation(33))
        assertTrue("location" !in NearbyPermissions.explanation(32))
    }
}
