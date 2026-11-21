#!/bin/sh
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then JAVA_BIN=java; fi
exec "$JAVA_BIN" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
