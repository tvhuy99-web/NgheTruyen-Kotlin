#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
DOWNLOADER="$APP_HOME/gradle/wrapper/WrapperDownloader.java"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE=java
fi

"$JAVA_EXE" "$DOWNLOADER" "$JAR"
exec "$JAVA_EXE" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
