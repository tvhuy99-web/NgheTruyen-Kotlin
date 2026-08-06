#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
import sqlite3
import subprocess
import tempfile
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise AssertionError(f"{path} thiếu: {missing}")
    return text


def _migration_sql(text: str, function_name: str) -> list[str]:
    match = re.search(
        rf"private fun {function_name}\(db: SupportSQLiteDatabase\) \{{(.*?)(?=\n        private fun |\n        val MIGRATION_|\n        fun create\()",
        text,
        re.DOTALL,
    )
    if not match:
        raise AssertionError(f"Không tìm thấy migration helper {function_name}")
    statements: list[str] = []
    pattern = re.compile(
        r'db\.execSQL\(\s*(?:"""(.*?)"""\.trimIndent\(\)|"([^"]+)")\s*,?\s*\)',
        re.DOTALL,
    )
    for sql in pattern.finditer(match.group(1)):
        statements.append(textwrap.dedent(sql.group(1)).strip() if sql.group(1) else sql.group(2))
    if not statements:
        raise AssertionError(f"Không trích được SQL từ {function_name}")
    return statements


def check_database_migration() -> None:
    text = require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "version = 18",
        "MIGRATION_6_7",
        "createDownloadJobsTable(db)",
        "normalizeFollowingDefaults(db)",
        "normalizeStoryTtsProfileDefaults(db)",
        "normalizeAudioExportDefaults(db)",
        'CREATE TABLE IF NOT EXISTS download_jobs',
        '@ColumnInfo(defaultValue = "-1")',
        '@ColumnInfo(defaultValue = "0")',
        '@ColumnInfo(defaultValue = "1.0")',
        '@ColumnInfo(defaultValue = "\'WAV\'")',
        '@ColumnInfo(defaultValue = "\'audio/wav\'")',
    )
    with sqlite3.connect(":memory:") as db:
        db.executescript(
            """
            CREATE TABLE following (
                storyId TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                remoteUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                latestKnownChapter TEXT NOT NULL,
                latestKnownChapterIndex INTEGER NOT NULL,
                newChapterCount INTEGER NOT NULL,
                checkedAt INTEGER NOT NULL
            );
            CREATE TABLE story_tts_profiles (
                storyId TEXT NOT NULL PRIMARY KEY,
                rate REAL NOT NULL,
                pitch REAL NOT NULL,
                volume REAL NOT NULL,
                enginePackage TEXT,
                voiceName TEXT,
                languageTag TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            );
            CREATE TABLE audio_export_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                storyId TEXT NOT NULL,
                storyTitle TEXT NOT NULL,
                chapterId TEXT,
                destinationUri TEXT NOT NULL,
                outputFormat TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                state TEXT NOT NULL,
                completedSegments INTEGER NOT NULL,
                totalSegments INTEGER NOT NULL,
                errorMessage TEXT,
                updatedAt INTEGER NOT NULL
            );
            INSERT INTO following VALUES (
                'story-1','source-1','https://example.test/story','Truyện','Chương 9',9,2,1234
            );
            INSERT INTO story_tts_profiles VALUES (
                'story-1',1.1,0.9,0.8,'engine','voice','vi-VN',1234
            );
            INSERT INTO audio_export_jobs VALUES (
                'job-1','story-1','Truyện',NULL,'content://export','M4A','audio/mp4',
                'RUNNING',3,8,NULL,1234
            );
            """
        )
        for helper in (
            "createDownloadJobsTable",
            "normalizeFollowingDefaults",
            "normalizeStoryTtsProfileDefaults",
            "normalizeAudioExportDefaults",
        ):
            for statement in _migration_sql(text, helper):
                db.execute(statement)

        download_columns = db.execute("PRAGMA table_info(download_jobs)").fetchall()
        names = [column[1] for column in download_columns]
        expected = [
            "id",
            "storyId",
            "sourceId",
            "state",
            "completedChapters",
            "totalChapters",
            "errorMessage",
            "updatedAt",
        ]
        if names != expected or download_columns[0][5] != 1:
            raise AssertionError(f"download_jobs schema sai: {download_columns}")

        defaults = {
            ("following", "latestKnownChapterIndex"): "-1",
            ("following", "newChapterCount"): "0",
            ("story_tts_profiles", "volume"): "1.0",
            ("audio_export_jobs", "outputFormat"): "'WAV'",
            ("audio_export_jobs", "mimeType"): "'audio/wav'",
        }
        for (table, column), expected_default in defaults.items():
            columns = db.execute(f"PRAGMA table_info({table})").fetchall()
            actual = next(item[4] for item in columns if item[1] == column)
            if actual != expected_default:
                raise AssertionError(f"{table}.{column} default sai: {actual}")

        preserved = (
            db.execute("SELECT latestKnownChapter FROM following").fetchone()[0],
            db.execute("SELECT voiceName FROM story_tts_profiles").fetchone()[0],
            db.execute("SELECT outputFormat, mimeType FROM audio_export_jobs").fetchone(),
        )
        if preserved != ("Chương 9", "voice", ("M4A", "audio/mp4")):
            raise AssertionError(f"Migration làm mất hoặc đổi dữ liệu: {preserved}")


