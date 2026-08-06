package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderVolumeKeyPolicyTest {
    @Test fun mapsSingleDownEventsOnlyWhileReaderNavigationIsEnabled() {
        assertEquals(-1, ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_UP))
        assertEquals(1, ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN))
        assertNull(ReaderVolumeKeyPolicy.paragraphDelta(true, true, false, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN))
        assertNull(ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 1, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN))
        assertNull(ReaderVolumeKeyPolicy.paragraphDelta(true, false, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN))
        assertNull(ReaderVolumeKeyPolicy.paragraphDelta(false, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN))
    }
}
