#!/bin/bash
# Unpack a block-based LineageOS OTA zip (system/vendor .new.dat.br) into raw ext4 images.
# Usage: unpack-ota.sh <ota.zip> <out-dir>
set -euo pipefail

zip=$(realpath "$1")
out=$2
tools=$(dirname "$(realpath "$0")")

mkdir -p "$out"
cd "$out"
unzip -o -q "$zip" '*.new.dat.br' '*.transfer.list' boot.img
for list in *.transfer.list; do
    part=${list%.transfer.list}
    brotli -d -f "$part.new.dat.br"
    rm "$part.new.dat.br"
    python3 -I "$tools/sdat2img.py" "$list" "$part.new.dat" "$part.img"
    rm "$part.new.dat"
done
ls -la
