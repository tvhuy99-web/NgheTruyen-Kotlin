#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch(path: str, old: str, new: str, count: int = 1) -> None:
    file = ROOT / path
    source = file.read_text(encoding="utf-8")
    found = source.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count}, found {found}: {old[:120]!r}")
    file.write_text(source.replace(old, new, count), encoding="utf-8")

patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        if (previousStoryAutoVoiceCastEnabled && !currentStoryAutoVoiceCastEnabled) {\n            narrationPlanJob?.cancel()\n            narrationPrefetchJob?.cancel()\n            narrationPlanningChapterId = ""\n            narrationPreparedChapterId = ""''',
    '''        if (previousStoryAutoVoiceCastEnabled && !currentStoryAutoVoiceCastEnabled) {\n            narrationPlanJob?.cancel()\n            narrationPrefetchJob?.cancel()\n            narrationPlanningChapterId = ""\n            narrationPreparedChapterId = ""\n            if (!PlaybackQueueStore.state.value.isPlaying) pendingPlay = false''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
    '''        viewModelScope.launch {\n            container.libraryRepository.deleteStoryAiProfile(storyId)\n            showMessage("Đã chuyển truyện về cấu hình AI chung.")\n        }''',
    '''        viewModelScope.launch {\n            container.libraryRepository.deleteStoryAiProfile(storyId)\n            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)\n            showMessage("Đã chuyển truyện về cấu hình AI chung.")\n        }''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled)",\n)''',
    '''    "if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled)",\n    "if (!PlaybackQueueStore.state.value.isPlaying) pendingPlay = false",\n)''',
)

Path(__file__).unlink()
print("AUTO_SWITCH_EDGE_FIX=APPLIED")
