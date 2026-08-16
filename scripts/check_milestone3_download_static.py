#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(path: str, *tokens: str) -> None:
    file = ROOT / path
    if not file.exists():
        raise SystemExit(f"Missing required Milestone 3 download file: {path}")
    text = file.read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        raise SystemExit(f"{path}: missing required Milestone 3 download wiring: {missing}")


def main() -> None:
    
    
    
    
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/ChapterDownloadWorker.kt",
        "override suspend fun getForegroundInfo()",
        "createForegroundInfo(",
        "override suspend fun doWork()",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/DownloadScheduler.kt",
        "fun prioritize(",
        "setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)",
        "enqueueUniqueWork",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/DownloadBatchPlanner.kt",
        "object DownloadBatchPlanner",
    )
    require(
        "app/src/main/java/vn/nghetruyen/app/downloads/DownloadStorageGuard.kt",
        "object DownloadStorageGuard",
    )

    print("MILESTONE3_DOWNLOAD_STATIC_WIRING_OK")
    print("MILESTONE3_DOWNLOAD_FULL_ANDROID_COMPILE=DEFERRED_TO_M0_GRADLE")


if __name__ == "__main__":
    main()
