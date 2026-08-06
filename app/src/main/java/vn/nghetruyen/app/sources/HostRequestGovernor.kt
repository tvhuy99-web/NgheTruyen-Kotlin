package vn.nghetruyen.app.sources

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes requests per host and enforces a small courtesy interval. */
class HostRequestGovernor(
    private val minimumIntervalMillis: Long = 700L,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private data class Gate(
        val mutex: Mutex = Mutex(),
        var lastRequestNanos: Long = 0L,
    )

    private val gates = ConcurrentHashMap<String, Gate>()

    suspend fun awaitTurn(host: String) {
        require(host.isNotBlank())
        val gate = gates.getOrPut(host.lowercase()) { Gate() }
        gate.mutex.withLock {
            val now = clockNanos()
            val elapsedMillis = if (gate.lastRequestNanos == 0L) {
                minimumIntervalMillis
            } else {
                (now - gate.lastRequestNanos).coerceAtLeast(0L) / 1_000_000L
            }
            val waitMillis = (minimumIntervalMillis - elapsedMillis).coerceAtLeast(0L)
            if (waitMillis > 0L) delay(waitMillis)
            gate.lastRequestNanos = clockNanos()
        }
    }
}
