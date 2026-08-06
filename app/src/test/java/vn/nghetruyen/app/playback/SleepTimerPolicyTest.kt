package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerPolicyTest {
    @Test fun deadlinePersistsAsAbsoluteTimeAndExpiresDeterministically() {
        val deadline = SleepTimerPolicy.deadlineFromMinutes(1_000L, 15)
        assertEquals(901_000L, deadline)
        assertEquals(500L, SleepTimerPolicy.remainingMillis(deadline, 900_500L))
        assertFalse(SleepTimerPolicy.hasExpired(deadline, 900_999L))
        assertTrue(SleepTimerPolicy.hasExpired(deadline, 901_000L))
        assertNull(SleepTimerPolicy.deadlineFromMinutes(1_000L, 0))
    }
}
