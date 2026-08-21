package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XpkAudioPromptQualityTest {
    private val musicTracks = listOf(
        SceneMusicTrackOption(
            id = "music-real-a",
            title = "Epic Battle Name.mp3",
            tags = emptyList(),
            description = "Sắc thái: hùng tráng | Dùng: đại chiến kéo dài | Tránh: đối thoại nhẹ",
        ),
        SceneMusicTrackOption(
            id = "music-real-b",
            title = "Sad Name.mp3",
            tags = emptyList(),
            description = "Sắc thái: bi thương | Dùng: mất mát sâu | Tránh: chiến thắng",
        ),
    )

    private val ambienceTracks = listOf(
        SceneMusicTrackOption(
            id = "amb-real-rain",
            title = "Rain File.wav",
            tags = emptyList(),
            description = "Nền: mưa đều ngoài trời | Dùng: cảnh mưa kéo dài | Tránh: trong nhà khô ráo",
        ),
        SceneMusicTrackOption(
            id = "amb-real-wind",
            title = "Wind File.wav",
            tags = emptyList(),
            description = "Nền: gió liên tục | Dùng: không gian ngoài trời nhiều gió | Tránh: phòng kín",
        ),
    )

    private val sfxTracks = listOf(
        SceneMusicTrackOption(
            id = "sfx-real-thunder",
            title = "Thunder File.wav",
            tags = emptyList(),
            description = "Sự kiện: sét đánh gần một lần | Dùng: tia sét thực sự đánh gần | Tránh: sấm rền xa",
        ),
        SceneMusicTrackOption(
            id = "sfx-real-sword",
            title = "Sword File.wav",
            tags = emptyList(),
            description = "Sự kiện: hai binh khí va mạnh | Dùng: va chạm trực tiếp | Tránh: rút kiếm",
        ),
    )

    @Test
    fun musicOnlyPromptDoesNotAnchorConcreteTrackExampleOrRequireAssignments() {
        val bundle = XpkVoiceCastPrompt.build(
            title = "Chương thử",
            body = "Trận chiến bắt đầu.\nSau đó mọi thứ lắng xuống.",
            profiles = emptyList(),
            storyNote = "",
            expressiveAdjustment = false,
            speedLimitPct = 0,
            pitchLimitPct = 0,
            volumeLimitPct = 0,
            expressionPrompt = "",
            includeVoiceCast = false,
            includeSceneMusic = true,
            tracks = musicTracks,
        )

        assertTrue(bundle.prompt.contains("AI MUSIC DIRECTOR"))
        assertTrue(bundle.prompt.contains("Không cung cấp ví dụ track_id cụ thể"))
        assertFalse(bundle.prompt.contains("\"track_id\": \"1\""))
        assertFalse(bundle.prompt.contains("\"track_id\": \"2\""))
        assertTrue(bundle.prompt.contains("Không tạo khóa assignments"))
        assertTrue(bundle.prompt.contains("định danh tạm"))
        assertTrue(bundle.prompt.lowercase().contains("không được cộng ưu tiên"))
        assertTrue(bundle.prompt.contains("loại các track xung đột rõ với phần “Tránh”"))
    }

    @Test
    fun unifiedPromptContainsPreviousTailExactlyOnceAndCurrentChapterWins() {
        val previousTail = "TAIL_UNIQUE_MARKER chương trước kết thúc trong mưa."
        val base = XpkVoiceCastPrompt.build(
            title = "Chương mới",
            body = "Cửa mở ra.\nNhân vật bước vào đại điện.",
            profiles = emptyList(),
            storyNote = "",
            expressiveAdjustment = false,
            speedLimitPct = 0,
            pitchLimitPct = 0,
            volumeLimitPct = 0,
            expressionPrompt = "",
            includeVoiceCast = false,
            includeSceneMusic = true,
            tracks = musicTracks,
            context = NarrationPlanContext(
                previousChapterEnding = previousTail,
                activeTrackId = "music-real-a",
                incomingSource = "final_scene",
            ),
            includeAudioDirection = true,
        )
        val ambienceCatalog = XpkUnifiedNarrationPrompt.buildCatalog(ambienceTracks, "test-amb")
        val sfxCatalog = XpkUnifiedNarrationPrompt.buildCatalog(sfxTracks, "test-sfx")
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base,
            title = "Chương mới",
            includeVoiceCast = false,
            includeSceneMusic = true,
            includeAmbience = true,
            includeSoundEffects = true,
            ambienceTracks = ambienceTracks,
            soundEffectTracks = sfxTracks,
            previousChapterTail = previousTail,
            incomingAmbienceId = "amb-real-rain",
            ambienceCatalog = ambienceCatalog,
            sfxCatalog = sfxCatalog,
        )

        assertEquals(1, Regex("TAIL_UNIQUE_MARKER").findAll(prompt).count())
        assertTrue(prompt.contains("Dữ liệu chương hiện tại luôn có ưu tiên cao hơn continuity chương trước"))
        assertTrue(prompt.contains("CONTINUITY_CONTEXT CHUNG"))
        assertTrue(prompt.contains("track_id=\"0\""))
        assertTrue(prompt.contains("tuyệt đối không xuất ambience_id=\"NONE\""))
        assertTrue(prompt.contains("tuyệt đối không xuất effect_id=\"NONE\""))
        assertTrue(prompt.contains("Không có trần số SFX trong chương"))
        assertTrue(prompt.contains("Nếu các UNIT sau không nhắc lại nguồn âm"))
        assertTrue(prompt.contains("Độ dài vật lý của file không quyết định ranh giới scene"))
        assertTrue(prompt.contains("asset tổng hợp"))
        assertFalse(prompt.contains("\"music_scenes\": []"))
    }

    @Test
    fun shuffledCatalogBundleIsTheSingleSourceForPromptAliases() {
        val ambienceCatalog = XpkUnifiedNarrationPrompt.buildCatalog(ambienceTracks, "bundle-amb")
        val sfxCatalog = XpkUnifiedNarrationPrompt.buildCatalog(sfxTracks, "bundle-sfx")
        val base = XpkVoiceCastPrompt.build(
            title = "Âm thanh",
            body = "Mưa kéo dài.\nMột tia sét đánh gần.",
            profiles = emptyList(),
            storyNote = "",
            expressiveAdjustment = false,
            speedLimitPct = 0,
            pitchLimitPct = 0,
            volumeLimitPct = 0,
            expressionPrompt = "",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAudioDirection = true,
        )
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base,
            title = "Âm thanh",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = true,
            includeSoundEffects = true,
            ambienceTracks = ambienceTracks,
            soundEffectTracks = sfxTracks,
            ambienceCatalog = ambienceCatalog,
            sfxCatalog = sfxCatalog,
        )

        assertEquals(ambienceCatalog.items.map { it.promptId }.toSet(), ambienceCatalog.aliasToId.keys)
        assertEquals(sfxCatalog.items.map { it.promptId }.toSet(), sfxCatalog.aliasToId.keys)
        ambienceCatalog.items.forEach { item ->
            assertEquals(item.id, ambienceCatalog.aliasToId.getValue(item.promptId))
            assertTrue(prompt.contains("${item.promptId} | ${item.description}"))
        }
        sfxCatalog.items.forEach { item ->
            assertEquals(item.id, sfxCatalog.aliasToId.getValue(item.promptId))
            assertTrue(prompt.contains("${item.promptId} | ${item.description}"))
        }
        listOf(
            "amb-real-rain",
            "amb-real-wind",
            "sfx-real-thunder",
            "sfx-real-sword",
            "Rain File.wav",
            "Wind File.wav",
            "Thunder File.wav",
            "Sword File.wav",
        ).forEach { leaked -> assertFalse(prompt.contains(leaked)) }
    }

    @Test
    fun freesoundPromptRequiresShortSearchableQueries() {
        val base = XpkVoiceCastPrompt.build(
            title = "Freesound",
            body = "Gió rít qua rừng. Một khối đá đổ sập.",
            profiles = emptyList(),
            storyNote = "",
            expressiveAdjustment = false,
            speedLimitPct = 0,
            pitchLimitPct = 0,
            volumeLimitPct = 0,
            expressionPrompt = "",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAudioDirection = true,
        )
        val prompt = XpkUnifiedNarrationPrompt.compose(
            base = base,
            title = "Freesound",
            includeVoiceCast = false,
            includeSceneMusic = false,
            includeAmbience = false,
            includeSoundEffects = false,
            ambienceTracks = emptyList(),
            soundEffectTracks = emptyList(),
            includeFreesoundAudioRequirements = true,
            freesoundRequirementKinds = setOf(vn.nghetruyen.app.audio.AudioAssetKind.MUSIC, vn.nghetruyen.app.audio.AudioAssetKind.AMBIENCE, vn.nghetruyen.app.audio.AudioAssetKind.SFX),
            previousChapterTail = "Nhân vật vẫn đứng trên sườn núi tuyết trong gió mạnh.",
            incomingFreesoundMusicQuery = "dark fantasy cinematic",
            incomingFreesoundAmbienceQueries = listOf("snow wind", "mountain wind"),
        )
        assertTrue(prompt.contains("ưu tiên 2 từ"))
        assertTrue(prompt.contains("không quá 3 search term"))
        assertTrue(prompt.contains("Freesound mặc định coi các term là bắt buộc"))
        assertTrue(prompt.contains("debris crash"))
        assertTrue(prompt.contains("forest wind"))
        assertTrue(prompt.contains("CÙNG NGUYÊN TẮC VỚI MODE 2"))
        assertTrue(prompt.contains("Im lặng là lựa chọn bình đẳng với nhạc"))
        assertTrue(prompt.contains("Không có độ dài tối thiểu cho AMBIENCE"))
        assertTrue(prompt.contains("Độ dài vật lý của file ambience KHÔNG quyết định ranh giới scene"))
        assertTrue(prompt.contains("ONE-SHOT"))
        assertTrue(prompt.contains("COUNTED REPEAT"))
        assertTrue(prompt.contains("ACTION LOOP"))
        assertTrue(prompt.contains("không gửi catalog local", ignoreCase = true))
        assertTrue(prompt.contains("MODE3_PREVIOUS_BOUNDARY_MUSIC_QUERY: dark fantasy cinematic"))
        assertTrue(prompt.contains("- snow wind"))
        assertTrue(prompt.contains("- mountain wind"))
        assertTrue(prompt.contains("không phải lệnh bắt buộc giữ", ignoreCase = true))
        assertTrue(prompt.contains("giữ NGUYÊN VĂN query"))
        assertTrue(prompt.contains("sang chương mới KHÔNG phải là lý do đổi ambience"))
        assertTrue(prompt.contains("im lặng tốt hơn một âm sai cảnh"))
        assertFalse(prompt.contains("TRACK_CATALOG"))
        assertFalse(prompt.contains("AMBIENCE_CATALOG"))
        assertFalse(prompt.contains("SFX_CATALOG"))
    }
}
