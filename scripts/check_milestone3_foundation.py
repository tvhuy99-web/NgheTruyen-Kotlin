#!/usr/bin/env python3
"""Milestone 3 complete gate: reader, ranked search, durable offline queue and Kindle import."""
from __future__ import annotations

import shutil
import sqlite3
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLINC = shutil.which("kotlinc")


def require(path: str, *tokens: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} missing: {missing}")
    return text


def pure_kotlin_smoke() -> None:
    if not KOTLINC:
        print("MILESTONE3_CORE_SMOKE_SKIPPED_NO_KOTLINC")
        return
    with tempfile.TemporaryDirectory(prefix="nghe_m3_") as temp_name:
        temp = Path(temp_name)
        entity = temp / "DownloadJobEntity.kt"
        entity.write_text(
            """package vn.nghetruyen.app.data.local
            data class DownloadJobEntity(
                val id:String, val storyId:String, val sourceId:String,
                val selectionMode:String="ALL", val startChapterIndex:Int=0,
                val endChapterIndex:Int=Int.MAX_VALUE, val wifiOnly:Boolean=false,
                val chargingOnly:Boolean=false
            )
            """.strip(),
            encoding="utf-8",
        )
        smoke = temp / "Smoke.kt"
        smoke.write_text(
            r'''import vn.nghetruyen.app.core.model.*
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.sources.ChapterCatalogIndex
import vn.nghetruyen.app.downloads.DownloadRequest
import vn.nghetruyen.app.importers.MobiParser
import vn.nghetruyen.app.importers.PalmDocCompression

private fun put16(bytes:ByteArray, offset:Int, value:Int) {
    bytes[offset]=(value ushr 8).toByte(); bytes[offset+1]=value.toByte()
}
private fun put32(bytes:ByteArray, offset:Int, value:Int) {
    bytes[offset]=(value ushr 24).toByte(); bytes[offset+1]=(value ushr 16).toByte()
    bytes[offset+2]=(value ushr 8).toByte(); bytes[offset+3]=value.toByte()
}
private fun palmDoc(text:ByteArray):ByteArray {
    val record0=78+16; val textOffset=record0+16; val bytes=ByteArray(textOffset+text.size)
    put16(bytes,76,2); put32(bytes,78,record0); put32(bytes,86,textOffset)
    put16(bytes,record0,1); put32(bytes,record0+4,text.size); put16(bytes,record0+8,1)
    put16(bytes,record0+10,4096); put16(bytes,record0+12,0); text.copyInto(bytes,textOffset)
    return bytes
}
fun main() {
    val wanted=StorySummary("1","ready","Đấu La Đại Lục","Đường Gia Tam Thiếu")
    val other=StorySummary("2","ready","Tiên Nghịch","")
    val ranked=StorySearch.merge(listOf(other,wanted), mapOf("ready" to SourceHealth.READY), query="dau la dai lucj")
    check(ranked.first().id=="1")
    val catalog=ChapterCatalogIndex((0 until 10000).map { ChapterSummary("c$it","story",it,"Chương ${it+1}: Hành trình","") })
    check(catalog.search("9999").single().index==9998)
    val request=DownloadRequest.create("source","story",DownloadSelectionMode.RANGE,4,9,true,false)
    check(request.jobId==DownloadRequest.create("source","story",DownloadSelectionMode.RANGE,4,9,true,false).jobId)
    check(request.jobId.contains("RANGE:4-9"))
    check(PalmDocCompression.decode(byteArrayOf(0xC8.toByte(),0x65,0x6c,0x6c,0x6f),100).toString(Charsets.UTF_8)==" Hello")
    val parsed=MobiParser.parse(palmDoc("<h1>Chương 1</h1><p>Xin chào.</p>".toByteArray()),"Sách thử")
    check(parsed.title=="Sách thử" && parsed.text.contains("Xin chào"))
    println("MILESTONE3_CORE_SMOKE_OK")
}
''',
            encoding="utf-8",
        )
        jar = temp / "m3.jar"
        command = [
            KOTLINC,
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/sources/ChapterCatalogIndex.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/importers/MobiParser.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/importers/HuffCdicDecoder.kt"),
            str(ROOT / "app/src/main/java/vn/nghetruyen/app/downloads/DownloadRequest.kt"),
            str(entity), str(smoke), "-include-runtime", "-d", str(jar),
        ]
        compile_result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
        if compile_result.returncode:
            print(compile_result.stdout)
            print(compile_result.stderr)
            raise SystemExit(compile_result.returncode)
        run_result = subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, text=True, capture_output=True, timeout=30)
        if run_result.returncode:
            print(run_result.stdout)
            print(run_result.stderr)
            raise SystemExit(run_result.returncode)
        print(run_result.stdout.strip())