def check_wrapper_bootstrap() -> None:
    require(
        "gradle/wrapper/WrapperDownloader.java",
        "v8.13.0",
        "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
        "MAX_WRAPPER_BYTES",
        "ATOMIC_MOVE",
        "checksum mismatch",
    )
    require("gradlew", "WrapperDownloader.java", "GradleWrapperMain")
    require("gradlew.bat", "WrapperDownloader.java", "GradleWrapperMain")
    javac = shutil.which("javac")
    if javac:
        with tempfile.TemporaryDirectory(prefix="nghe_wrapper_") as temp:
            subprocess.run(
                [
                    javac,
                    "-d",
                    temp,
                    str(ROOT / "gradle/wrapper/WrapperDownloader.java"),
                ],
                check=True,
                cwd=ROOT,
                timeout=30,
            )


def check_build_contract() -> None:
    build = require(
        "app/build.gradle.kts",
        "compileSdk = 36",
        "versionCode = 28",
        'versionName = "2.8.0-ai-narration-priority2-complete"',
        "abortOnError = true",
        'arg("room.schemaLocation"',
        'androidTestImplementation("androidx.room:room-testing:2.8.4")',
    )
    if "compileSdkMinor" in build:
        raise AssertionError("compileSdkMinor làm tăng phụ thuộc SDK không cần thiết")
    require(
        ".github/workflows/android-ci.yml",
        "Run Milestone 0 gate",
        "./scripts/m0_gate.sh",
        "connectedDebugAndroidTest",
        "reactivecircus/android-emulator-runner@v2.37.0",
        "actions/checkout@v6",
        "actions/setup-java@v5",
        "gradle/actions/setup-gradle@v6",
        "actions/upload-artifact@v7",
    )
    require(
        "scripts/m0_gate.sh",
        "testDebugUnitTest",
        "lintDebug",
        "assembleDebug",
        "assembleDebugAndroidTest",
        "bundleRelease",
        "M0_RUN_CONNECTED",
    )
    require(
        "scripts/build-milestone2.sh",
        "testDebugUnitTest",
        "lintDebug",
        "assembleDebug",
        "assembleDebugAndroidTest",
        "MILESTONE2_EXTRA_TASKS",
    )
    require(
        "scripts/build-milestone2.ps1",
        "testDebugUnitTest",
        "connectedDebugAndroidTest",
        "bundleRelease",
    )
    require(
        "app/src/androidTest/java/vn/nghetruyen/app/data/local/AppDatabaseMigrationTest.kt",
        "migration5To6CreatesEveryP4TableIncludingDownloadJobs",
        "migration6To7RepairsMissingTableAndNormalizesDefaultsWithoutDataLoss",
    )


def main() -> None:
    check_database_migration()
    check_wrapper_bootstrap()
    check_build_contract()
    print("MILESTONE1_FOUNDATION_CHECK_OK")


if __name__ == "__main__":
    main()
