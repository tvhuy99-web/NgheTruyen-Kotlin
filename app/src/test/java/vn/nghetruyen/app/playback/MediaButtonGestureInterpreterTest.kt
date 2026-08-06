package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonGestureInterpreterTest {
    @Test fun singleDoubleTripleClickMapToToggleNextPrevious() {
        val interpreter = MediaButtonGestureInterpreter(300)
        interpreter.onKeyEvent(up(100), true)
        assertEquals(MediaButtonCommand.TOGGLE, interpreter.flush(400))

        interpreter.onKeyEvent(up(1_000), true)
        interpreter.onKeyEvent(up(1_180), true)
        assertEquals(MediaButtonCommand.NEXT, interpreter.flush(1_500))

        interpreter.onKeyEvent(up(2_000), true)
        interpreter.onKeyEvent(up(2_100), true)
        interpreter.onKeyEvent(up(2_200), true)
        assertEquals(MediaButtonCommand.PREVIOUS, interpreter.flush(2_600))
    }

    @Test fun customMappingControlsAllGestureCounts() {
        val mapping = MediaButtonMapping(
            singleClick = MediaButtonCommand.PLAY,
            doubleClick = MediaButtonCommand.REWIND,
            tripleClick = MediaButtonCommand.STOP,
            longPress = MediaButtonCommand.NEXT,
        )
        val interpreter = MediaButtonGestureInterpreter(300)
        interpreter.onKeyEvent(up(100), true, mapping)
        assertEquals(MediaButtonCommand.PLAY, interpreter.flush(401, mapping))
        interpreter.onKeyEvent(up(1_000), true, mapping)
        interpreter.onKeyEvent(up(1_100), true, mapping)
        assertEquals(MediaButtonCommand.REWIND, interpreter.flush(1_401, mapping))
        val down = MediaKeyEvent(MediaKeyEvent.HEADSET_HOOK, MediaKeyEvent.ACTION_DOWN, 2_000, 2_500, 1, longPress = true)
        assertEquals(MediaButtonCommand.NEXT, interpreter.onKeyEvent(down, true, mapping).immediate)
    }

    @Test fun longPressStopsAndConsumesFollowingUp() {
        val interpreter = MediaButtonGestureInterpreter()
        val down = MediaKeyEvent(MediaKeyEvent.HEADSET_HOOK, MediaKeyEvent.ACTION_DOWN, 100, 700, 1, longPress = true)
        assertEquals(MediaButtonCommand.STOP, interpreter.onKeyEvent(down, true).immediate)
        assertNull(interpreter.onKeyEvent(up(720), true).immediate)
        assertNull(interpreter.flush(2_000))
    }

    @Test fun dedicatedKeysActOnInitialDownOnly() {
        val interpreter = MediaButtonGestureInterpreter()
        val down = MediaKeyEvent(MediaKeyEvent.MEDIA_NEXT, MediaKeyEvent.ACTION_DOWN, 0, 10, 0)
        val repeat = MediaKeyEvent(MediaKeyEvent.MEDIA_NEXT, MediaKeyEvent.ACTION_DOWN, 0, 20, 1)
        assertEquals(MediaButtonCommand.NEXT, interpreter.onKeyEvent(down, true).immediate)
        assertNull(interpreter.onKeyEvent(repeat, true).immediate)
    }

    @Test fun longSessionGestureStreamDoesNotLeakPendingClicksOrDuplicateCommands() {
        val interpreter = MediaButtonGestureInterpreter(280)
        val dedupe = MediaButtonEventDeduplicator()
        var commands = 0
        repeat(20_000) { index ->
            val time = index * 500L + 100L
            val event = up(time)
            if (dedupe.accept(event, time)) {
                interpreter.onKeyEvent(event, true)
                if (interpreter.flush(time + 281) == MediaButtonCommand.TOGGLE) commands++
            }
            assertFalse(dedupe.accept(event, time + 5))
        }
        assertEquals(20_000, commands)
        assertNull(interpreter.flush(20_000L * 500L + 1_000L))
    }

    @Test fun duplicatePhysicalEventIsRejected() {
        val dedupe = MediaButtonEventDeduplicator()
        val event = up(100)
        assertTrue(dedupe.accept(event, 100))
        assertFalse(dedupe.accept(event, 110))
        assertTrue(dedupe.accept(up(200), 200))
    }

    private fun up(time: Long) = MediaKeyEvent(MediaKeyEvent.HEADSET_HOOK, MediaKeyEvent.ACTION_UP, 0, time, 0)
}
