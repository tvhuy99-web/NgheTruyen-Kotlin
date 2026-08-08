#!/usr/bin/env python3
"""Static gate for the XPK-parity product wiring added in Android 2.9.0."""

from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def source(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        raise AssertionError(f"Missing file: {path}")
    return file.read_text(encoding="utf-8")


def require(path: str, *markers: str) -> None:
    data = source(path)
    missing = [marker for marker in markers if marker not in data]
    if missing:
        raise AssertionError(f"{path}: missing parity markers: {missing}")


def forbid(path: str, *markers: str) -> None:
    data = source(path)
    found = [marker for marker in markers if marker in data]
    if found:
        raise AssertionError(f"{path}: stale/broken product markers remain: {found}")


def matching_parenthesis(data: str, opening: int) -> int:
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(opening, len(data)):
        char = data[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {'"', "'"}:
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("Unbalanced Kotlin parenthesis")


def split_top_level(value: str) -> list[str]:
    parts: list[str] = []
    start = 0
    round_depth = square_depth = curly_depth = angle_depth = 0
    quote: str | None = None
    escaped = False
    for index, char in enumerate(value):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {'"', "'"}:
            quote = char
        elif char == "(":
            round_depth += 1
        elif char == ")":
            round_depth -= 1
        elif char == "[":
            square_depth += 1
        elif char == "]":
            square_depth -= 1
        elif char == "{":
            curly_depth += 1
        elif char == "}":
            curly_depth -= 1
        elif char == "<":
            angle_depth += 1
        elif char == ">" and angle_depth:
            angle_depth -= 1
        elif char == "," and not any((round_depth, square_depth, curly_depth, angle_depth)):
            parts.append(value[start:index].strip())
            start = index + 1
    tail = value[start:].strip()
    if tail:
        parts.append(tail)
    return parts


def declaration_parameters(path: str, function: str) -> list[str]:
    data = source(path)
    match = re.search(rf"\bfun\s+{re.escape(function)}\s*\(", data)
    if match is None:
        raise AssertionError(f"{path}: declaration not found: {function}")
    opening = data.index("(", match.start())
    block = data[opening + 1 : matching_parenthesis(data, opening)]
    parameters: list[str] = []
    for item in split_top_level(block):
        name = re.match(r"(?:[A-Za-z_][A-Za-z0-9_]*\s+)*([A-Za-z_][A-Za-z0-9_]*)\s*:", item)
        if name is None:
            raise AssertionError(f"{path}: cannot parse {function} parameter: {item[:80]}")
        parameters.append(name.group(1))
    return parameters


def call_arguments(path: str, function: str) -> list[list[str]]:
    data = source(path)
    calls: list[list[str]] = []
    for match in re.finditer(rf"\b{re.escape(function)}\s*\(", data):
        prefix = data[max(0, match.start() - 12) : match.start()]
        if re.search(r"\bfun\s*$", prefix):
            continue
        opening = data.index("(", match.start())
        block = data[opening + 1 : matching_parenthesis(data, opening)]
        arguments: list[str] = []
        for item in split_top_level(block):
            named = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=", item)
            if named is None:
                raise AssertionError(f"{path}: {function} must use named arguments: {item[:80]}")
            arguments.append(named.group(1))
        calls.append(arguments)
    return calls


def verify_screen_calls() -> None:
    screens = {
        "LibraryScreen": "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt",
        "PersonalScreen": "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "StoryDetailScreen": "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "ReaderScreen": "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    }
    routes = (
        "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
        "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt",
    )
    for function, declaration in screens.items():
        expected = declaration_parameters(declaration, function)
        for route in routes:
            calls = call_arguments(route, function)
            if len(calls) != 1:
                raise AssertionError(f"{route}: expected one {function} call, found {len(calls)}")
            actual = calls[0]
            missing = [name for name in expected if name not in actual]
            extra = [name for name in actual if name not in expected]
            duplicates = sorted({name for name in actual if actual.count(name) > 1})
            if missing or extra or duplicates:
                raise AssertionError(
                    f"{route}: {function} mismatch; missing={missing}, extra={extra}, duplicates={duplicates}",
                )


def verify_history_migration() -> None:
    database = source("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt")
    match = re.search(
        r"val MIGRATION_21_22.*?(CREATE TABLE IF NOT EXISTS reading_history \(.*?\n\s*\))\n\s*\"\"\"\.trimIndent",
        database,
        re.S,
    )
    if match is None:
        raise AssertionError("Cannot extract migration 21->22 reading_history SQL")
    connection = sqlite3.connect(":memory:")
    try:
        connection.execute(match.group(1))
        columns = [row[1] for row in connection.execute("PRAGMA table_info(reading_history)")]
        connection.execute(
            "CREATE UNIQUE INDEX index_reading_history_storyId_chapterId "
            "ON reading_history(storyId, chapterId)",
        )
        connection.execute("CREATE INDEX index_reading_history_visitedAt ON reading_history(visitedAt)")
        connection.execute(
            "INSERT INTO reading_history VALUES "
            "('h1','s1','src','Story','c1','Chapter',2,9,1234)",
        )
        connection.execute(
            "INSERT OR REPLACE INTO reading_history VALUES "
            "('h2','s1','src','Story','c1','Chapter',4,9,2345)",
        )
        count, paragraph = connection.execute(
            "SELECT COUNT(*), MAX(paragraphIndex) FROM reading_history",
        ).fetchone()
    finally:
        connection.close()
    expected = [
        "id", "storyId", "sourceId", "storyTitle", "chapterId", "chapterTitle",
        "paragraphIndex", "totalParagraphs", "visitedAt",
    ]
    if columns != expected or (count, paragraph) != (1, 4):
        raise AssertionError(f"Migration 21->22 mismatch: columns={columns}, row={(count, paragraph)}")


def main() -> int:
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
        "import vn.nghetruyen.app.ui.screens.PersonalScreen",
        "onUpdateVietPhrase = viewModel::updateVietPhrase",
        "onPrioritizeDownload = viewModel::prioritizeDownload",
        "onHistoryClick = viewModel::openReadingHistory",
    )
    forbid(
        "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
        "ReferencePersonalScreen(",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "scheduleReadingPersistence(playback)",
        "recordReadingHistory(",
        "fun openReadingHistory(",
        "fun clearReadingHistory()",
        "fun prioritizeDownload(",
        "fun updateVietPhrase(",
        "pendingVietPhraseImport = result.value",
        "Đã tiếp tục bằng bản gốc.",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "display.layoutMode == ReaderLayoutMode.SCROLL",
        "showNoteDialog = true",
        "ÁP DỤNG VIETPHRASE",
        "CẢI THIỆN VIETPHRASE",
        "PHÂN VAI + NHẠC",
        "TIẾP TỤC BẰNG BẢN GỐC",
        "AudioExportRequest(",
    )
    forbid(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "QUAY LẠI",
        "CẢI THIỆN VIETPHRASE",
        "PHÂN VAI + NHẠC CẢNH",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "Chỉ dùng Wi-Fi",
        "Chỉ tải khi đang sạc",
        "TẢI CHƯƠNG CHƯA ĐỌC",
        "TẢI THEO KHOẢNG",
        "AudioExportScope.CACHED_STORY",
        "AudioExportPackaging.ONE_FILE_PER_CHAPTER",
        "Đánh dấu chương trong MP3",
    )
    forbid(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "QUAY LẠI",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt",
        "LibrarySection.NOTES -> state.notes.size",
        "NoteList(visibleNotes",
        "state.readingHistory.filter",
        "onClearReadingHistory()",
        "onPrioritizeDownload(job.id)",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "TAI NGHE & TỰ ĐỘNG",
        "KHÔI PHỤC (",
        "GỢI Ý AI (",
        "SỬA QUY TẮC",
        "onUpdateRule(",
        "XÁC NHẬN NHẬP",
        "onDictionaryEnabledChange",
        "onUpdate(pack.id)",
        "onExport(pack.id, pack.name)",
        "onRemove(packId)",
    )
    forbid(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "QUAY LẠI",
        "chưa được nối vào nút này",
        "chưa được kích hoạt",
        "TAI NGHE & TỰ ĐỘNG",
        "GỢI Ý AI",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        'tableName = "reading_history"',
        "interface ReadingHistoryDao",
        "version = 22",
        "MIGRATION_21_22",
        "abstract fun readingHistoryDao()",
        "suspend fun update(item: VietPhraseEntity): Int",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "private const val FORMAT_VERSION = 16",
        'writer.name("readingHistory")',
        "writeReadingHistory",
        "readReadingHistory",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt",
        "setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt",
        "override suspend fun getForegroundInfo(): ForegroundInfo",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt",
        "SemanticVersion.parse(BuildConfig.VERSION_NAME)",
    )
    require(
        "app/build.gradle.kts",
        "versionCode = 33",
        'versionName = "2.9.0-xpk-parity"',
    )

    verify_screen_calls()
    verify_history_migration()
    print("XPK parity 2.9.0 static gate: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
