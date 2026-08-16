#!/usr/bin/env python3
"""Offline release gate for the Kotlin clean rewrite.

This gate does not claim to replace Gradle, Android Lint, instrumented tests or
physical-device verification. It catches packaging regressions and critical
wiring mistakes without requiring Android SDK or network access.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_script(name: str) -> None:
    
    
    
    print(f"RUN_GATE {name}", flush=True)
    with tempfile.TemporaryFile(mode="w+t", encoding="utf-8") as log:
        try:
            completed = subprocess.run(
                [sys.executable, str(ROOT / "scripts" / name)],
                cwd=ROOT,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=180,
                start_new_session=True,
            )
        except subprocess.TimeoutExpired as exc:
            log.seek(0)
            output = log.read()
            if output:
                print(output, end="" if output.endswith("\n") else "\n")
            raise SystemExit(f"Gate timed out after 180s: {name}") from exc
        log.seek(0)
        output = log.read()
    if output:
        print(output, end="" if output.endswith("\n") else "\n")
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)


def require_text(path: str, *tokens: str) -> None:
    content = (ROOT / path).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in content]
    if missing:
        raise AssertionError(f"{path} thiếu wiring: {missing}")


def main() -> None:
    run_script("check_xpk_parity_v290.py")
    run_script("check_lua_diagnostics_ui_parity.py")
    if "--wiring-only" not in sys.argv:
        run_script("check_clean_rewrite.py")
        run_script("check_truyenfull_fixtures.py")
        run_script("check_truyencv_fixtures.py")
        run_script("check_truyencom_fixtures.py")
        run_script("check_truyenyy_fixtures.py")
        run_script("check_milestone1_reader_core.py")
        run_script("check_ai_gemini_story_vietphrase.py")
        run_script("check_priority2_complete.py")
        run_script("check_priority1_source_coverage.py")
        run_script("check_priority1_complete.py")
        run_script("check_priority1_home_genre_fixtures.py")
        run_script("check_v250_tool_parity.py")
        if "--with-kotlinc" in sys.argv:
            run_script("check_kotlin_static.py")
            run_script("check_ai_settings_static.py")
            run_script("check_priority2_coordinator_static.py")
            run_script("check_audio_export_static.py")
            run_script("check_android_wiring_static.py")
            run_script("check_wave_assembler.py")
            run_script("check_p1_ui_static.py")
            run_script("check_p1_features.py")
            run_script("check_p2_sources.py")
            run_script("check_p2_network_static.py")
            run_script("check_p2_health_static.py")
            run_script("check_p2_android_wiring.py")
            run_script("check_p2_ui_static.py")
            run_script("check_p3_features.py")
            run_script("check_p4_features.py")
            run_script("check_p4_network_static.py")
            run_script("check_p4_android_security.py")
            run_script("check_p4_transfer_static.py")
            run_script("check_source_platform_foundation.py")
            run_script("check_source_platform_android_static.py")
            run_script("check_priority1_source_parity.py")
            run_script("check_priority1_registry.py")
            run_script("check_milestone2_source_platform.py")
            run_script("check_vbook_static.py")
            run_script("check_v240_native_lua_vbook_diagnostics_comments.py")
            run_script("check_source_diagnostic_browser_static.py")
            run_script("check_milestone2_complete.py")
            run_script("check_milestone1_foundation.py")
            run_script("check_milestone3_foundation.py")
            run_script("check_milestone3_ui_static.py")
            run_script("check_milestone4_foundation.py")
            run_script("check_milestone4_complete.py")
            run_script("check_milestone5_foundation.py")
            run_script("check_roadmap_milestone5_playback_complete.py")
        else:
            print("KOTLIN_COMPILER_GATES_RUN_SEPARATELY")
    else:
        print("RELEASE_CHILD_GATES_RUN_SEPARATELY")

    with (ROOT / "REWRITE_STATUS.json").open(encoding="utf-8") as handle:
        status = json.load(handle)
    assert status["version"] == "2.9.0-xpk-parity"
    assert status["luaIncluded"] is True


    require_text(
        "settings.gradle.kts",
        '":source-api"',
        '":source-package"',
        '":source-store"',
        '":source-runtime"',
        '":source-diagnostics"',
        '":source-network"',
        '":source-repository"',
        '":source-vbook"',
        '":source-lua"',
    )
    require_text(
        "source-package/src/main/kotlin/vn/nghetruyen/source/packagekit/SourcePackArchive.kt",
        "FILES.sha256",
        "PACKAGE_HASH_COVERAGE_MISMATCH",
        "SourceDetachedSignatureVerifier.verify",
        "PACKAGE_VERIFIED",
    )
    require_text(
        "source-store/src/main/kotlin/vn/nghetruyen/source/store/SourcePackStore.kt",
        "staging",
        "payloadTreeSha256",
        "atomicMove",
        "rollback",
    )
    require_text(
        "source-runtime/src/main/kotlin/vn/nghetruyen/source/runtime/SourceFixtureRunner.kt",
        "FIXTURE_PASSED",
        "FIXTURE_FAILED",
        "allPassed",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt",
        "SourcePackArchiveVerifier",
        "SourceFixtureRunner",
        "selfTest(pack)",
        "permissionDiff",
        "rollback",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "CÀI .NTSOURCE / VBOOK / LUA API 2",
        "THÊM / LÀM MỚI REPOSITORY",
        "Self-test:",
        "ROLLBACK PHIÊN BẢN NGUỒN",
    )

    for xml_file in ROOT.rglob("*.xml"):
        if "build" not in xml_file.parts and ".gradle" not in xml_file.parts:
            ET.parse(xml_file)

    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/HttpHtmlClient.kt",
        ".followRedirects(false)",
        ".followSslRedirects(false)",
        "nextUrl.requireAllowed(allowedHosts)",
        "readBounded(maxResponseBytes)",
        "HostRequestGovernor",
        "DEFAULT_CACHE_TTL_MILLIS",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceRegistry.kt",
        "TruyenFullSource()",
        "TruyenCvSource()",
        "TruyenComSource()",
        "TruyenYySource()",
        "WikidichSource()",
        "SangTacVietSource(sessionStore)",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/TruyenCvSource.kt",
        "SourceHealth.DEGRADED",
        "findHighestChapterPage",
        "previousChapterIndexPage",
        "SOURCE_BROWSER_VERIFICATION_REQUIRED",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/TruyenComSource.kt",
        "SourceHealth.DEGRADED",
        "searchSlug",
        "parseChapterPage",
        "SOURCE_BROWSER_VERIFICATION_REQUIRED",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/TruyenYySource.kt",
        "SourceHealth.DEGRADED",
        "JINA_HOST",
        "parseChapterList",
        "SOURCE_BROWSER_VERIFICATION_REQUIRED",
        "isStoryTarget",
        "TextDocumentClient",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/WikidichSource.kt",
        "SourceHealth.DEGRADED",
        "wikidichvn.com",
        "parseChapterPage",
        "lastChapterPage",
        "allowedHosts = ALLOWED_HOSTS",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SangTacVietSource.kt",
        "SourceHealth.DEGRADED",
        "chapterlist",
        "readchapter",
        "SOURCE_LOGIN_REQUIRED",
        "loginUrl = BASE_URL",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/EncryptedSourceSessionStore.kt",
        "AndroidKeyStore",
        "AES/GCM/NoPadding",
        "updateAAD",
        "setRandomizedEncryptionRequired(true)",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt",
        "MAX_COOKIE_COUNT = 128",
        "MAX_COOKIE_HEADER_BYTES = 32 * 1024",
        "CookieHeaderCodec.merge",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SessionHttpClient.kt",
        ".followRedirects(false)",
        "mergeSetCookieHeaders",
        "requireAllowedSourceHost",
        "readBoundedSource",
        "MAX_REDIRECTS = 5",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt",
        "MIXED_CONTENT_NEVER_ALLOW",
        "safeBrowsingEnabled = true",
        "setAcceptThirdPartyCookies(this, false)",
        "clearStoredSession",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceHealthChecker.kt",
        "probeStories",
        "withTimeout",
        "Nội dung chương",
        "SourceHealth.NEEDS_LOGIN",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt",
        "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
        "createCancelPendingIntent",
        "saveDownloadedChapter",
        "DownloadBatchPlanner.create",
        "MAX_CHAPTERS_PER_RUN = 40",
        "ExistingWorkPolicy.APPEND",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
        "PREFETCH_THRESHOLD = 0.75f",
        "advanceAfterChapter",
        "loadNextChapter",
        "autoPlayNextChapter",
        "ACTION_SET_SLEEP_TIMER",
        "scheduleSleepTimer",
        "PlaybackQueueStore.setSleepTimer",
        "handleAudioFocusChange",
        "AUDIOFOCUS_GAIN",
        "resumeAfterTransientFocusLoss",
        "PronunciationProcessor.apply",
        "observePronunciations",
        "ACTION_PREVIEW",
        "pendingPreviewText",
        "previewUtteranceId",
        "TextToSpeech.Engine.KEY_PARAM_VOLUME",
        "VoiceRoleResolver.resolve",
        "configureBackgroundMusic",
        "AudioInterruptionMode",
        "currentEnginePackage",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15",
        "writePronunciations",
        "writeStoryVoiceProfiles",
        "writeVoiceRoles",
        "readerCacheLimitMiB",
        "dataSha256",
        "MAX_ENTRY_COUNT = 2",
        "copyBounded",
        "database.withTransaction",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/importers/BookImporter.kt",
        "MAX_PLAIN_TEXT_BYTES",
        "MAX_TOTAL_TEXT_BYTES",
        "normalizeArchiveEntryName",
        "UTF_16LE",
        "UTF_16BE",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "openBookmark",
        "openFollowedStory",
        "openFollowedStoryById",
        "loadMoreStories",
        "removeOfflineStory",
        "refreshTtsVoices",
        "openTtsSettings",
        "addPronunciation",
        "trimReaderCacheNow",
        "checkFollowingNow",
        "exportBackup",
        "restoreBackup",
        "saveVoiceProfileForCurrentStory",
        "clearVoiceProfileForCurrentStory",
        "exportAudio",
        "cancelAudioExport",
        "openAudioExport",
        "setSearchAllSources",
        "cancelSearch",
        "loadAllChapters",
        "downloadChapterRange",
        "setReaderTheme",
        "openExternalUrl",
        "refreshSourceSessions",
        "checkSource",
        "checkAllSources",
        "openSourceLogin",
        "clearSourceSession",
        "runSourceCheck",
        "selectTtsEngine",
        "setTtsVolume",
        "setAudioInterruptionMode",
        "setBackgroundMusic",
        "saveVoiceRoleForCurrentStory",
        "applyVietPhraseToCurrentChapter",
        "importVietPhrase",
        "exportVietPhrase",
        "aiTranslate",
        "voiceCast",
        "planSceneMusic",
        "planNarration",
        "saveAiApiKey",
        "clearAiApiKey",
        "updateSceneMusicTrack",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
        "TÌM TRONG CHƯƠNG",
        "SAO CHÉP CHƯƠNG",
        "ReaderThemeMode.SEPIA",
        "keepScreenOn",
        "lineHeightPercent",
        "VIETPHRASE",
        "DỊCH AI",
        "PHÂN VAI AI",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt",
        'if (state.diagnosticsMode == "off" && state.diagnosticPersistentCriticalCount == 0) return',
        "LỖI CÀI ĐẶT",
        "ĐANG GHI NHẬT KÝ...",
        "XEM NHẬT KÝ",
        "CHƯA CÓ NHẬT KÝ",
        'title = { Text("NHẬT KÝ") }',
        'Text("SAO CHÉP")',
        'Text("XÓA")',
        'Text("XUẤT TỆP")',
        "DiagnosticHumanFormatter.formatUi",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
        "ReferenceDiagnosticsChrome(",
        "ReferencePrimaryBottomBar(",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt",
        "TÌM CHƯƠNG",
        "Nhập tên, số chương hoặc vài ký tự liên quan",
        "Hãy nhập tên hoặc số chương.",
        "CHỌN PHẠM VI TẢI",
        "CHỌN NHIỀU CHƯƠNG",
        "TẢI TOÀN BỘ TRUYỆN",
        "snapshotFlow",
        "state.chapterPageLoading",
        "CHAPTER_PAGE_PREFETCH_DISTANCE",
        "MỞ TRANG GỐC",
        "BÌNH LUẬN",
        "TẢI LẠI",
        "state.storyComments",
        "AI RIÊNG CHO TRUYỆN",
        "PHÂN VAI TTS CHO TRUYỆN NÀY",
        "BỘ GIỌNG RIÊNG CỦA TRUYỆN",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/StorySearch.kt",
        "Normalizer.normalize",
        "healthRank",
        "groupBy",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/WikidichSource.kt",
        "SourceHealth.DEGRADED",
        "wikidichvn.com",
        "parseChapterPage",
        "lastChapterPage",
        "allowedHosts = ALLOWED_HOSTS",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SangTacVietSource.kt",
        "SourceHealth.DEGRADED",
        "chapterlist",
        "readchapter",
        "SOURCE_LOGIN_REQUIRED",
        "loginUrl = BASE_URL",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/EncryptedSourceSessionStore.kt",
        "AndroidKeyStore",
        "AES/GCM/NoPadding",
        "updateAAD",
        "setRandomizedEncryptionRequired(true)",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceSessionStore.kt",
        "MAX_COOKIE_COUNT = 128",
        "MAX_COOKIE_HEADER_BYTES = 32 * 1024",
        "CookieHeaderCodec.merge",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SessionHttpClient.kt",
        ".followRedirects(false)",
        "mergeSetCookieHeaders",
        "requireAllowedSourceHost",
        "readBoundedSource",
        "MAX_REDIRECTS = 5",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceLoginActivity.kt",
        "MIXED_CONTENT_NEVER_ALLOW",
        "safeBrowsingEnabled = true",
        "setAcceptThirdPartyCookies(this, false)",
        "clearStoredSession",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/sources/SourceHealthChecker.kt",
        "probeStories",
        "withTimeout",
        "Nội dung chương",
        "SourceHealth.NEEDS_LOGIN",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt",
        "KEY_START_INDEX",
        "KEY_END_INDEX",
        "ChapterRangeSelector.select",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "Từ điển phát âm",
        "selectedEngineLabel",
        "selectedVoiceLabel",
        "DỌN THEO HẠN MỨC",
        "NGHE THỬ GIỌNG ĐANG CHỌN",
        "Xuất sách nói WAV / M4A",
        "HỦY XUẤT ${job.outputFormat}",
        "MỞ TỆP ${job.outputFormat}",
        "Bộ đọc TTS",
        "Bật nhạc nền",
        "KIỂM TRA TẤT CẢ NGUỒN",
        "ĐĂNG NHẬP",
        "XÓA PHIÊN ĐÃ LƯU",
        "Bật VietPhrase",
        "NHẬP ZIP",
        "XUẤT ZIP",
        "XÓA TẤT CẢ",
        "TẢI TỪ MẠNG",
        "Hán Việt khi thiếu cụm",
        "Bật nút AI trong màn hình đọc",
        "PasswordVisualTransformation",
        "Thư viện nhạc cảnh",
        "LƯU TÊN/TAG",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateWorker.kt",
        "source.latestChapter",
        "POST_NOTIFICATIONS",
        "listFollowingForUpdate",
        "updateFollowCheck",
        "EXTRA_STORY_ID",
        "newChapterCount",
        "latestKnownChapterIndex",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/playback/TtsVoiceCatalog.kt",
        "engine.voices",
        "engine.engines",
        "defaultEngine",
        "networkRequired",
        "engine.shutdown()",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "observeStorageUsage",
        "clearTransientCache",
        "observeOfflineStorage",
        "LENGTH(CAST(c.content AS BLOB))",
        "listForUpdate(limit: Int)",
        "tts_pronunciations",
        "MIGRATION_1_2",
        "MIGRATION_2_3",
        "MIGRATION_3_4",
        "version = 18",
        "MIGRATION_4_5",
        "MIGRATION_5_6",
        "viet_phrase_rules",
        "chapter_transforms",
        "chapter_voice_assignments",
        "scene_music_tracks",
        "scene_music_cues",
        "voice_roles",
        "story_tts_profiles",
        "audio_export_jobs",
        "listTransientCacheEntries",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/following/FollowingUpdateScheduler.kt",
        "PeriodicWorkRequestBuilder<FollowingUpdateWorker>",
        "ExistingWorkPolicy.KEEP",
        "enqueueUniqueWork",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/data/settings/SettingsRepository.kt",
        "ttsVoiceName",
        "ttsLanguageTag",
        "followingUpdatesEnabled",
        "setTtsVoice",
        "setFollowingUpdatesEnabled",
        "readerCacheLimitMiB",
        "setReaderCacheLimitMiB",
        "readerTheme",
        "setReaderTheme",
        "setReaderFontSizeSp",
        "setReaderLineHeightPercent",
        "setReaderKeepScreenOn",
        "ttsEnginePackage",
        "ttsVolume",
        "backgroundMusicUri",
        "audioInterruptionMode",
        "AiOnlineSettings",
        "aiConsent",
        "aiEndpoint",
        "aiModel",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/audio/AudioExportWorker.kt",
        "FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING",
        "TtsFileSynthesizer",
        "WaveFileAssembler.assemble",
        "PronunciationProcessor.apply",
        "createCancelPendingIntent",
        "MAX_SYNTHESIS_SEGMENTS",
        "AudioExportFormat.M4A",
        "AudioExportFormat.MP3",
        "Pcm16WaveConverter.convert",
        "M4aAacEncoder.encode",
        "VoiceRoleResolver.resolve",
        "ChapterAiWorkflow.KIND_VOICE_CAST",
        "listVoiceAssignments",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/audio/WaveFileAssembler.kt",
        "RIFF",
        "WAVE",
        "formatPayload.contentEquals",
        "MAX_RIFF_DATA_BYTES",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/audio/TtsFileSynthesizer.kt",
        "TextToSpeech.getMaxSpeechInputLength",
        "synthesizeToFile",
        "withTimeout",
        "TtsSynthesisVoice",
        "enginePackage",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/MainActivity.kt",
        "runWithNotificationPermission",
        "handleFollowingIntent",
        "openFollowedStoryById",
        "AudioExportFormat.WAV.mimeType",
        "AudioExportFormat.M4A.mimeType",
        "AudioExportFormat.MP3.mimeType",
        "backgroundMusicLauncher",
        "AudioExportRequest",
        "OpenDocumentTree",
        "takePersistableUriPermission",
    )
    require_text(
        "app/src/main/AndroidManifest.xml",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
        'android:foregroundServiceType="dataSync|mediaProcessing"',
        'android:usesCleartextTraffic="false"',
        '@xml/network_security_config',
        '.sources.SourceLoginActivity',
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt",
        ".dns(AiPublicDns)",
        ".followRedirects(false)",
        "MAX_RESPONSE_CHARS",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ai/AiCredentialStore.kt",
        "AndroidKeyStore",
        "AES/GCM/NoPadding",
        "updateAAD",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ai/VietPhraseFileCodec.kt",
        "MAX_RECORDS = 100_000",
        "NgheTruyen VietPhrase v1",
        "source=target",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/transfer/VietPhraseTransferManager.kt",
        "MAX_FILE_BYTES = 256 * 1024 * 1024",
        "previewFrom",
        "VietPhraseBinaryDictionaryCodec",
        "VietPhraseBundleCodec",
        "VIETPHRASE_IMPORT_FAILED",
        "VIETPHRASE_EXPORT_FAILED",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt",
        "version = 18",
        "MIGRATION_13_14",
        "viet_phrase_snapshots",
        "viet_phrase_dictionary_state",
        "viet_phrase_suggestions",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt",
        "VietPhrasePersistenceArchiveCodec",
        "previewVietPhraseImport",
        "commitVietPhraseImport",
        "rollbackVietPhraseSnapshot",
        "setVietPhraseDictionaryEnabled",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15",
        "vietPhraseSnapshots",
        "vietPhraseDictionaryStates",
        "vietPhraseSuggestions",
        "VIETPHRASE_KINDS",
    )
    require_text(
        "app/src/main/res/xml/backup_rules.xml",
        "encrypted_ai_credentials_v1.xml",
        "encrypted_source_sessions_v1.xml",
        'path="datastore/"',
        'domain="database" path="."',
    )
    require_text(
        "app/src/main/res/xml/data_extraction_rules.xml",
        "encrypted_ai_credentials_v1.xml",
        "encrypted_source_sessions_v1.xml",
        'path="datastore/"',
        'domain="database" path="."',
    )
    require_text(
        "app/build.gradle.kts",
        'versionName = "2.8.0-ai-narration-priority2-complete"',
        "versionCode = 28",
        "compileSdk = 36",
        "targetSdk = 36",
        'org.jsoup:jsoup:1.23.1',
        'implementation(project(":source-lua"))',
    )
    require_text(
        "gradle/wrapper/gradle-wrapper.properties",
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip",
        "distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78",
    )
    require_text(
        "gradle/wrapper/WrapperDownloader.java",
        "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
        "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar",
    )
    require_text("scripts/bootstrap-wrapper.sh", "WrapperDownloader.java")

    view_model = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt").read_text(encoding="utf-8")
    view_model_methods = set(re.findall(r"\bfun\s+(\w+)\s*\(", view_model))
    callback_refs: set[str] = set()
    for callback_file in (
        "app/src/main/java/vn/nghetruyen/app/MainActivity.kt",
        "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt",
    ):
        callback_text = (ROOT / callback_file).read_text(encoding="utf-8")
        callback_refs.update(re.findall(r"viewModel::(\w+)", callback_text))
        callback_refs.update(re.findall(r"viewModel\.(\w+)\s*\(", callback_text))
    missing_callbacks = sorted(callback_refs - view_model_methods)
    if missing_callbacks:
        raise AssertionError(f"UI gọi AppViewModel method chưa tồn tại: {missing_callbacks}")

    allowed_lua = {
        ROOT / "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_api.lua",
        ROOT / "source-lua/src/main/resources/vn/nghetruyen/source/lua/native_v2_adapter.lua",
    }
    forbidden = []
    for extension in ("*.lua", "*.dex", "*.so", "*.xpk", "*.alp"):
        forbidden.extend(
            path for path in ROOT.rglob(extension)
            if "build" not in path.parts and ".gradle" not in path.parts
            and not (extension == "*.lua" and path in allowed_lua)
        )
    if forbidden:
        raise AssertionError(f"Còn artifact runtime cũ hoặc Lua ngoài allowlist: {forbidden}")

    print("JSON_XML_VALIDATION_OK")
    print("CRITICAL_WIRING_CHECK_OK")
    print("FORBIDDEN_ARTIFACT_CHECK_OK")
    print("RELEASE_VALIDATION_OK")


if __name__ == "__main__":
    main()
