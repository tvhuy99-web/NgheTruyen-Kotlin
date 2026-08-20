package vn.nghetruyen.app.freesound

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class FreesoundImporterTest {
    @Test
    fun previewExtensionPrefersActualUrlSuffix() {
        assertEquals("ogg", FreesoundImporter.extensionForPreviewUrl("https://cdn.example/sound-hq.ogg"))
        assertEquals("ogg", FreesoundImporter.extensionForPreviewUrl("https://cdn.example/a.OGG?token=x"))
        assertEquals("mp3", FreesoundImporter.extensionForPreviewUrl("https://cdn.example/sound-hq.mp3"))
    }

    @Test
    fun titleMatchesManualImportStyleByRemovingAudioExtension() {
        assertEquals("Thunder Strike", FreesoundImporter.titleForImport(" Thunder Strike.wav ", "fallback"))
        assertEquals("Forest Night", FreesoundImporter.titleForImport("Forest Night.ogg", "fallback"))
        assertEquals("Magic Spell", FreesoundImporter.titleForImport("Magic Spell", "fallback"))
        assertEquals("fallback", FreesoundImporter.titleForImport(".mp3", "fallback"))
    }

    @Test
    fun importTagsUseExistingCategoryMarkersAndDescriptionLimit() {
        assertEquals(
            "type:music, Epic background",
            FreesoundImporter.tagsForImport(AudioAssetKind.MUSIC, " Epic background "),
        )
        assertEquals(
            "type:ambience, Forest at night",
            FreesoundImporter.tagsForImport(AudioAssetKind.AMBIENCE, "Forest at night"),
        )
        assertEquals(
            "type:sfx",
            FreesoundImporter.tagsForImport(AudioAssetKind.SFX, "   "),
        )

        val longDescription = "x".repeat(500)
        val tags = FreesoundImporter.tagsForImport(AudioAssetKind.SFX, longDescription)
        assertTrue(tags.startsWith("type:sfx, "))
        assertEquals(300, tags.removePrefix("type:sfx, ").length)
    }

    @Test
    fun managedUriExposesFreesoundIdAcrossAllThreeCategories() {
        assertEquals(
            12345,
            FreesoundImporter.soundIdFromManagedUri(
                "file:///data/user/0/vn.nghetruyen.app/files/audio/freesound/music/freesound_12345_8ad49d6c-7b14-4cc1-a4d7-817e30dad079.ogg",
            ),
        )
        assertEquals(
            88,
            FreesoundImporter.soundIdFromManagedUri(
                "file:///data/user/0/vn.nghetruyen.app/files/audio/freesound/ambience/freesound_88_8ad49d6c-7b14-4cc1-a4d7-817e30dad079.mp3",
            ),
        )
        assertEquals(
            9,
            FreesoundImporter.soundIdFromManagedUri(
                "file:///data/user/0/vn.nghetruyen.app/files/audio/freesound/sfx/freesound_9_8ad49d6c-7b14-4cc1-a4d7-817e30dad079.ogg",
            ),
        )
    }

    @Test
    fun normalizingMarkerTemporarilyHidesRemoteIdFromUiDuplicateChecks() {
        val root = Files.createTempDirectory("freesound-marker-test").toFile()
        try {
            val directory = File(root, "audio/freesound/sfx").apply { mkdirs() }
            val audio = File(directory, "freesound_77_8ad49d6c-7b14-4cc1-a4d7-817e30dad079.ogg").apply {
                writeBytes(byteArrayOf(1))
            }
            val uri = "file://${audio.absolutePath}"
            assertEquals(77, FreesoundImporter.soundIdFromManagedUri(uri))

            File("${audio.absolutePath}.normalizing").writeText("normalizing")
            assertNull(FreesoundImporter.soundIdFromManagedUri(uri))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateDetectionDoesNotTreatExternalOrMalformedUrisAsFreesoundImports() {
        assertNull(FreesoundImporter.soundIdFromManagedUri("content://media/external/audio/123"))
        assertNull(
            FreesoundImporter.soundIdFromManagedUri(
                "file:///storage/emulated/0/Music/freesound_123_8ad49d6c-7b14-4cc1-a4d7-817e30dad079.ogg",
            ),
        )
        assertNull(
            FreesoundImporter.soundIdFromManagedUri(
                "file:///data/user/0/vn.nghetruyen.app/files/audio/freesound/music/freesound_nope_uuid.ogg",
            ),
        )
    }

    @Test
    fun duplicateIsReadyOnlyAfterSuccessfulNormalization() {
        val base = SceneMusicTrackEntity(
            id = "track",
            title = "Thunder",
            uri = "file:///tmp/thunder.ogg",
            tagsCsv = "type:sfx",
            volume = 1f,
            enabled = true,
            updatedAt = 1L,
        )
        assertFalse(FreesoundImporter.hasValidNormalization(base))

        val ready = base.copy(
            normalizationVersion = PcmLoudnessEstimator.VERSION,
            normalizationError = "",
            loudnessLufsEstimate = -20f,
            peakDbfs = -2f,
            normalizationGainDb = 3f,
        )
        assertTrue(FreesoundImporter.hasValidNormalization(ready))
        assertFalse(FreesoundImporter.hasValidNormalization(ready.copy(normalizationError = "decode failed")))
        assertFalse(FreesoundImporter.hasValidNormalization(ready.copy(normalizationGainDb = Float.NaN)))
    }

    @Test
    fun queueSummaryCountsEveryTerminalAndActiveState() {
        val summary = summarizeFreesoundQueue(
            listOf(
                FreesoundImportQueueStatus.QUEUED,
                FreesoundImportQueueStatus.QUEUED,
                FreesoundImportQueueStatus.IMPORTING,
                FreesoundImportQueueStatus.IMPORTED,
                FreesoundImportQueueStatus.IMPORTED,
                FreesoundImportQueueStatus.FAILED,
                FreesoundImportQueueStatus.DUPLICATE,
                FreesoundImportQueueStatus.CANCELLED,
            ),
        )

        assertEquals(2, summary.queued)
        assertEquals(1, summary.importing)
        assertEquals(2, summary.imported)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.duplicate)
        assertEquals(1, summary.cancelled)
        assertEquals(8, summary.total)
    }
}
