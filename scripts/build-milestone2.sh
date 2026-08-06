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
python3 scripts/check_milestone2_complete.py
python3 scripts/check_milestone2_source_platform.py
python3 scripts/check_vbook_static.py
python3 scripts/check_source_platform_android_static.py

set -- \
  clean \
  test \
  testDebugUnitTest \
  lintDebug \
  assembleDebug \
  assembleDebugAndroidTest

./gradlew --no-daemon --stacktrace "$@"

case " ${MILESTONE2_EXTRA_TASKS:-} " in
  *" connected "*) ./gradlew --no-daemon --stacktrace connectedDebugAndroidTest ;;
esac
case " ${MILESTONE2_EXTRA_TASKS:-} " in
  *" release "*) ./gradlew --no-daemon --stacktrace bundleRelease ;;
esac

printf '%s\n' 'Milestone 2 complete build gates completed.'
printf '%s\n' 'Debug APK: app/build/outputs/apk/debug/app-debug.apk'
