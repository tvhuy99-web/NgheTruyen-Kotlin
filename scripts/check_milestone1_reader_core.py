#!/usr/bin/env python3
"""Offline executable gate for Milestone 1 reader invariants."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")
KOTLIN = shutil.which("kotlin")


def require_text(path: str, *tokens: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit(f"{path} missing reader wiring: {missing}")


def main() -> None:
    require_text(
        "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
        "snapshot.currentSpeechText",
        "PlaybackQueueStore.advanceSpeechChunk()",
        "snapshot.speechChunkIndex",
        "ReaderDocumentNormalizer.normalize(cachedByIndex)",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "ReaderDocumentNormalizer.normalize(content.value)",
        "ReaderPositionResolver.resolve(",
        "Chương không có nội dung có thể đọc.",
        "ReaderChapterNavigation.next(",
        "ReaderChapterNavigation.previous(",
    )
    if not KOTLINC or not KOTLIN:
        print("M1_READER_CORE_STATIC_OK; EXECUTABLE_CHECK_SKIPPED: Kotlin CLI unavailable")
        return

    harness = r'''
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.ReaderChapterNavigation
import vn.nghetruyen.app.playback.ReaderTextChunker
import vn.nghetruyen.app.playback.ReaderPositionResolver
import vn.nghetruyen.app.playback.ReaderVolumeKeyPolicy

fun checkCondition(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val longText = "a".repeat(7200)
    PlaybackQueueStore.load(
        sourceId = "source",
        storyId = "story",
        chapterId = "chapter",
        chapterTitle = "Chapter",
        chapterIndex = 0,
        paragraphs = listOf(longText, "second"),
    )
    checkCondition(PlaybackQueueStore.state.value.paragraphs.size == 2, "visible paragraph count changed")
    checkCondition(PlaybackQueueStore.state.value.speechChunks.size == 4, "unexpected speech chunk count")
    checkCondition(PlaybackQueueStore.state.value.currentParagraph == longText, "full paragraph not retained")
    checkCondition(PlaybackQueueStore.advanceSpeechChunk(), "first chunk advance failed")
    checkCondition(PlaybackQueueStore.state.value.paragraphIndex == 0, "reader index shifted inside long paragraph")
    checkCondition(PlaybackQueueStore.advanceSpeechChunk(), "second chunk advance failed")
    checkCondition(!PlaybackQueueStore.advanceSpeechChunk(), "advanced beyond paragraph boundary")
    checkCondition(PlaybackQueueStore.moveBy(1), "paragraph move failed")
    checkCondition(PlaybackQueueStore.state.value.paragraphIndex == 1, "wrong second paragraph index")
    checkCondition(PlaybackQueueStore.state.value.currentSpeechText == "second", "wrong second paragraph speech")
    checkCondition(PlaybackQueueStore.state.value.speechChunks.all { it.text.length <= ReaderTextChunker.SAFE_TTS_CHARS }, "TTS chunk too large")

    val unicode = ReaderTextChunker.normalizeParagraphs(listOf("  Tiếng Việt 👩🏽‍🚀  ", "\n\t", "第二段。"))
    checkCondition(unicode == listOf("Tiếng Việt 👩🏽‍🚀", "第二段。"), "Unicode or blank normalization changed")
    checkCondition(ReaderPositionResolver.resolve("c", 5, forcedParagraphIndex = 99) == 4, "forced position not clamped")
    checkCondition(ReaderPositionResolver.resolve("c", 5, savedChapterId = "other", savedParagraphIndex = 3) == 0, "foreign chapter progress accepted")
    checkCondition(ReaderPositionResolver.resolve("c", 0, forcedParagraphIndex = 3) == 0, "empty chapter position invalid")
    checkCondition(ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_UP) == -1, "volume up mapping wrong")
    checkCondition(ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN) == 1, "volume down mapping wrong")
    checkCondition(ReaderVolumeKeyPolicy.paragraphDelta(true, true, true, 1, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN) == null, "repeat volume event consumed")
    checkCondition(ReaderVolumeKeyPolicy.paragraphDelta(false, true, true, 0, ReaderVolumeKeyPolicy.Key.VOLUME_DOWN) == null, "volume event consumed outside reader")

    val hugeCatalog = (0 until 10_000).map { index ->
        ChapterSummary("id-$index", "story", index * 2 + 10, "Chương ${index + 1}", "u-$index")
    }
    checkCondition(ReaderChapterNavigation.next(hugeCatalog[4_999], hugeCatalog, null)?.id == "id-5000", "10k catalog next failed")
    checkCondition(ReaderChapterNavigation.previous(hugeCatalog[5_000], hugeCatalog, null)?.id == "id-4999", "10k catalog previous failed")

    val chapters = listOf(
        ChapterSummary("a", "story", 10, "11", "u11"),
        ChapterSummary("b", "story", 20, "21", "u21"),
        ChapterSummary("c", "story", 30, "31", "u31"),
    )
    checkCondition(ReaderChapterNavigation.next(chapters[1], chapters, null)?.id == "c", "next used source index as list offset")
    checkCondition(ReaderChapterNavigation.previous(chapters[1], chapters, null)?.id == "a", "previous used source index as list offset")
    println("M1_READER_CORE_EXECUTABLE_OK")
}
'''
    with tempfile.TemporaryDirectory(prefix="nghe_m1_reader_") as td:
        temp = Path(td)
        harness_file = temp / "Harness.kt"
        output = temp / "reader-core.jar"
        harness_file.write_text(harness, encoding="utf-8")
        coroutines = Path(KOTLINC).resolve().parents[1] / "lib" / "kotlinx-coroutines-core-jvm.jar"
        command = [
            KOTLINC,
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/ReaderChapterNavigation.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/ReaderPositionResolver.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/playback/ReaderVolumeKeyPolicy.kt"),
            str(harness_file),
        ]
        if coroutines.is_file():
            command += ["-cp", str(coroutines)]
        command += ["-d", str(output)]
        subprocess.run(command, cwd=ROOT, check=True, timeout=90)
        runtime_classpath = str(output)
        if coroutines.is_file():
            runtime_classpath += ":" + str(coroutines)
        subprocess.run([KOTLIN, "-classpath", runtime_classpath, "HarnessKt"], cwd=ROOT, check=True, timeout=30)


if __name__ == "__main__":
    main()
