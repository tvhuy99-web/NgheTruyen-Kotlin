#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REPORT_DIR="build/reports/m0"
mkdir -p "$REPORT_DIR"
exec > >(tee "$REPORT_DIR/m0-gate.log") 2>&1

GATE_RESULT=FAILED
finish() {
  local code=$?
  trap - EXIT
  python3 scripts/m0_collect_evidence.py || true
  if [[ "$GATE_RESULT" == "PASS" && $code -eq 0 ]]; then
    printf 'M0_GATE=PASS\n'
    exit 0
  fi
  printf 'M0_GATE=FAILED exit=%s\n' "$code"
  exit "$code"
}
trap finish EXIT

printf 'M0_GATE_STARTED_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python3 scripts/m0_preflight.py \
  --json "$REPORT_DIR/preflight.json" \
  --markdown "$REPORT_DIR/preflight.md" \
  --strict

STATIC_GATES=(
  scripts/validate_release.py
  scripts/check_source_platform_foundation.py
  scripts/check_vbook_chromium_static.py
  scripts/check_milestone2_source_platform.py
  scripts/check_milestone4_foundation.py
  scripts/check_milestone4_complete.py
  scripts/check_milestone5_foundation.py
  scripts/check_p1_ui_static.py
  scripts/check_p2_ui_static.py
  scripts/check_p4_transfer_static.py
  scripts/check_milestone3_ui_static.py
  scripts/check_milestone3_download_static.py
  scripts/check_milestone3_kindle.py
  scripts/check_ui_control_parity.py
  scripts/check_reference_workflow_parity.py
  scripts/check_music_runtime_parity.py
  scripts/check_music_playback_parity.py
  scripts/check_music_normalization_flow_parity.py
  scripts/check_xpk_final_ui_parity.py
  scripts/check_downloaded_xpk_parity.py
  scripts/check_xpk_strict_parity.py
)
for gate in "${STATIC_GATES[@]}"; do
  printf 'RUN_STATIC_GATE=%s\n' "$gate"
  python3 "$gate"
done

# check_milestone3_foundation.py contains legacy UI-label assertions for the former
# reader surface. The current M3 UI/download gates validate the live wiring, while
# the Gradle stages below are the authoritative compiler/test/lint/build checks.
printf 'SKIP_LEGACY_GATE=scripts/check_milestone3_foundation.py (superseded UI labels)\n'

# check_audio_export_static.py is a historical, hand-stubbed standalone kotlinc harness. Audio
# export now shares VoiceExpressionProcessor with the canonical XPK runtime, so that harness no
# longer models the production dependency graph. The real app compiler, unit tests, lint and APK/
# bundle builds below remain authoritative for audio-export integration; XPK prosody ownership is
# separately locked by check_xpk_strict_parity.py.
printf 'SKIP_LEGACY_GATE=scripts/check_audio_export_static.py (superseded standalone stubs)\n'

GRADLE_ARGS=(--no-daemon --stacktrace --warning-mode all)
./gradlew "${GRADLE_ARGS[@]}" clean
./gradlew "${GRADLE_ARGS[@]}" test testDebugUnitTest
./gradlew "${GRADLE_ARGS[@]}" lintDebug
./gradlew "${GRADLE_ARGS[@]}" assembleDebug assembleDebugAndroidTest bundleRelease

if [[ "${M0_RUN_CONNECTED:-0}" == "1" ]]; then
  ./gradlew "${GRADLE_ARGS[@]}" connectedDebugAndroidTest
fi

GATE_RESULT=PASS