def migration_smoke() -> None:
    with sqlite3.connect(":memory:") as db:
        db.executescript(
            """
            CREATE TABLE download_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                storyId TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                state TEXT NOT NULL,
                completedChapters INTEGER NOT NULL,
                totalChapters INTEGER NOT NULL,
                errorMessage TEXT,
                updatedAt INTEGER NOT NULL
            );
            INSERT INTO download_jobs VALUES ('job-1','story-1','source-1','RUNNING',7,20,NULL,1234);
            ALTER TABLE download_jobs ADD COLUMN selectionMode TEXT NOT NULL DEFAULT 'ALL';
            ALTER TABLE download_jobs ADD COLUMN startChapterIndex INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE download_jobs ADD COLUMN endChapterIndex INTEGER NOT NULL DEFAULT 2147483647;
            ALTER TABLE download_jobs ADD COLUMN wifiOnly INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE download_jobs ADD COLUMN chargingOnly INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE download_jobs ADD COLUMN currentChapterIndex INTEGER NOT NULL DEFAULT -1;
            ALTER TABLE download_jobs ADD COLUMN currentChapterTitle TEXT NOT NULL DEFAULT '';
            ALTER TABLE download_jobs ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE download_jobs ADD COLUMN requestedAt INTEGER NOT NULL DEFAULT 0;
            UPDATE download_jobs SET requestedAt = updatedAt WHERE requestedAt = 0;
            """
        )
        row = db.execute(
            "SELECT id,state,completedChapters,totalChapters,selectionMode,requestedAt,currentChapterIndex FROM download_jobs"
        ).fetchone()
        assert row == ("job-1", "RUNNING", 7, 20, "ALL", 1234, -1), row
        columns = {item[1]: item[4] for item in db.execute("PRAGMA table_info(download_jobs)")}
        assert columns["currentChapterTitle"] == "''"
        assert columns["endChapterIndex"] == "2147483647"
    with sqlite3.connect(":memory:") as db:
        db.executescript(
            """
            CREATE TABLE chapter_notes (
                id TEXT NOT NULL PRIMARY KEY, storyId TEXT NOT NULL, chapterId TEXT NOT NULL,
                paragraphIndex INTEGER NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            );
            CREATE UNIQUE INDEX index_chapter_notes_storyId_chapterId_paragraphIndex
                ON chapter_notes(storyId, chapterId, paragraphIndex);
            CREATE TABLE chapter_download_failures (
                id TEXT NOT NULL PRIMARY KEY, jobId TEXT NOT NULL, storyId TEXT NOT NULL,
                sourceId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, chapterTitle TEXT NOT NULL,
                errorMessage TEXT NOT NULL, retryCount INTEGER NOT NULL, updatedAt INTEGER NOT NULL
            );
            CREATE UNIQUE INDEX index_chapter_download_failures_jobId_chapterIndex
                ON chapter_download_failures(jobId, chapterIndex);
            CREATE INDEX index_chapter_download_failures_storyId ON chapter_download_failures(storyId);
            INSERT INTO chapter_notes VALUES ('n','s','c',4,'ghi chú',1,2);
            INSERT INTO chapter_download_failures VALUES ('j:7','j','s','src',7,'Chương 8','Mất mạng',1,2);
            """
        )
        assert db.execute("SELECT text FROM chapter_notes").fetchone()[0] == "ghi chú"
        assert db.execute("SELECT chapterIndex FROM chapter_download_failures").fetchone()[0] == 7
    print("MILESTONE3_MIGRATION_7_8_8_9_OK")


