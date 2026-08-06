#!/usr/bin/env sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=java
fi
"$JAVA_EXE" "$PROJECT_ROOT/gradle/wrapper/WrapperDownloader.java" \
  "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar"
printf '%s\n' 'Gradle Wrapper is ready. Run ./gradlew test or ./gradlew assembleDebug'
