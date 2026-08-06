#!/usr/bin/env python3
"""Aggregate source-side acceptance gate for roadmap Milestone 3.

Android Gradle/device/live-site checks are intentionally outside this source
acceptance gate per the owner's sequencing decision. Each child gate is still
run independently in a new process so a Kotlin launcher cannot hide another
result by keeping a pipe open.
"""
from __future__ import annotations
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHILD_GATES = (
    "check_roadmap_milestone3_vietphrase_complete.py",
    "check_roadmap_m3_persistence_static.py",
    "check_p4_transfer_static.py",
    "check_p2_ui_static.py",
    "check_p4_features.py",
)


def require_text(relative: str, *tokens: str) -> None:
    content = (ROOT / relative).read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in content]
    if missing:
        raise AssertionError(f"{relative} thiếu wiring M3: {missing}")


def run_gate(name: str) -> None:
    print(f"RUN_M3_GATE {name}", flush=True)
    with tempfile.TemporaryFile(mode="w+t", encoding="utf-8") as log:
        try:
            completed = subprocess.run(
                [sys.executable, str(ROOT / "scripts" / name)],
                cwd=ROOT,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=300,
                start_new_session=True,
            )
        except subprocess.TimeoutExpired as error:
            log.seek(0)
            output = log.read()
            if output:
                print(output, end="" if output.endswith("\n") else "\n")
            raise SystemExit(f"M3 child gate timed out: {name}") from error
        log.seek(0)
        output = log.read()
    if output:
        print(output, end="" if output.endswith("\n") else "\n")
    if completed.returncode:
        raise SystemExit(completed.returncode)


def main() -> None:
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
        "acceptVietPhraseSuggestion",
        "rejectVietPhraseSuggestion",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt",
        "pendingVietPhraseImport",
        "confirmVietPhraseImport",
        "setVietPhraseDictionaryEnabled",
        "acceptVietPhraseSuggestion",
        "rejectVietPhraseSuggestion",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt",
        "onDictionaryEnabledChange",
        "NHẬP / XEM TRƯỚC",
        "Snapshot rollback",
        "Suggestion AIReplace chờ duyệt",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/transfer/VietPhraseTransferManager.kt",
        "previewFrom",
        "commit",
        "VietPhraseBundleCodec",
        "VietPhraseBinaryDictionaryCodec",
        "MAX_FILE_BYTES",
    )
    require_text(
        "app/src/main/java/vn/nghetruyen/app/transfer/BackupTransferManager.kt",
        "FORMAT_VERSION = 15",
        "vietPhraseSnapshots",
        "vietPhraseDictionaryStates",
        "vietPhraseSuggestions",
        "VIETPHRASE_KINDS",
        "VIETPHRASE_SUGGESTION_STATUSES",
    )
    require_text(
        "docs/xpk_reference/vietphrase_v34_contract.json",
        "vp-r9.4-ai-final-replace-20260725",
        "vp-import-safe-dat-v1",
        "74f8e442dfbbe5dc7120664f86a0c1a2ed7c09b24e042e238e74af351e21faab",
    )
    if "--run-gates" in sys.argv:
        for gate in CHILD_GATES:
            run_gate(gate)
    else:
        print("M3_CHILD_GATES_RECORDED_SEPARATELY")
    print(f"ROADMAP_MILESTONE3_SOURCE_COMPLETE_GATE=PASS childGates={len(CHILD_GATES)}")


if __name__ == "__main__":
    main()
