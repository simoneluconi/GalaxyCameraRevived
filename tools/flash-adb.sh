#!/bin/bash
# Flash a LineageOS build to the Galaxy Camera over adb, without Odin.
# Run it in WSL, from the build tree's out directory or with OUT set.
#
#   tools/flash-adb.sh prepare   raw + gzip the images, write <img>.info
#   tools/flash-adb.sh push      copy them to /data/flash (LineageOS running)
#   tools/flash-adb.sh flash     reboot to the mini recovery, write and
#                                verify BOOT, SYSTEM and HIDDEN, reboot,
#                                then delete /data/flash (about 600MB)
#   tools/flash-adb.sh clean     delete /data/flash (LineageOS running)
#
# It writes only BOOT, SYSTEM and HIDDEN (/vendor), through the mini
# recovery's flash-image, which refuses the bootloader, PARAM and TZ and
# verifies every partition by reading it back. Data and settings are kept.
# The mini recovery runs the stock 3.0 kernel, so /data is mounted read-only
# there.
#
# Environment: DEVICE (gd1, the default, for the GC100; sf2wifi for the
# GC200), OUT (default ~/android/lineage/out/target/product/$DEVICE),
# STAGE (default ~/android/flash-stage, or flash-stage-sf2 for the GC200),
# ADB (default: the Android SDK's adb.exe under Windows' %LOCALAPPDATA%),
# ONLY_BOOT=1 to prepare, push and flash the kernel (boot.img) alone.
#
# The same camera shows two adb serials: LineageOS reports the board serial,
# the stock adbd of the mini recovery the old Samsung one. Set both, in the
# environment or in a git-ignored local file, tools/flash-adb.local for the
# GC100 or tools/flash-adb-sf2wifi.local for the GC200, e.g.
#   SERIAL_LOS=<serial under LineageOS>
#   SERIAL_REC=<serial in the mini recovery>
# `adb devices` shows each one while the camera is in that mode.
#
# On the GC200 the mini recovery's su cannot mount (no CAP_SYS_ADMIN under
# its stock adbd), so root commands go through rootcmd (rootd, run by init;
# docs/RECOVERY.md). After an Android "reboot recovery" the GC200 keeps
# booting recovery until a plain reboot from it, which the flash step does.

set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
DEVICE=${DEVICE:-gd1}
case "$DEVICE" in
    gd1) LOCAL=$HERE/tools/flash-adb.local; DEF_STAGE=$HOME/android/flash-stage; ROOT_RUN="su -c" ;;
    sf2wifi) LOCAL=$HERE/tools/flash-adb-sf2wifi.local; DEF_STAGE=$HOME/android/flash-stage-sf2; ROOT_RUN=rootcmd ;;
    *) echo "unknown DEVICE '$DEVICE' (gd1 or sf2wifi)" >&2; exit 1 ;;
esac
if [ -f "$LOCAL" ]; then
    . "$LOCAL"
fi

OUT=${OUT:-$HOME/android/lineage/out/target/product/$DEVICE}
STAGE=${STAGE:-$DEF_STAGE}
if [ -z "${ADB:-}" ]; then
    ADB="$(wslpath "$(cmd.exe /c 'echo %LOCALAPPDATA%' 2>/dev/null | tr -d '\r')")/Android/Sdk/platform-tools/adb.exe"
fi
SIMG2IMG=${SIMG2IMG:-$HOME/android/lineage/out/host/linux-x86/bin/simg2img}
DEVDIR=/data/flash

: "${SERIAL_LOS:?set SERIAL_LOS (see the header)}"
: "${SERIAL_REC:?set SERIAL_REC (see the header)}"

# name in the stage, partition. BOOT goes last, so that a failure never
# leaves a new kernel with a half-written system.
IMAGES="system.raw.gz:SYSTEM vendor.raw.gz:HIDDEN boot.img:BOOT"
RAWS="system vendor"
if [ "${ONLY_BOOT:-0}" = 1 ]; then
    IMAGES="boot.img:BOOT"
    RAWS=""
