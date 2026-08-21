package vn.nghetruyen.app.freesound

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundParallelSearchPolicyTest {
    @Test
    fun searchesRunConcurrentlyWithHardLimitFourAndKeepOrder() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val values = (0 until 12).toList()

        val output = FreesoundParallelSearchPolicy.mapOrdered(values) { value ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(35)
                value * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(values.map { it * 10 }, output)
        assertEquals(4, FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES)
        assertTrue("expected actual overlap", maximum.get() > 1)
        assertTrue("must never exceed four searches", maximum.get() <= 4)
    }
}
