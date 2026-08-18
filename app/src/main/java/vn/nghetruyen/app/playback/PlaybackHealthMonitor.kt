package vn.nghetruyen.app.playback

import java.util.ArrayDeque


class PlaybackHealthMonitor(private val capacity: Int = 128) {
    data class Event(val atMillis: Long, val type: String, val detail: String)

    private val events = ArrayDeque<Event>()
    private var startedChunks = 0L
    private var completedChunks = 0L
    private var failedChunks = 0L
    private var recoveries = 0L

    @Synchronized fun chunkStarted(atMillis: Long, token: String) {
        startedChunks++
        add(Event(atMillis, "START", token.takeLast(48)))
    }

    @Synchronized fun chunkCompleted(atMillis: Long, token: String) {
        completedChunks++
        add(Event(atMillis, "DONE", token.takeLast(48)))
    }

    @Synchronized fun chunkFailed(atMillis: Long, code: String) {
        failedChunks++
        add(Event(atMillis, "FAILED", code.take(96)))
    }

    @Synchronized fun recovery(atMillis: Long, action: SpeechRecoveryAction) {
        recoveries++
        add(Event(atMillis, "RECOVERY", action.name))
    }

    @Synchronized fun snapshot(): String = buildString {
        append("started=").append(startedChunks)
        append(" completed=").append(completedChunks)
        append(" failed=").append(failedChunks)
        append(" recoveries=").append(recoveries)
        append(" inFlight=").append((startedChunks - completedChunks - failedChunks).coerceAtLeast(0L))
        append(" events=").append(events.size)
    }

    @Synchronized fun recent(): List<Event> = events.toList()

    private fun add(event: Event) {
        events.addLast(event)
        while (events.size > capacity.coerceIn(16, 512)) events.removeFirst()
    }
}
