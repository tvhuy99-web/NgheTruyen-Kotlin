#!/usr/bin/env python3
from pathlib import Path
import re
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        raise AssertionError(f"Missing file: {path}")
    return p.read_text(encoding="utf-8")


def require(path: str, *markers: str) -> None:
    data = text(path)
    missing = [marker for marker in markers if marker not in data]
    if missing:
        raise AssertionError(f"{path}: missing markers: {missing}")


def forbid(path: str, *markers: str) -> None:
    data = text(path)
    found = [marker for marker in markers if marker in data]
    if found:
        raise AssertionError(f"{path}: forbidden markers: {found}")


def main() -> int:
    require(
        "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
        "GEMINI_API_BASE",
        "/models/$geminiModel:generateContent",
        '"x-goog-api-key"',
        '"responseMimeType", "application/json"',
        "listGeminiModels",
        'supportedGenerationMethods',
        "isSuitableGeminiTextModel",
        "GEMINI_NON_TEXT_MODEL_TOKENS",
        "VietPhraseImprovementEngine",
        "defaultImprovePrompt",
        "parseVietPhraseSuggestions",
        "MAX_SUGGESTIONS = 30",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ai/AiCredentialStore.kt",
        "api_key_openai",
        "api_key_gemini",
        "AiProvider.OPENAI_COMPATIBLE",
        "AiProvider.GEMINI",
        "migrateLegacyKeyIfNeeded",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
        "enum class AiProvider",
        "ai_model_openai_compatible",
        "ai_model_gemini",
        "DEFAULT_GEMINI_MODEL",
        "setAiProvider",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "StoryAiProfileEntity",
        'tableName = "story_ai_profiles"',
        "StoryAiProfileDao",
        "version = 18",
        "MIGRATION_16_17",
        "CREATE TABLE IF NOT EXISTS story_ai_profiles",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt",
        "saveStoryAiProfile",
        "{{CHAPTER_TEXT}}",
        "{{SOURCE_TEXT}}",
        "{{VIETPHRASE_TEXT}}",
        "saveVietPhraseSuggestion",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "saveStoryAiProfileForCurrentStory",
        "clearStoryAiProfileForCurrentStory",
        "improveVietPhraseForCurrentChapter",
        "VietPhraseImprovementRequest",
        "listEnabledVietPhrase",
        "saveVietPhraseSuggestion",
        "refreshGeminiModels",
        '"IMPROVE" -> improveVietPhraseForCurrentChapter()',
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "AI RIÊNG CHO TRUYỆN",
        "Lời nhắc riêng khi dịch",
        "Lời nhắc riêng khi cải thiện VietPhrase",
        "Tự động dịch khi mở chương",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "TẠO NHẬT KÝ VIETPHRASE",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReferencePersonalScreen.kt",
        "Google Gemini",
        "TẢI DS",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15",
        'name("aiProvider")',
        'name("storyAiProfiles")',
        "writeStoryAiProfiles",
        "readStoryAiProfiles",
    )
    require(
        "app/src/androidTest/java/vn/nghetruyen/app/data/local/AppDatabaseMigrationTest.kt",
        "migration16To17AddsPerStoryAiProfilesWithSafeDefaults",
    )
    require(
        "app/build.gradle.kts",
        "versionCode = 28",
        'versionName = "2.8.0-ai-narration-priority2-complete"',
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "safeCustomPrompts",
        "SettingsRepository.DEFAULT_GEMINI_MODEL",
    )

    database_source = text("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt")
    migration_match = re.search(
        r"val MIGRATION_16_17.*?(CREATE TABLE IF NOT EXISTS story_ai_profiles \(.*?\n\s*\))\n\s*\"\"\"\.trimIndent",
        database_source,
        re.S,
    )
    if migration_match is None:
        raise AssertionError("Cannot extract migration 16->17 SQL")
    connection = sqlite3.connect(":memory:")
    try:
        connection.execute(migration_match.group(1))
        columns = {row[1]: row for row in connection.execute("PRAGMA table_info(story_ai_profiles)")}
    finally:
        connection.close()
    expected_columns = {
        "storyId", "mode", "overrideProvider", "provider", "endpoint", "model", "temperature",
        "useCustomPrompts", "translationPrompt", "improvePrompt", "autoRunOnOpen", "updatedAt",
    }
    if set(columns) != expected_columns or columns["storyId"][5] != 1:
        raise AssertionError(f"Migration 16->17 schema mismatch: {sorted(columns)}")

    # The improvement flow must queue proposals for review rather than writing AI_REPLACE directly.
    vm = text("app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt")
    start = vm.index("fun improveVietPhraseForCurrentChapter")
    end = vm.index("fun aiTranslate", start)
    improve_block = vm[start:end]
    if "saveVietPhraseSuggestion" not in improve_block:
        raise AssertionError("Improvement flow does not enqueue suggestions")
    if "saved.isSuccess" not in improve_block:
        raise AssertionError("Improvement flow does not handle kotlin.Result from the suggestion repository")
    if re.search(r"VietPhraseEntity\s*\(", improve_block):
        raise AssertionError("Improvement flow writes VietPhrase rules without review")

    # No accidental multiline ordinary string literal from generated patches.
    if 'joinToString("\\n\\n")' not in vm:
        raise AssertionError("Chapter payload separator is not the expected escaped double newline")

    print("AI Gemini/story profile/VietPhrase static gate: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
