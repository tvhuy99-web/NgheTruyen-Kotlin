package vn.nghetruyen.source.network

import vn.nghetruyen.source.api.SourceNetworkCapability
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class SourceNetworkLimiter(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Gate(
        var concurrency: Int,
        var semaphore: Semaphore,
        val starts: ArrayDeque<Long> = ArrayDeque(),
    )

    private val gates = ConcurrentHashMap<String, Gate>()

    fun <T> run(sourceId: String, capability: SourceNetworkCapability, timeoutMs: Long, block: () -> T): T {
        val gate = gates.compute(sourceId) { _, old ->
            if (old == null || old.concurrency != capability.maxConcurrent) {
                Gate(capability.maxConcurrent, Semaphore(capability.maxConcurrent, true))
            } else old
        }!!
        val acquired = gate.semaphore.tryAcquire(timeoutMs.coerceIn(100L, 120_000L), TimeUnit.MILLISECONDS)
        require(acquired) { "SOURCE_NETWORK_CONCURRENCY_LIMIT" }
        try {
            synchronized(gate.starts) {
                val threshold = clockMs() - 60_000L
                while (gate.starts.isNotEmpty() && gate.starts.first() <= threshold) gate.starts.removeFirst()
                require(gate.starts.size < capability.requestsPerMinute) { "SOURCE_NETWORK_RATE_LIMIT" }
                gate.starts.addLast(clockMs())
            }
            return block()
        } finally {
            gate.semaphore.release()
        }
    }
}
