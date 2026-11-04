package owntally.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockStateTest {
    private val t0 = 1_000_000L

    @Test fun coldStartWithTheLockOffIsOpen() {
        assertFalse(LockState.start(LockMode.OFF).locked)
    }

    @Test fun coldStartWithTheLockOnAlwaysAsks() {
        assertTrue(LockState.start(LockMode.PHONE).locked)
    }

    @Test fun backInsideTheGracePeriodStaysOpen() {
        val s = LockState.start(LockMode.PHONE).unlocked().backgrounded(t0).foregrounded(t0 + 29_999)
        assertFalse(s.locked)
    }

    @Test fun backAtExactlyTheGracePeriodLocks() {
        val s = LockState.start(LockMode.PHONE).unlocked().backgrounded(t0).foregrounded(t0 + 30_000)
        assertTrue(s.locked)
    }

    @Test fun backLongAfterLocks() {
        assertTrue(LockState.start(LockMode.PHONE).unlocked().backgrounded(t0).foregrounded(t0 + 3_600_000).locked)
    }

    @Test fun theLockOffNeverLocks() {
        val s = LockState.start(LockMode.OFF).backgrounded(t0).foregrounded(t0 + 10_000_000)
        assertFalse(s.locked)
    }

    @Test fun comingToTheForegroundWithoutHavingLeftChangesNothing() {
        val open = LockState.start(LockMode.PHONE).unlocked()
        assertEquals(open, open.foregrounded(t0))
    }

    @Test fun aLockedAppStaysLockedWhateverTheTime() {
        val s = LockState.start(LockMode.PHONE).backgrounded(t0).foregrounded(t0 + 1)
        assertTrue(s.locked)
    }

    @Test fun unlockingOpensItAndForgetsTheTimeAway() {
        val s = LockState.start(LockMode.PHONE).backgrounded(t0).unlocked()
        assertFalse(s.locked)
        assertEquals(null, s.backgroundedAt)
    }

    @Test fun leavingTwiceDoesNotStretchTheGracePeriod() {
        // Two "stopped" signals in a row keep the first time.
        val s = LockState.start(LockMode.PHONE).unlocked().backgrounded(t0).backgrounded(t0 + 25_000).foregrounded(t0 + 31_000)
        assertTrue(s.locked)
    }

    @Test fun aClockThatWentBackwardsLocks() {
        val s = LockState.start(LockMode.PHONE).unlocked().backgrounded(t0).foregrounded(t0 - 5)
        assertTrue(s.locked)
    }

    @Test fun turningTheLockOnLeavesTheAppOpenButArmed() {
        val s = LockState.start(LockMode.OFF).withMode(LockMode.PHONE)
        assertFalse(s.locked)
        assertTrue(s.backgrounded(t0).foregrounded(t0 + 30_000).locked)
    }

    @Test fun turningTheLockOffOpensItAtOnce() {
        val s = LockState.start(LockMode.PHONE).withMode(LockMode.OFF)
        assertFalse(s.locked)
        assertFalse(s.backgrounded(t0).foregrounded(t0 + 100_000).locked)
    }

    @Test fun theGracePeriodIsThirtySeconds() {
        assertEquals(30_000L, LockState.GRACE_MILLIS)
    }

    @Test fun modeNamesAreStableBecauseTheyAreStored() {
        assertEquals("off", LockMode.OFF.stored)
        assertEquals("phone", LockMode.PHONE.stored)
        assertEquals(LockMode.PHONE, LockMode.fromStored("phone"))
        assertEquals(LockMode.OFF, LockMode.fromStored("off"))
        // Anything unknown or missing must not lock the person out of their data, nor pretend a lock is on.
        assertEquals(LockMode.OFF, LockMode.fromStored(null))
        assertEquals(LockMode.OFF, LockMode.fromStored("pin"))
    }
}
