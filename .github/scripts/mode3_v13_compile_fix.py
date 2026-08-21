from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


coordinator = ROOT / "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
replace_once(
    coordinator,
    '''            var sfxCandidate = if (AudioAssetKind.SFX in kinds) {\n            var sfxCandidate = if (AudioAssetKind.SFX in kinds) {\n''',
    '''            var sfxCandidate = if (AudioAssetKind.SFX in kinds) {\n''',
    "duplicate SFX candidate marker",
)
replace_once(
    coordinator,
    '''            val validated = AmbienceSfxPlan(\n            val validated = AmbienceSfxPlan(\n''',
    '''            val validated = AmbienceSfxPlan(\n''',
    "duplicate validated plan marker",
)

resolver = ROOT / "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    resolver,
    '''    }\n}\n\n    }\n}\n\n/** Converts resolved search needs into the same local runtime cue types used by the existing player. */\n''',
    '''    }\n}\n\n/** Converts resolved search needs into the same local runtime cue types used by the existing player. */\n''',
    "duplicate resolver closing braces",
)
replace_once(
    resolver,
    '''import vn.nghetruyen.app.audio.AudioDirectionPreferences\n''',
    '''import vn.nghetruyen.app.audio.AudioDirectionLimits\nimport vn.nghetruyen.app.audio.AudioDirectionPreferences\n''',
    "AudioDirectionLimits import",
)

prompt = ROOT / "app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt"
replace_once(
    prompt,
    "Mode 3 dùng cùng logic đạo diễn MUSIC/AMBIENCE/SFX của Mode 2; khác biệt duy nhất là không gửi catalog asset local và không yêu cầu AI chọn track_id.",
    "Mode 3 dùng cùng logic đạo diễn MUSIC/AMBIENCE/SFX của Mode 2; khác biệt duy nhất là không gửi catalog local (asset trên máy) và không yêu cầu AI chọn track_id.",
    "explicit no-local-catalog wording",
)

stage5 = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundStage5Test.kt"
replace_once(
    stage5,
    '        assertEquals("duration:[10 TO 300]", url.queryParameter("filter"))\n',
    '        assertEquals("category:\\\"Soundscapes\\\" duration:[10 TO 300]", url.queryParameter("filter"))\n',
    "similar taxonomy filter expectation",
)

print("V13 generated-source syntax/import/prompt/test fix applied.")
