#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt")
source = path.read_text(encoding="utf-8")
old = '''                val created = planResult?.let { it.voicePlanCreated || it.musicPlanCreated } == true
                val musicApplied = hasSceneMusicPlan()
                val statusMessage = when {
                    planResult == null -> "Phân vai tự động lỗi; đang đọc bằng cấu hình/phân vai hiện có."
                    created -> "Đã áp dụng phân vai mới${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                    else -> "Đã áp dụng phân vai đã lưu${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                } + warnings.firstOrNull()?.takeIf(String::isNotBlank)?.let { " • ${it.take(120)}" }.orEmpty()
                PlaybackQueueStore.setNarrationAutomation(
                    stage = if (planResult == null) NarrationAutomationStage.FAILED else NarrationAutomationStage.CURRENT_READY,
                    progress = 1f,
                    message = statusMessage,
                )'''
new = '''                val created = planResult?.let { it.voicePlanCreated || it.musicPlanCreated } == true
                val planningFailed = planResult == null || (
                    planResult.warnings.isNotEmpty() && !planResult.voicePlanCreated && !planResult.musicPlanCreated
                )
                val musicApplied = hasSceneMusicPlan()
                val statusMessage = when {
                    planningFailed -> "Phân vai tự động chưa thành công; đang đọc bằng cấu hình/phân vai hiện có."
                    created -> "Đã áp dụng phân vai mới${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                    else -> "Đã áp dụng phân vai đã lưu${if (musicApplied) " + nhạc cảnh" else ""} cho chương hiện tại."
                } + warnings.firstOrNull()?.takeIf(String::isNotBlank)?.let { " • ${it.take(120)}" }.orEmpty()
                PlaybackQueueStore.setNarrationAutomation(
                    stage = if (planningFailed) NarrationAutomationStage.FAILED else NarrationAutomationStage.CURRENT_READY,
                    progress = 1f,
                    message = statusMessage,
                )'''
if source.count(old) != 1:
    raise SystemExit(f"Expected current narration status block once, found {source.count(old)}")
path.write_text(source.replace(old, new, 1), encoding="utf-8")
print("PATCH_AUTO_STATUS_FAILURE_OK")
