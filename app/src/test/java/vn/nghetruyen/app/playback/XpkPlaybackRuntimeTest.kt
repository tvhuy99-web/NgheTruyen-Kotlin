package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.ai.XpkVoiceCastSplitter

class XpkPlaybackRuntimeTest {
    @Test
    fun playbackTimelineIsExactlyTheSplitterTimeline() {
        val title = "Chương thử"
        val paragraphs = listOf(
            "Lâm nói: \"Đi thôi.\" Rồi hắn quay đi.",
            "— Đứng lại! — cô gái gọi.",
            "Trong lòng hắn nghĩ: “Có lẽ mình đã sai.”",
        )
        val expected = XpkVoiceCastSplitter.buildUnits(title, paragraphs.joinToString("\n"))
        val actual = XpkPlaybackRuntime.buildSpeechTimeline(title, paragraphs)

        assertEquals(expected.map { it.id }, actual.map { it.unitId })
        assertEquals(expected.map { it.text }, actual.map { it.text })
        assertEquals(expected.map { it.unitKind }, actual.map { it.unitKind })
        assertEquals(expected.map { it.fixedVoice }, actual.map { it.fixedVoiceId })
    }

    @Test
    fun timelineUsesUnitIdsWhileDialogueGroupIdsStayMetadataOnly() {
        assertEquals(0, XpkPlaybackRuntime.paragraphIndex("TITLE-U01"))
        assertEquals(2, XpkPlaybackRuntime.paragraphIndex("P0003-U02"))
        assertEquals(-1, XpkPlaybackRuntime.paragraphIndex("P0003-D04"))
        assertEquals(-1, XpkPlaybackRuntime.paragraphIndex("bad-id"))
    }

    @Test
    fun checkpointSpeechIndexRestoresTheExactXpkDialogueUnit() {
        PlaybackQueueStore.load(
            sourceId = "test",
            storyId = "story",
            chapterId = "chapter",
            chapterIndex = 1,
            chapterTitle = "Tiêu đề",
            paragraphs = listOf("Nam nói: \"Một. Hai.\" Sau đó anh im lặng."),
        )
        val initial = PlaybackQueueStore.state.value
        val targetIndex = initial.speechChunks.indexOfFirst { it.fixedVoiceId == null }
        assertTrue(targetIndex >= 0)
        val target = initial.speechChunks[targetIndex]
        assertTrue(target.unitId.matches(Regex("P\\d{4}-U\\d{2}")))

        PlaybackQueueStore.restoreSpeechPosition(target.paragraphIndex, targetIndex)

        assertEquals(target.unitId, PlaybackQueueStore.state.value.currentUnitId)
        assertEquals(target.text, PlaybackQueueStore.state.value.currentSpeechText)
    }

    @Test
    fun initialManualReassignClearsOldChapterTransferSummary() {
        PlaybackQueueStore.load(
            sourceId = "test",
            storyId = "story",
            chapterId = "chapter-reassign",
            chapterIndex = 1,
            chapterTitle = "Tiêu đề",
            paragraphs = listOf("Nội dung."),
        )
        PlaybackQueueStore.rememberFreesoundTransferSummary(
            chapterId = "chapter-reassign",
            downloadedAssets = 4,
            reusedAssets = 2,
        )

        PlaybackQueueStore.setNarrationAutomation(
            stage = NarrationAutomationStage.CURRENT_PLANNING,
            progress = 0.2f,
            message = "Đang phân vai chương hiện tại.",
        )

        assertEquals(
            FreesoundTransferSummary(),
            PlaybackQueueStore.consumeFreesoundTransferSummary(
                chapterId = "chapter-reassign",
                currentDownloadedAssets = 0,
                currentReusedAssets = 0,
            ),
        )
    }

    @Test
    fun internalRetryDoesNotEraseCurrentTransferSummary() {
        PlaybackQueueStore.load(
            sourceId = "test",
            storyId = "story",
            chapterId = "chapter-retry",
            chapterIndex = 1,
            chapterTitle = "Tiêu đề",
            paragraphs = listOf("Nội dung."),
        )
        PlaybackQueueStore.rememberFreesoundTransferSummary(
            chapterId = "chapter-retry",
            downloadedAssets = 3,
            reusedAssets = 1,
        )

        PlaybackQueueStore.setNarrationAutomation(
            stage = NarrationAutomationStage.CURRENT_PLANNING,
            progress = 0.2f,
            message = "Đang thử phân vai lại lần 2.",
        )

        assertEquals(
            FreesoundTransferSummary(downloadedAssets = 3, reusedAssets = 1),
            PlaybackQueueStore.consumeFreesoundTransferSummary(
                chapterId = "chapter-retry",
                currentDownloadedAssets = 0,
                currentReusedAssets = 0,
            ),
        )
    }

    @Test
    fun voiceTransformIsAddressedByUnitIdNotParagraphIndex() {
        val json = """
            {
              "engine":"xpk-unit-v8",
              "assignments":[
                {"id":"P0001-U01","voice":"voice-a","speed_adjust_pct":3,"pitch_adjust_pct":-2,"volume_adjust_pct":1},
                {"id":"P0001-U02","voice":"voice-b","speed_adjust_pct":0,"pitch_adjust_pct":0,"volume_adjust_pct":0}
              ]
            }
        """.trimIndent()
        val parsed = XpkPlaybackRuntime.parseVoiceAssignments(
            json,
            listOf("TITLE-U01", "P0001-U01", "P0001-U02"),
        )

        assertEquals("voice-a", parsed.getValue("P0001-U01").voiceId)
        assertEquals(3f, parsed.getValue("P0001-U01").speedAdjustPct)
        assertEquals("voice-b", parsed.getValue("P0001-U02").voiceId)
        assertEquals(2, parsed.size)
    }

    @Test
    fun sceneIntervalsSwitchAtTheExactUnitBoundary() {
        val units = listOf("TITLE-U01", "P0001-U01", "P0001-U02", "P0001-U03", "P0002-U01")
        val json = """
            {
              "engine":"xpk-ai-full-authority-v1",
              "mode":"ai_full_authority",
              "music_scenes":[
                {"start_id":"TITLE-U01","end_id":"P0001-U02","track_id":"a"},
                {"start_id":"P0001-U03","end_id":"P0002-U01","track_id":"b"}
              ]
            }
        """.trimIndent()

        val timeline = XpkPlaybackRuntime.parseSceneTimeline(json, units, listOf("a", "b"))

        assertEquals("a", timeline["P0001-U02"])
        assertEquals("b", timeline["P0001-U03"])
        assertEquals("b", timeline["P0002-U01"])
        assertEquals(units.size, timeline.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sceneRuntimeRejectsGapsInsteadOfGuessing() {
        val units = listOf("P0001-U01", "P0001-U02", "P0001-U03")
        val json = """
            {
              "music_scenes":[
                {"start_id":"P0001-U01","end_id":"P0001-U01","track_id":"a"},
                {"start_id":"P0001-U03","end_id":"P0001-U03","track_id":"b"}
              ]
            }
        """.trimIndent()
        XpkPlaybackRuntime.parseSceneTimeline(json, units, listOf("a", "b"))
    }
}
