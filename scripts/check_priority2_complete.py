#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, textwrap

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

# The combined ROLE/ASSIGN/CUE response must be accepted by the production parser.
with tempfile.TemporaryDirectory(prefix='priority2-kotlin-') as tmp:
    test = Path(tmp) / 'Priority2ParserTest.kt'
    test.write_text(textwrap.dedent('''
        import vn.nghetruyen.app.ai.*
        fun main() {
            val raw = """
                ROLE|Người kể chuyện|narrator|CALM
                ROLE|Lâm|A Lâm|TENSE
                ASSIGN|1|Lâm|0.91|5|-3|4
                CUE|0|track-calm|0.25|mở đầu
                CUE|4|track-tense|0.38|cao trào
            """.trimIndent()
            val voice = AiLineProtocol.parseVoiceCast(raw)
            val cues = AiLineProtocol.parseSceneCues(raw)
            check(voice.roles.any { it.character == "Lâm" })
            check(voice.assignments.single().speedAdjustPct == 5f)
            check(cues.map { it.trackId } == listOf("track-calm", "track-tense"))
            println("PRIORITY2_COMBINED_PROTOCOL_OK")
        }
    '''), encoding='utf-8')
    jar = Path(tmp) / 'test.jar'
    cmd = [
        'kotlinc',
        str(ROOT/'app/src/main/java/vn/nghetruyen/app/core/common/AppResult.kt'),
        str(ROOT/'app/src/main/java/vn/nghetruyen/app/ai/AiServices.kt'),
        str(ROOT/'app/src/main/java/vn/nghetruyen/app/ai/AiLineProtocol.kt'),
        str(test), '-include-runtime', '-d', str(jar),
    ]
    subprocess.run(cmd, check=True, cwd=ROOT, timeout=120)
    result = subprocess.run(['java', '-jar', str(jar)], check=True, capture_output=True, text=True, timeout=30)
    if 'PRIORITY2_COMBINED_PROTOCOL_OK' not in result.stdout:
        raise AssertionError(result.stdout)

print('PRIORITY2_COMPLETE_OK')