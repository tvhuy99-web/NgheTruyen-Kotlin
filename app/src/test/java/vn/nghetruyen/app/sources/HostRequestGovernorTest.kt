package vn.nghetruyen.app.sources

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRequestGovernorTest {
    @Test
    fun repeatedRequestsForSameHostAreDelayed() = runTest {
        var nanos = 1_000_000_000L
        val governor = HostRequestGovernor(700L) { nanos }
        governor.awaitTurn("example.org")
        nanos += 100_000_000L
        val before = testScheduler.currentTime
        governor.awaitTurn("example.org")
        assertTrue(testScheduler.currentTime - before >= 600L)
    }
}