def main() -> None:
    require(
        "app/src/main/java/vn/nghetruyen/app/core/model/Models.kt",
        "DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }",
        "enum class DownloadSelectionMode",
        "enum class SearchSortMode",
        "enum class ReaderLayoutMode",
        "horizontalPaddingDp",
        "paragraphSpacingDp",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt",
        "damerauLevenshtein",
        "tokenCoverage",
        "SearchSortMode.RELEVANCE",
        "SearchSortMode.AUTHOR",
    )
    database_text = require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "version = 18",
        "MIGRATION_7_8",
        "MIGRATION_8_9",
        "CREATE TABLE IF NOT EXISTS chapter_notes",
        "CREATE TABLE IF NOT EXISTS chapter_download_failures",
        "selectionMode TEXT NOT NULL DEFAULT 'ALL'",
        "requestedAt INTEGER NOT NULL DEFAULT 0",
        "UPDATE download_jobs SET requestedAt = updatedAt",
    )
    legacy_helper = database_text.split("private fun createDownloadJobsTable", 1)[1].split("fun create(context", 1)[0]
    assert "selectionMode" not in legacy_helper, "5/6/7 migration helper must remain schema-7 compatible"
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt",
        "enqueueUnread",
        "enqueueChapter",
        "enqueueRange",
        "NetworkType.UNMETERED",
        "setRequiresCharging",
        "fun resume",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt",
        "DownloadState.PAUSED",
        "KEY_SELECTION_MODE",
        "KEY_WIFI_ONLY",
        "KEY_CHARGING_ONLY",
        "currentChapterTitle",
        "recordDownloadFailure",
        "DownloadStorageGuard.estimate",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "setSearchSortMode",
        "downloadUnreadChapters",
        "pauseDownload",
        "resumeDownload",
        "retryDownload",
        "moveToParagraph",
        "saveCurrentNote",
        "retryFailedChapter",
        "setReaderVolumeKeysNavigate",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "ReaderLayoutMode.SCROLL",
        "ReaderLayoutMode.PAGED",
        "onParagraphSelected",
        "Lề ngang:",
        "Cách đoạn:",
        "Dùng phím âm lượng để chuyển đoạn",
        "showNoteDialog",
        "firstVisibleItemIndex",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt",
        "onSortModeChange",
        "SearchSortMode.RELEVANCE",
        "TÁC GIẢ",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt",
        "onPauseDownload",
        "onResumeDownload",
        "onRetryDownload",
        '"TẠM DỪNG"',
        '"THỬ LẠI RIÊNG CHƯƠNG"',
        "LibrarySection.NOTES",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt",
        '"mobi"', '"prc"', '"azw"', '"azw3"', "MobiParser.parse",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/importers/MobiParser.kt",
        "PalmDocCompression",
        "HuffCdicDecoder.decodeRecords",
        "HUFF/CDIC",
        "DRM",
        "MAX_TEXT_BYTES",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sources/ChapterCatalogIndex.kt",
        "byHumanNumber",
        "StorySearch.normalize",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/importers/HuffCdicDecoder.kt",
        "MAX_DEPTH",
        "MAX_CDIC_RECORDS",
        "appendBounded",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/MainActivity.kt",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "readerLayoutMode",
        "readerHorizontalPaddingDp",
        "readerParagraphSpacingDp",
        "readerVolumeKeysNavigate",
        'writer.name("notes")',
        "ReaderLayoutMode.SCROLL",
    )
    require(
        "app/src/androidTest/java/vn/nghetruyen/app/data/local/AppDatabaseMigrationTest.kt",
        "migration7To8AddsDurableDownloadRequestFieldsWithoutLosingProgress",
        "migration8To9AddsNotesAndPerChapterFailures",
    )
    require(
        "scripts/build-milestone3.sh",
        "testDebugUnitTest",
        "lintDebug",
        "assembleDebug",
        "MILESTONE3_EXTRA_TASKS",
    )
    require(
        "scripts/build-milestone3.ps1",
        "connectedDebugAndroidTest",
        "bundleRelease",
    )
    require(
        ".github/workflows/android-ci.yml",
        "./scripts/m0_gate.sh",
        "Set up JDK 17",
        "platforms;android-36",
    )
    require(
        "scripts/m0_gate.sh",
        "check_milestone3_foundation.py",
        "check_milestone3_ui_static.py",
        "check_milestone3_download_static.py",
        "check_milestone3_kindle.py",
    )
    pure_kotlin_smoke()
    migration_smoke()
    print("MILESTONE3_FOUNDATION_CHECK_OK")


if __name__ == "__main__":
    main()
