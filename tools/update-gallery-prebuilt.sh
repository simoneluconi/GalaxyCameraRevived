#!/bin/bash
# Copies GC Gallery's release APK into the device tree as the GcGallery prebuilt
# (device/samsung/gd1/app/GcGallery). Build it first on Windows:
#   cd camera-app; .\gradlew.bat :gallery:assembleRelease
# Run from WSL: tools/update-gallery-prebuilt.sh [lineage root]
set -euo pipefail

ROOT="${1:-$HOME/android/lineage}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APK="$HERE/camera-app/gallery/build/outputs/apk/release/gallery-release.apk"
DEST="$ROOT/device/samsung/gd1/app/GcGallery/GcGallery.apk"

[ -f "$APK" ] || { echo "no release APK at $APK: run :gallery:assembleRelease first" >&2; exit 1; }
[ -d "$(dirname "$DEST")" ] || { echo "no $(dirname "$DEST")" >&2; exit 1; }
cp "$APK" "$DEST"
echo "GcGallery.apk updated ($(stat -c %s "$DEST") bytes, $(sha256sum "$DEST" | cut -c1-12))"
