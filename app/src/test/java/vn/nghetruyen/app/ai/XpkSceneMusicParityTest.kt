package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XpkSceneMusicParityTest {
    @Test
    fun catalogUsesFreeformDescriptionAndStripsAudioExtension() {
        val description = "Sắc thái: kiềm chế, cô độc; Dùng: cảnh suy tư; Tránh: giao chiến."
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
    fun promptContainsIncomingContinuityAndNoSceneCountTarget() {
        val block = XpkSceneMusicParity.promptBlock(
            title = "Chương 9",
            firstUnitId = "P0001-U01",
            lastUnitId = "P0004-U01",
            tracks = listOf(
                SceneMusicTrackOption("track-a", "A.mp3", emptyList(), "Sắc thái: tĩnh; Dùng: suy tư; Tránh: giao chiến"),
                SceneMusicTrackOption("track-b", "B.ogg", emptyList(), "Sắc thái: căng; Dùng: xung đột; Tránh: nghỉ ngơi"),
            ),
            context = NarrationPlanContext(
                previousChapterEnding = "[PREVIOUS_UNIT offset=-1 | kind=narration] Kết chương trước.",
                activeTrackId = "track-b",
                incomingSource = "final_scene",
            ),
        )
        assertTrue(block.instructions.contains("INCOMING_TRACK_ID: track-b"))
        assertTrue(block.instructions.contains("NGUỒN XÁC ĐỊNH: final_scene"))
        assertTrue(block.instructions.contains("[PREVIOUS_UNIT offset=-1"))
        assertTrue(block.instructions.contains("Không đặt mục tiêu về số lần đổi nhạc"))
        assertTrue(block.instructions.contains("Đổi tại đúng UNIT đầu tiên"))
        assertTrue(block.instructions.contains("track-a | A | Sắc thái: tĩnh"))
        assertFalse(block.instructions.contains("Tối đa 12"))
        assertFalse(block.instructions.contains("cách nhau ít nhất 3"))
    }

    @Test
    fun invalidIncomingBecomesNone() {
        val block = XpkSceneMusicParity.promptBlock(
            title = "Chương",
            firstUnitId = "P0001-U01",
            lastUnitId = "P0001-U01",
            tracks = listOf(SceneMusicTrackOption("track-a", "A", emptyList())),
            context = NarrationPlanContext(activeTrackId = "missing"),
        )
        assertEquals("NONE", block.incomingTrackId)
        assertTrue(block.instructions.contains("INCOMING_TRACK_ID: NONE"))
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
    fun validatorDoesNotImposeMaximumSceneCount() {
        val units = (1..20).map { "P${it.toString().padStart(4, '0')}-U01" }
        val rows = units.mapIndexed { index, id ->
            XpkSceneMusicParity.RawScene(id, id, if (index % 2 == 0) "a" else "b")
        }
        val scenes = XpkSceneMusicParity.validateScenes(rows, units, listOf("a", "b"))
        assertEquals(20, scenes.size)
    }

    @Test
    fun fallbackPrefersValidIncomingOtherwiseFirstCatalogTrack() {
        val units = listOf("P0001-U01", "P0002-U01")
        val incoming = XpkSceneMusicParity.fallbackScene(units, listOf("a", "b"), "b").single()
        assertEquals("b", incoming.trackId)
        assertEquals("P0001-U01", incoming.startUnitId)
        assertEquals("P0002-U01", incoming.endUnitId)

        val first = XpkSceneMusicParity.fallbackScene(units, listOf("a", "b"), "missing").single()
        assertEquals("a", first.trackId)
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
