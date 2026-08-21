package vn.nghetruyen.app.freesound

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundParallelImportPolicyTest {
    @Test
    fun downloadsAndNormalizationRunConcurrentlyWithHardLimitFourAndKeepOrder() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val values = (0 until 12).toList()

        val output = FreesoundParallelImportPolicy.mapOrdered(values) { value ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(35)
                value * 100
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(values.map { it * 100 }, output)
        assertEquals(4, FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS)
        assertTrue("expected actual overlap", maximum.get() > 1)
        assertTrue("must never exceed four imports", maximum.get() <= 4)
    }
}
