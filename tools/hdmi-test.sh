#!/bin/bash
# HDMI mode tests with a capture card (docs/HDMI.md).
#
#   tools/hdmi-test.sh check [mode...]       one frame per mode: size, border,
#                                            corners, bars, grey ramp, aspect
#   tools/hdmi-test.sh stability [mode...]   40 s per mode, every frame compared
#   tools/hdmi-test.sh edid                  the sink's EDID, read over DDC
#
# Default: every mode of gd1_hdmitest. Run as root adb on the device under test;
# hdmid is stopped for the test and started again at the end.
#
# Environment:
#   SERIAL     adb serial (default: SERIAL_LOS from tools/flash-adb.local)
#   ADB        adb (default: the Android SDK's, then PATH)
#   FFMPEG     ffmpeg (default: PATH)
#   CAPTURE    ffmpeg input of the capture card (default: DirectShow "Cam Link 4K")
#   HDMITEST   the gd1_hdmitest binary to push (default: already on the device)
#   PYTHON     python with numpy and Pillow (default: python)
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$HERE/tools/flash-adb.local" ] && . "$HERE/tools/flash-adb.local"
SERIAL=${SERIAL:-$SERIAL_LOS}
[ -n "$SERIAL" ] || { echo "set SERIAL" >&2; exit 1; }
if [ -z "$ADB" ]; then
    ADB=adb
    [ -n "$LOCALAPPDATA" ] && [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ] && ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
fi
FFMPEG=${FFMPEG:-ffmpeg}
CAPTURE=${CAPTURE:-'-f dshow -rtbufsize 800M -pixel_format yuyv422 -i "video=Cam Link 4K"'}
PYTHON=${PYTHON:-python}
TMP=${TMPDIR:-/tmp}/hdmi-test
mkdir -p "$TMP"
# Git Bash: ffmpeg and python are Windows programs, which need a Windows path
command -v cygpath > /dev/null && TMP=$(cygpath -m "$TMP") && HERE=$(cygpath -m "$HERE")
export MSYS_NO_PATHCONV=1
DEV=/data/local/tmp/gd1_hdmitest

adb_() { "$ADB" -s "$SERIAL" "$@"; }
capture() { eval "\"$FFMPEG\" -hide_banner -loglevel error $CAPTURE \"\$@\""; }

cmd=${1:-check}
shift || true

if [ "$cmd" = edid ]; then
    adb_ shell 'i2ctransfer -y 5 w1@0x50 0x00 r128; i2ctransfer -y 5 w1@0x50 0x80 r128' | grep '^0x'
    exit 0
fi

[ -n "$HDMITEST" ] && adb_ push "$HDMITEST" $DEV > /dev/null
adb_ shell "chmod 755 $DEV; stop vendor.hdmid; pkill gd1_hdmitest" > /dev/null 2>&1 || true
trap 'adb_ shell "pkill gd1_hdmitest; start vendor.hdmid" > /dev/null 2>&1' EXIT
sleep 2

modes="$*"
[ -z "$modes" ] && modes=$(adb_ shell $DEV list | tr -d '\r' | awk '{print $1}')
fails=0
for m in $modes; do
    line=$(adb_ shell $DEV list | tr -d '\r' | awk -v m="$m" '$1==m')
    size=$(echo "$line" | awk '{print $2}'); W=${size%x*}; H=${size#*x}
    dar=$(echo "$line" | awk '{print $5}'); DW=${dar%:*}; DH=${dar#*:}
    case $m in
        1080i60|1080i59) fps=30 ;; 1080i50) fps=25 ;;
        *30*) fps=30 ;; *50*) fps=50 ;; *) fps=60 ;;
    esac
    adb_ shell "$DEV $m 70 > /dev/null 2>&1 &"
    sleep 6
    # "480p60-4:3": no colon in a Windows file name (NTFS reads it as a data stream)
    f=${m//:/-}
    if [ "$cmd" = check ]; then
        echo "== $m ($W x $H, $DW:$DH)"
        rm -f "$TMP/$f.png"
        # A failed capture is a failed mode, not the end of the run
        capture -frames:v 1 -update 1 -y "$TMP/$f.png" || true
        if [ -f "$TMP/$f.png" ]; then
            "$PYTHON" "$HERE/tools/hdmi-check.py" "$TMP/$f.png" "$W" "$H" "$DW" "$DH" || fails=$((fails + 1))
        else
            echo "  BAD no frame captured"; fails=$((fails + 1))
        fi
    else
        capture -f rawvideo - 2>/dev/null | "$PYTHON" "$HERE/tools/hdmi-monitor.py" "$W" "$H" 40 $fps > "$TMP/$f.log" || true
        bad=$(awk '{b+=$3} END {print b+0}' "$TMP/$f.log")
        total=$(awk '{split($3,a,"/"); n+=a[2]} END {print n+0}' "$TMP/$f.log")
        printf "%-11s %4dx%-4d %2d fps: %s bad frames of %s\n" "$m" "$W" "$H" $fps "$bad" "$total"
        # No frames at all is a failure too
        [ "$bad" = 0 ] && [ "$total" -gt 0 ] || fails=$((fails + 1))
    fi
    adb_ shell "pkill gd1_hdmitest" > /dev/null 2>&1 || true
    sleep 2
done
echo "$fails mode(s) failed"
[ $fails = 0 ]
