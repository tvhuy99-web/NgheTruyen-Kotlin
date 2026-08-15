package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

class AudioAssetClassifierTest {
    @Test
    fun ordinaryExistingTrackRemainsMusic() {
        assertEquals(
            AudioAssetKind.MUSIC,
            AudioAssetClassifier.classify(track(tags = "bi thương, cổ trang, chậm")),
        )
    }

    @Test
    fun explicitAmbienceMarkerSelectsAmbience() {
        assertEquals(
            AudioAssetKind.AMBIENCE,
            AudioAssetClassifier.classify(track(tags = "type:ambience, mưa đêm, mái ngói")),
        )
    }

    @Test
    fun explicitSfxMarkerSelectsOneShotEffect() {
        assertEquals(
            AudioAssetKind.SFX,
            AudioAssetClassifier.classify(track(tags = "[sfx], rút kiếm, kim loại")),
        )
    }

    @Test
    fun continuousSfxMarkerIsPromotedToAmbienceBus() {
        assertEquals(
            AudioAssetKind.AMBIENCE,
            AudioAssetClassifier.classify(track(tags = "type:sfx_continuous, vó ngựa kéo dài")),
        )
        assertEquals(
            AudioAssetKind.AMBIENCE,
            AudioAssetClassifier.classify(track(tags = "[continuous], máy chạy đều")),
        )
    }

    @Test
    fun descriptiveWordsDoNotAccidentallyChangeKind() {
        assertEquals(
            AudioAssetKind.MUSIC,
            AudioAssetClassifier.classify(track(tags = "mưa, chiến đấu, tiếng gió, sấm")),
        )
    }

    private fun track(tags: String) = SceneMusicTrackEntity(
        id = "asset-1",
        title = "Asset.wav",
        uri = "content://audio/asset-1",
        tagsCsv = tags,
        volume = 1f,
        enabled = true,
        updatedAt = 1L,
    )
}
