#!/bin/bash
# Copies GC Camera's release APK into the device tree as the GcCamera prebuilt
# (device/samsung/gd1/app/GcCamera). Build it first on Windows:
#   cd camera-app; .\gradlew.bat :app:assembleRelease
# Run from WSL: tools/update-camera-prebuilt.sh [lineage root]
set -euo pipefail

ROOT="${1:-$HOME/android/lineage}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APK="$HERE/camera-app/app/build/outputs/apk/release/app-release.apk"
DEST="$ROOT/device/samsung/gd1/app/GcCamera/GcCamera.apk"

[ -f "$APK" ] || { echo "no release APK at $APK: run :app:assembleRelease first" >&2; exit 1; }
[ -d "$(dirname "$DEST")" ] || { echo "no $(dirname "$DEST")" >&2; exit 1; }
cp "$APK" "$DEST"
echo "GcCamera.apk updated ($(stat -c %s "$DEST") bytes, $(sha256sum "$DEST" | cut -c1-12))"
