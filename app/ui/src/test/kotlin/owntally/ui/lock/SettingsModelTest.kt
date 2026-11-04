package owntally.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    @Test fun phoneLockCanBeChosenOnlyWhenThePhoneCanCheckIt() {
        assertTrue(SettingsModel(LockMode.OFF, PhoneLockAvailability.AVAILABLE).canChoosePhoneLock)
        assertFalse(SettingsModel(LockMode.OFF, PhoneLockAvailability.NOT_SET_UP).canChoosePhoneLock)
        assertFalse(SettingsModel(LockMode.OFF, PhoneLockAvailability.UNSUPPORTED).canChoosePhoneLock)
    }

    @Test fun theNoteSaysWhyItCannot() {
        assertNull(SettingsModel(LockMode.OFF, PhoneLockAvailability.AVAILABLE).phoneNote)
        assertEquals("Set a screen lock or fingerprint in your phone's settings first.", SettingsModel(LockMode.OFF, PhoneLockAvailability.NOT_SET_UP).phoneNote)
        assertEquals("This phone cannot check a lock for apps.", SettingsModel(LockMode.OFF, PhoneLockAvailability.UNSUPPORTED).phoneNote)
    }

    @Test fun ifTheLockIsOnButThePhoneLostItsLockTheNoteSaysItIsNotEnforced() {
        val m = SettingsModel(LockMode.PHONE, PhoneLockAvailability.NOT_SET_UP)
        assertEquals("OwnTally's lock is not asking right now because your phone has no screen lock. Set one to use it again.", m.phoneNote)
    }

    @Test fun choosingTheCurrentModeDoesNothing() {
        assertEquals(ModeChange.Nothing, SettingsModel(LockMode.OFF, PhoneLockAvailability.AVAILABLE).choose(LockMode.OFF))
        assertEquals(ModeChange.Nothing, SettingsModel(LockMode.PHONE, PhoneLockAvailability.AVAILABLE).choose(LockMode.PHONE))
    }

    @Test fun changingEitherWayNeedsTheLockToBeShownFirst() {
        assertEquals(ModeChange.AskThenSet(LockMode.PHONE), SettingsModel(LockMode.OFF, PhoneLockAvailability.AVAILABLE).choose(LockMode.PHONE))
        assertEquals(ModeChange.AskThenSet(LockMode.OFF), SettingsModel(LockMode.PHONE, PhoneLockAvailability.AVAILABLE).choose(LockMode.OFF))
    }

    @Test fun turningItOnWhenThePhoneCannotCheckIsRefused() {
        assertEquals(ModeChange.Refused, SettingsModel(LockMode.OFF, PhoneLockAvailability.NOT_SET_UP).choose(LockMode.PHONE))
    }

    @Test fun turningItOffWhenThePhoneLostItsLockWorksWithoutAskingBecauseThereIsNothingToAsk() {
        assertEquals(ModeChange.SetNow(LockMode.OFF), SettingsModel(LockMode.PHONE, PhoneLockAvailability.NOT_SET_UP).choose(LockMode.OFF))
    }

    @Test fun theAppAsksOnlyWhenLockedAndThePhoneCanCheck() {
        val locked = LockState.start(LockMode.PHONE)
        assertTrue(LockGate.mustAsk(locked, PhoneLockAvailability.AVAILABLE))
        assertFalse(LockGate.mustAsk(locked.unlocked(), PhoneLockAvailability.AVAILABLE))
        // A phone with no screen lock cannot check anything, and must not shut the person out of their own data.
        assertFalse(LockGate.mustAsk(locked, PhoneLockAvailability.NOT_SET_UP))
        assertFalse(LockGate.mustAsk(locked, PhoneLockAvailability.UNSUPPORTED))
        assertFalse(LockGate.mustAsk(LockState.start(LockMode.OFF), PhoneLockAvailability.AVAILABLE))
    }

    @Test fun screenshotsAreBlockedWhileTheLockIsOnAndOnlyThen() {
        assertTrue(LockGate.blockScreenshots(LockMode.PHONE))
        assertFalse(LockGate.blockScreenshots(LockMode.OFF))
    }
}

class FlagHoldsTest {
    @Test fun theFlagStaysOnUntilEveryoneWhoAskedHasLetGo() {
        val h = FlagHolds()
        assertTrue(h.acquire(), "the first one turns it on")
        assertFalse(h.acquire(), "already on")
        assertFalse(h.release(), "one holder is left")
        assertTrue(h.release(), "the last one turns it off")
    }

    @Test fun releasingMoreThanAcquiredDoesNotGoNegative() {
        val h = FlagHolds()
        assertFalse(h.release())
        assertTrue(h.acquire())
        assertTrue(h.release())
    }
}