fi

winpath() { wslpath -w "$1"; }

# "<raw size> <raw sha1>" of a raw image file
info() {
    echo "$(stat -c %s "$1") $(sha1sum < "$1" | cut -d' ' -f1)"
}

prepare() {
    mkdir -p "$STAGE"
    echo "preparing images from $OUT in $STAGE"
    cp "$OUT/boot.img" "$STAGE/boot.img"
    info "$STAGE/boot.img" > "$STAGE/boot.img.info"
    for i in $RAWS; do
        "$SIMG2IMG" "$OUT/$i.img" "$STAGE/$i.raw"
        info "$STAGE/$i.raw" > "$STAGE/$i.raw.gz.info"
        gzip -1 -c "$STAGE/$i.raw" > "$STAGE/$i.raw.gz"
        rm -f "$STAGE/$i.raw"
    done
    for e in $IMAGES; do f=${e%%:*}; echo "  $f: $(cat "$STAGE/$f.info")"; done
    ls -l "$STAGE"
}

push() {
    local adb="$ADB -s $SERIAL_LOS"
    echo "pushing to $DEVDIR (LineageOS must be running)"
    $adb shell "mkdir -p $DEVDIR"
    for e in $IMAGES; do
        f=${e%%:*}
        $adb push "$(winpath "$STAGE/$f")" "$DEVDIR/$f"
        $adb push "$(winpath "$STAGE/$f.info")" "$DEVDIR/$f.info"
    done
    $adb push "$(winpath "$HERE/minirecovery/sbin/flash-image")" "$DEVDIR/flash-image"
    $adb shell "sync; ls -l $DEVDIR"
}

flash() {
    local adb="$ADB -s $SERIAL_REC"
    echo "rebooting to the mini recovery"
    # Already in the mini recovery when a previous run stopped there
    if ! "$ADB" devices | grep -q "^$SERIAL_REC"; then
        "$ADB" -s "$SERIAL_LOS" reboot recovery
    fi
    # The mini recovery's adbd reports the "recovery" state, which
    # wait-for-device does not match
    $adb wait-for-recovery
    sleep 5
    # The stock adbd of the mini recovery drops root (use its su, or rootcmd)
    # and does not return exit codes, so every step is checked on its output.
    # On any failure the camera stays in the mini recovery, never rebooted
    # into a half-written system.
    out=$($adb shell "$ROOT_RUN 'mkdir -p /mnt/data && mount -t ext4 -o ro /dev/block/platform/dw_mmc/by-name/USERDATA /mnt/data && cp /mnt/data/flash/flash-image /tmp/flash-image && chmod 0755 /tmp/flash-image && echo STAGED'" | tr -d '\r')
    echo "$out"
    if ! echo "$out" | grep -q STAGED; then
        echo "could not mount /data read-only or find the staged files; staying in recovery" >&2
        exit 1
    fi
    for e in $IMAGES; do
        f=${e%%:*}; p=${e##*:}
        echo "writing $p"
        out=$($adb shell "$ROOT_RUN '/tmp/flash-image $p /mnt/data/flash/$f 2>&1'" | tr -d '\r')
        echo "$out"
        if ! echo "$out" | grep -q "^$p written and verified"; then
            echo "$p FAILED; staying in recovery. Fix it there or flash with Odin." >&2
            exit 1
        fi
    done
    $adb shell "$ROOT_RUN 'umount /mnt/data; sync'"
    echo "all partitions written and verified; rebooting"
    $adb reboot
    # /data is read-only in the mini recovery: free the staged images once
    # LineageOS is up again
    "$ADB" -s "$SERIAL_LOS" wait-for-device
    clean
}

clean() {
    "$ADB" -s "$SERIAL_LOS" shell "rm -rf $DEVDIR; df -h /data | tail -1"
}

case "${1:-}" in
    prepare) prepare ;;
    push) push ;;
    flash) flash ;;
    clean) clean ;;
    *) echo "usage: $0 prepare|push|flash|clean" >&2; exit 1 ;;
esac
