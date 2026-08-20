package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.audio.AudioAssetKind

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
}
