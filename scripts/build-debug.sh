#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java was not found. Install JDK 17 and configure JAVA_HOME." >&2
  exit 1
fi
JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
if ! java -version 2>&1 | grep -Eq '"17([.]|")'; then
  echo "ERROR: Gema Engine requires JDK 17. Detected: $JAVA_VERSION" >&2
  exit 1
fi

if [[ -z "${ANDROID_SDK_ROOT:-}${ANDROID_HOME:-}" ]]; then
  echo "ERROR: Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK directory." >&2
  exit 1
fi

if ! command -v gradle >/dev/null 2>&1; then
  echo "ERROR: Gradle 8.9 was not found on PATH. Install Gradle 8.9." >&2
  exit 1
fi
GRADLE_VERSION="$(gradle --version | awk '/^Gradle / {print $2; exit}')"
if [[ "$GRADLE_VERSION" != "8.9" ]]; then
  echo "ERROR: Expected Gradle 8.9, found $GRADLE_VERSION." >&2
  exit 1
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ ! -d "$SDK_DIR/platforms/android-35" ]]; then
  echo "ERROR: Android SDK Platform 35 is missing. Install it using Android Studio SDK Manager." >&2
  exit 1
fi

gradle --no-daemon --stacktrace clean assembleDebug
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
  echo "ERROR: Build finished but APK was not found at $APK" >&2
  exit 1
fi
echo
echo "BUILD SUCCESSFUL"
echo "APK: $APK"
