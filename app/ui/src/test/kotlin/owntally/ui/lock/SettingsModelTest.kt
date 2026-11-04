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

    @Test fun ifTheLockIsOnButThePhoneLostItsLockTheNoteSaysTheAppStaysLocked() {
        val m = SettingsModel(LockMode.PHONE, PhoneLockAvailability.NOT_SET_UP)
        assertEquals("Your phone has no screen lock, so OwnTally stays locked until you set one.", m.phoneNote)
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

    @Test fun aLockedAppShowsTheLockScreenWhateverThePhoneCanDo() {
        val locked = LockState.start(LockMode.PHONE)
        assertTrue(LockGate.mustAsk(locked, PhoneLockAvailability.AVAILABLE))
        assertFalse(LockGate.mustAsk(locked.unlocked(), PhoneLockAvailability.AVAILABLE))
        // Owner decision, 4 Nov 2026: with no screen lock on the phone the app stays locked until one is set (nothing is lost meanwhile).
        assertTrue(LockGate.mustAsk(locked, PhoneLockAvailability.NOT_SET_UP))
        assertTrue(LockGate.mustAsk(locked, PhoneLockAvailability.UNSUPPORTED))
        assertFalse(LockGate.mustAsk(LockState.start(LockMode.OFF), PhoneLockAvailability.AVAILABLE))
        assertFalse(LockGate.mustAsk(LockState.start(LockMode.OFF), PhoneLockAvailability.NOT_SET_UP))
    }

    @Test fun theLockScreenOnlyAsksTheSystemWhenThePhoneCan() {
        assertTrue(LockGate.canAsk(PhoneLockAvailability.AVAILABLE))
        assertFalse(LockGate.canAsk(PhoneLockAvailability.NOT_SET_UP))
        assertFalse(LockGate.canAsk(PhoneLockAvailability.UNSUPPORTED))
    }

    @Test fun lockScreenWordsSayWhatToDo() {
        assertEquals("Use your fingerprint or your phone's screen lock to open it.", LockGate.lockScreenText(PhoneLockAvailability.AVAILABLE))
        assertEquals("Your phone has no screen lock. Set a screen lock or fingerprint in your phone's settings, then come back.", LockGate.lockScreenText(PhoneLockAvailability.NOT_SET_UP))
        assertEquals("This phone cannot check a lock for apps, so OwnTally cannot be opened while its lock is on.", LockGate.lockScreenText(PhoneLockAvailability.UNSUPPORTED))
    }
}

class CoverRuleTest {
    @Test fun theScreenIsCoveredWheneverTheAppIsNotInFront() {
        assertFalse(CoverRule.covered(androidx.lifecycle.Lifecycle.State.RESUMED))
        for (s in listOf(androidx.lifecycle.Lifecycle.State.STARTED, androidx.lifecycle.Lifecycle.State.CREATED, androidx.lifecycle.Lifecycle.State.INITIALIZED)) {
            assertTrue(CoverRule.covered(s), "$s")
        }
    }
}
