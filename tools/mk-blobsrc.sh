#!/bin/bash
# Build the blob source dir for device/samsung/gd1/extract-files.sh.
#
# proprietary-files.txt mixes two sources: system/ entries from the GC100
# stock dump and vendor/ entries from html6405's LineageOS 21 i9300 vendor.
# extract-utils looks for every blob at its destination path first, so a
# plain symlink to the full i9300 vendor/ shadows each system/...:vendor/...
# entry with the i9300 file of the same name (that put the i9300 Wi-Fi NVRAM
# and Bluetooth patchram on the camera). Here vendor/ holds only the files
# that proprietary-files.txt takes from it.
#
# Usage: mk-blobsrc.sh <proprietary-files.txt> <gc100-system-dump> <i9300-vendor> <out-dir>
# Then:  device/samsung/gd1/extract-files.sh <out-dir>
set -euo pipefail

list=$1
gc100_system=$(realpath "$2")
i9300_vendor=$(realpath "$3")
out=$4

rm -rf "$out"
mkdir -p "$out/vendor"
ln -s "$gc100_system" "$out/system"

grep -v '^#' "$list" | sed 's/^-//' | grep '^vendor/' | while read -r line; do
    src=${line%%[:|;]*}
    rel=${src#vendor/}
    if [ ! -e "$i9300_vendor/$rel" ]; then
        echo "missing in the i9300 vendor: $src" >&2
        exit 1
    fi
    mkdir -p "$out/vendor/$(dirname "$rel")"
    ln -s "$i9300_vendor/$rel" "$out/vendor/$rel"
done

echo "blob source ready in $out"
