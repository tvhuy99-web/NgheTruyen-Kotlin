#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"

command -v java >/dev/null 2>&1 || {
  printf '%s\n' 'ERROR: JDK 17 is required and java is not on PATH.' >&2
  exit 2
}
command -v python3 >/dev/null 2>&1 || {
  printf '%s\n' 'ERROR: Python 3 is required for the offline release gates.' >&2
  exit 2
}

python3 scripts/validate_release.py
python3 scripts/check_roadmap_milestone5_playback_complete.py
python3 scripts/check_milestone5_foundation.py
python3 scripts/check_milestone4_complete.py
python3 scripts/check_audio_export_static.py
python3 scripts/check_p1_ui_static.py
python3 scripts/check_p2_ui_static.py
python3 scripts/check_p4_transfer_static.py
python3 scripts/check_milestone3_foundation.py
python3 scripts/check_milestone3_ui_static.py
python3 scripts/check_milestone3_download_static.py
python3 scripts/check_milestone3_kindle.py
python3 scripts/check_milestone2_complete.py

./gradlew --no-daemon --stacktrace \
  clean \
  test \
  testDebugUnitTest \
  lintDebug \
  assembleDebug \
  assembleDebugAndroidTest

case " ${MILESTONE5_EXTRA_TASKS:-} " in
  *" connected "*) ./gradlew --no-daemon --stacktrace connectedDebugAndroidTest ;;
esac
case " ${MILESTONE5_EXTRA_TASKS:-} " in
  *" release "*) ./gradlew --no-daemon --stacktrace :app:verifyReleaseSigning bundleRelease ;;
esac

printf '%s\n' 'Milestone 5 complete build gates completed.'
printf '%s\n' 'Debug APK: app/build/outputs/apk/debug/app-debug.apk'
