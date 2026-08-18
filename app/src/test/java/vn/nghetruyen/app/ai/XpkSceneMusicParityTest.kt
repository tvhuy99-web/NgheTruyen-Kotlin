package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XpkSceneMusicParityTest {
    @Test
    fun catalogUsesFreeformDescriptionAndKeepsFilenameLocalOnly() {
        val description = "Sắc thái: kiềm chế, cô độc | Dùng: cảnh suy tư | Tránh: giao chiến"
        val rows = XpkSceneMusicParity.normalizeTracks(
            listOf(SceneMusicTrackOption("track-a", "Cô độc.mp3", emptyList(), description)),
        )
        assertEquals(1, rows.size)
        assertEquals("Cô độc", rows.single().name)
        assertEquals(description, rows.single().description)
    }

    @Test
    fun catalogDescriptionIsCappedAtThreeHundredCodePoints() {
        val description = "ổ".repeat(340)
        val row = XpkSceneMusicParity.normalizeTracks(
            listOf(SceneMusicTrackOption("track-a", "Nhạc.wav", emptyList(), description)),
        ).single()
        assertEquals(300, row.description.codePointCount(0, row.description.length))
    }

    @Test
    fun promptShufflesThenAliasesWithoutLeakingNameOrRealIdAndMapsIncomingTrack() {
        val block = XpkSceneMusicParity.promptBlock(
            title = "Chương 9",
            firstUnitId = "P0001-U01",
            lastUnitId = "P0004-U01",
            tracks = listOf(
                SceneMusicTrackOption("track-a", "A.mp3", emptyList(), "Sắc thái: tĩnh | Dùng: suy tư | Tránh: giao chiến"),
                SceneMusicTrackOption("track-b", "B.ogg", emptyList(), "Sắc thái: căng | Dùng: xung đột | Tránh: nghỉ ngơi"),
                SceneMusicTrackOption("track-c", "C.wav", emptyList(), "Sắc thái: ấm | Dùng: đoàn tụ | Tránh: hiểm nguy"),
            ),
            context = NarrationPlanContext(
                previousChapterEnding = "[PREVIOUS_UNIT offset=-1 | kind=narration] Kết chương trước.",
                activeTrackId = "track-b",
                incomingSource = "final_scene",
            ),
        )
        val incomingAlias = block.trackAliasToId.entries.single { it.value == "track-b" }.key

        assertEquals("track-b", block.incomingTrackId)
        assertEquals(incomingAlias, block.incomingPromptTrackId)
        assertTrue(block.instructions.contains("INCOMING_TRACK_ID: $incomingAlias"))
        assertTrue(block.instructions.contains("NGUỒN XÁC ĐỊNH: final_scene"))
        assertTrue(block.instructions.contains("[PREVIOUS_UNIT offset=-1"))
        assertTrue(block.instructions.contains("Không đặt mục tiêu về số lần đổi nhạc"))
        assertTrue(block.instructions.contains("Đổi tại đúng UNIT đầu tiên"))
        assertTrue(block.instructions.contains("Ổn định quan trọng hơn phản ứng theo từng câu"))
        assertTrue(block.instructions.contains("0 | Sắc thái: im lặng"))
        assertTrue(block.instructions.contains("Im lặng là một lựa chọn bình đẳng với track"))
        assertEquals(setOf("1", "2", "3"), block.trackAliasToId.keys)
        block.tracks.forEachIndexed { index, track ->
            assertEquals((index + 1).toString(), track.promptId)
            assertEquals(track.id, block.trackAliasToId.getValue(track.promptId))
            assertTrue(block.instructions.contains("${track.promptId} | ${track.description}"))
        }
        assertFalse(block.instructions.contains("track-a"))
        assertFalse(block.instructions.contains("track-b"))
        assertFalse(block.instructions.contains("track-c"))
        assertFalse(block.instructions.contains("A.mp3"))
        assertFalse(block.instructions.contains("B.ogg"))
        assertFalse(block.instructions.contains("C.wav"))
        assertFalse(block.instructions.contains("Tối đa 12"))
        assertFalse(block.instructions.contains("cách nhau ít nhất 3"))
    }

    @Test
    fun blankDescriptionIsNotSentEvenWhenFilenameExists() {
        val rows = XpkSceneMusicParity.normalizeTracks(
            listOf(
                SceneMusicTrackOption("track-a", "Tên rất gợi ý Epic Battle.mp3", emptyList(), ""),
                SceneMusicTrackOption("track-b", "Tên khác.mp3", emptyList(), "Sắc thái: nhẹ | Dùng: nghỉ | Tránh: chiến"),
            ),
        )
        assertEquals(listOf("track-b"), rows.map { it.id })
    }

    @Test
    fun invalidIncomingBecomesInternalNoneAndPromptZero() {
        val block = XpkSceneMusicParity.promptBlock(
            title = "Chương",
            firstUnitId = "P0001-U01",
            lastUnitId = "P0001-U01",
            tracks = listOf(
                SceneMusicTrackOption(
                    "track-a",
                    "A.mp3",
                    emptyList(),
                    "Sắc thái: tĩnh | Dùng: suy tư | Tránh: giao chiến",
                ),
            ),
            context = NarrationPlanContext(activeTrackId = "missing"),
        )
        assertEquals(XpkSceneMusicParity.SILENCE_TRACK_ID, block.incomingTrackId)
        assertEquals(XpkSceneMusicParity.SILENCE_PROMPT_ID, block.incomingPromptTrackId)
        assertTrue(block.instructions.contains("INCOMING_TRACK_ID: 0"))
        assertFalse(block.instructions.contains("INCOMING_TRACK_ID: NONE"))
    }

    @Test
    fun continuityTailUsesExactlyLastFiveUnits() {
        val tail = XpkSceneMusicParity.continuityTailForPrompt(
            title = "Chương cũ",
            body = (1..7).joinToString("\n") { "Dòng kể $it." },
            maxUnits = 5,
        )
        val lines = tail.lineSequence().filter(String::isNotBlank).toList()
        assertEquals(5, lines.size)
        assertTrue(lines.first().contains("offset=-5"))
        assertTrue(lines.last().contains("offset=-1"))
        assertTrue(lines.first().contains("Dòng kể 3."))
        assertTrue(lines.last().contains("Dòng kể 7."))
        assertFalse(tail.contains("P000"))
    }

    @Test
    fun validatorMergesAdjacentScenesWithSameTrack() {
        val units = listOf("P0001-U01", "P0002-U01", "P0003-U01", "P0004-U01")
        val scenes = XpkSceneMusicParity.validateScenes(
            rows = listOf(
                XpkSceneMusicParity.RawScene("P0001-U01", "P0002-U01", "a"),
                XpkSceneMusicParity.RawScene("P0003-U01", "P0003-U01", "a"),
                XpkSceneMusicParity.RawScene("P0004-U01", "P0004-U01", "b"),
            ),
            validUnitIds = units,
            validTrackIds = listOf("a", "b"),
        )
        assertEquals(2, scenes.size)
        assertEquals("P0001-U01", scenes[0].startUnitId)
        assertEquals("P0003-U01", scenes[0].endUnitId)
        assertEquals("a", scenes[0].trackId)
        assertEquals("b", scenes[1].trackId)
    }

    @Test
    fun validatorAllowsIntentionalSilentScene() {
        val units = listOf("P0001-U01", "P0002-U01", "P0003-U01", "P0004-U01")
        val scenes = XpkSceneMusicParity.validateScenes(
            rows = listOf(
                XpkSceneMusicParity.RawScene(units[0], units[1], "a"),
                XpkSceneMusicParity.RawScene(units[2], units[3], XpkSceneMusicParity.SILENCE_TRACK_ID),
            ),
            validUnitIds = units,
            validTrackIds = listOf("a"),
        )
        assertEquals(2, scenes.size)
        assertEquals(XpkSceneMusicParity.SILENCE_TRACK_ID, scenes.last().trackId)
    }

    @Test
    fun validatorRejectsGapOverlapAndUnknownTrack() {
        val units = listOf("P0001-U01", "P0002-U01", "P0003-U01")
        assertFails {
            XpkSceneMusicParity.validateScenes(
                listOf(
                    XpkSceneMusicParity.RawScene("P0001-U01", "P0001-U01", "a"),
                    XpkSceneMusicParity.RawScene("P0003-U01", "P0003-U01", "b"),
                ),
                units,
                listOf("a", "b"),
            )
        }
        assertFails {
            XpkSceneMusicParity.validateScenes(
                listOf(
                    XpkSceneMusicParity.RawScene("P0001-U01", "P0002-U01", "a"),
                    XpkSceneMusicParity.RawScene("P0002-U01", "P0003-U01", "b"),
                ),
                units,
                listOf("a", "b"),
            )
        }
        assertFails {
            XpkSceneMusicParity.validateScenes(
                listOf(XpkSceneMusicParity.RawScene("P0001-U01", "P0003-U01", "missing")),
                units,
                listOf("a", "b"),
            )
        }
    }

    @Test
    fun validatorRejectsOneUnitMiddleSceneFlicker() {
        val units = (1..5).map { "P${it.toString().padStart(4, '0')}-U01" }
        assertFails {
            XpkSceneMusicParity.validateScenes(
                listOf(
                    XpkSceneMusicParity.RawScene(units[0], units[1], "a"),
                    XpkSceneMusicParity.RawScene(units[2], units[2], "b"),
                    XpkSceneMusicParity.RawScene(units[3], units[4], "a"),
                ),
                units,
                listOf("a", "b"),
            )
        }
    }

    @Test
    fun validatorAllowsManyStableScenesWithoutArbitraryMaximum() {
        val units = (1..20).map { "P${it.toString().padStart(4, '0')}-U01" }
        val rows = (0 until 10).map { scene ->
            XpkSceneMusicParity.RawScene(
                units[scene * 2],
                units[scene * 2 + 1],
                if (scene % 2 == 0) "a" else "b",
            )
        }
        val scenes = XpkSceneMusicParity.validateScenes(rows, units, listOf("a", "b"))
        assertEquals(10, scenes.size)
    }

    @Test
    fun fallbackKeepsRealIncomingOtherwiseUsesInternalSilence() {
        val units = listOf("P0001-U01", "P0002-U01")
        val incoming = XpkSceneMusicParity.fallbackScene(units, listOf("a", "b"), "b").single()
        assertEquals("b", incoming.trackId)
        assertEquals("P0001-U01", incoming.startUnitId)
        assertEquals("P0002-U01", incoming.endUnitId)

        val silent = XpkSceneMusicParity.fallbackScene(units, listOf("a", "b"), "missing").single()
        assertEquals(XpkSceneMusicParity.SILENCE_TRACK_ID, silent.trackId)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected validation to fail")
        } catch (_: IllegalArgumentException) {

        } catch (_: IllegalStateException) {

        }
    }
}
