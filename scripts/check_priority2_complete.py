#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding='utf-8')


def require(rel: str, *needles: str) -> None:
    text = read(rel)
    missing = [n for n in needles if n not in text]
    if missing:
        raise AssertionError(f"{rel}: missing {missing}")


require('app/build.gradle.kts', 'versionCode = 28', 'versionName = "2.8.0-ai-narration-priority2-complete"')
require('app/src/main/java/vn/nghetruyen/app/playback/PlaybackQueueStore.kt',
        'enum class PlaybackPreparationState', 'PREPARING', 'FAILED', 'fun setPreparation')
require('app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt',
        'PlaybackPreparationState.PREPARING', 'pendingPlay = true',
        'PlaybackPreparationState.FAILED', 'prepareRolePreview',
        'VoiceExpressionProcessor.resolve', 'ACTION_PREVIEW_ROLE')
require('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
        'autoTranslate', 'Đang chuẩn bị bản dịch AI trước khi phát',
        'chapterLoadJob?.cancel()', 'fun planNarration()',
        'includeVoice = true, includeMusic = true',
        'draft.originalRoleId', 'previewVoiceRole')
require('app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt',
        'data class NarrationPlanContext', 'previousChapterEnding',
        'activeTrackId', 'data class NarrationPlanRequest', 'interface NarrationPlanner')
require('app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt',
        'override suspend fun planNarration', 'NGỮ CẢNH LIÊN CHƯƠNG',
        'YÊU CẦU PHÂN VAI RIÊNG', 'Nhạc đang tiếp nối',
        'AiLineProtocol.parseVoiceCast', 'AiLineProtocol.parseSceneCues')
require('app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt',
        'usedUnifiedRequest', 'ai.planNarration', 'buildContinuityContext',
        'loadPreviousCachedChapter', 'activeTrackId', 'persistVoicePlan', 'persistMusicPlan')
require('app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt',
        'PHÂN VAI AI', 'ĐANG CHUẨN BỊ', 'AI LỖI')
require('app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt',
        'THÊM VAI', 'onPreviewVoiceRole', 'enginePackage',
        'voiceName', 'expressionStrength', 'sonicSpeed', 'sonicPitch')
require('app/src/main/java/vn/nghetruyen/app/core/model/Models.kt',
        'data class VoiceRoleDraft', 'originalRoleId', 'enginePackage',
        'expressionStrength', 'sonicSpeed', 'sonicPitch')





require('app/src/main/java/vn/nghetruyen/app/ai/AiLineProtocol.kt',
        'fun parseVoiceCast(raw: String): VoiceCastPlan',
        'fun parseSceneCues(raw: String): List<SceneMusicCue>',
        '@Deprecated("Use parseXpkNarration; paragraph ROLE/ASSIGN protocol is not used by XPK narration runtime")',
        '@Deprecated("Use parseXpkNarration; paragraph CUE protocol is not used by XPK narration runtime")')
require('app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt',
        'AiLineProtocol.parseXpkNarration(',
        'includeVoiceCast = request.includeVoiceCast',
        'includeSceneMusic = request.includeSceneMusic')

print('PRIORITY2_COMPLETE_OK')