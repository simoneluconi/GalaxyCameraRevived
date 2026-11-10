#!/bin/bash
# Build the gd1 mini recovery: the stock GC100XXBMC4 recovery kernel, init and
# adbd, plus our init.rc, scripts and a static busybox (see docs/RECOVERY.md).
#
# Usage: build-minirecovery.sh <stock recovery.img> <busybox static ARM binary> <out dir>
# SU=<static ARM build of minirecovery/su.c> is required too: the stock adbd
# always drops to the shell uid.
# Needs python3 with Pillow, cpio, and mkbootimg.py (from an Android tree,
# set MKBOOTIMG to override the default path).
# KERNEL=<zImage> replaces the stock kernel, e.g. to test our own kernel
# with this known-good ramdisk (the stock exFAT modules then fail to load).
# DEVICE=sf2wifi builds it for the GC200 (EK-GC200) from its stock
# GC200XXUCNK4 recovery; the default is gd1 (GC100).
set -euo pipefail

stock=$1
busybox=$2
out=$3
here=$(cd "$(dirname "$0")/.." && pwd)
src=$here/minirecovery
mkbootimg=${MKBOOTIMG:-$HOME/android/lineage/system/tools/mkbootimg/mkbootimg.py}
max_size=8388608    # RECOVERY partition, mmcblk0p6 (same on the GC200)
device=${DEVICE:-gd1}
case "$device" in
    gd1) model=EK-GC100; product=gd1xx ;;
    sf2wifi) model=EK-GC200; product=sf2wifixx ;;
    *) echo "unknown DEVICE $device (gd1 or sf2wifi)" >&2; exit 1 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 "$here/tools/unpackboot.py" "$stock" "$work/stock" > /dev/null
s=$work/stock/ramdisk
r=$work/root

# Directory skeleton
for d in data dev proc sys tmp etc res sbin system system/bin lib lib/modules; do
    install -d -m 0755 "$r/$d"
done
chmod 0771 "$r/data"

# From the stock recovery: init/ueventd, adbd, ueventd rules, exFAT modules
install -m 0750 "$s/init" "$r/init"
ln -s ../init "$r/sbin/ueventd"
install -m 0750 "$s/sbin/adbd" "$r/sbin/adbd"
install -m 0644 "$s/ueventd.rc" "$s/ueventd.smdk4x12.rc" "$r/"
# The GC200's stock recovery has no exFAT modules; init.rc's insmod then
# just fails and the microSD needs FAT32
for m in exfat_core.ko exfat_fs.ko; do
    if [ -f "$s/lib/modules/$m" ]; then
        install -m 0644 "$s/lib/modules/$m" "$r/lib/modules/"
    fi
done

# Ours. default.prop names the gd1; other models get their own names.
install -m 0750 "$src/init.rc" "$r/init.rc"
sed -e "s/^ro.build.id=gd1-/ro.build.id=$device-/" \
    -e "s/^ro.product.model=.*/ro.product.model=$model/" \
    -e "s/^ro.product.name=.*/ro.product.name=$product/" \
    -e "s/^ro.product.device=.*/ro.product.device=$device/" \
    -e "s/^ro.build.product=.*/ro.build.product=$device/" \
    "$src/default.prop" > "$r/default.prop"
chmod 0644 "$r/default.prop"
install -m 0644 "$src/sbin/minirecovery-lib.sh" "$r/sbin/"
install -m 0750 "$src/sbin/recovery.sh" "$src/sbin/backup-critical" \
    "$src/sbin/flash-image" "$src/sbin/wipe-data" "$src/sbin/rootd" "$r/sbin/"
install -m 0755 "$src/sbin/rootcmd" "$r/sbin/"
python3 "$src/mkscreens.py" "$work/screens"
install -m 0644 "$work/screens/"*.ppm.gz "$r/res/"

# busybox: applet links in /sbin, and /system/bin/sh for adb shell. Tools
# that discard or repartition the eMMC get no link.
install -m 0755 "$busybox" "$r/sbin/busybox"
# busybox-applets.txt is the output of "busybox --list" for that binary.
for a in $(cat "$src/busybox-applets.txt"); do
    case "$a" in
        busybox|blkdiscard|fstrim|fdisk|mkswap|mkdosfs|mkfs.vfat|nanddump|nandwrite|init|linuxrc) continue ;;
    esac
    [ -e "$r/sbin/$a" ] || ln -s busybox "$r/sbin/$a"
done
ln -s /sbin/busybox "$r/system/bin/sh"

# setuid-root su for the adb shell
install -m 4755 "${SU:?set SU to the static ARM su binary}" "$r/sbin/su"

# Pack with root ownership
(cd "$r" && find . | sort | cpio -o -H newc -R 0:0 --quiet) | gzip -9n > "$work/ramdisk.gz"

mkdir -p "$out"
python3 "$mkbootimg" --header_version 0 \
    --kernel "${KERNEL:-$work/stock/zImage}" --ramdisk "$work/ramdisk.gz" \
    --base 0x10000000 --pagesize 2048 \
    --kernel_offset 0x00008000 --ramdisk_offset 0x01000000 \
    --second_offset 0x00f00000 --tags_offset 0x00000100 \
    --output "$out/recovery.img"

size=$(stat -c %s "$out/recovery.img")
if [ "$size" -gt "$max_size" ]; then
    echo "recovery.img is $size bytes, larger than the RECOVERY partition ($max_size)" >&2
    exit 1
fi

# Odin package: a plain tar plus the md5 trailer Odin checks. Odin also
# checks the file name in that trailer, so never rename the .tar.md5;
# set TAR_NAME instead.
name=${TAR_NAME:-minirecovery-$device}
(cd "$out" && tar -H ustar -cf "$name.tar" recovery.img \
    && md5sum -t "$name.tar" >> "$name.tar" \
    && mv "$name.tar" "$name.tar.md5")
cp "$work/screens/"*.png "$out/"
echo "recovery.img: $size bytes (limit $max_size)"
sha1sum "$out/recovery.img" "$out/$name.tar.md5"
