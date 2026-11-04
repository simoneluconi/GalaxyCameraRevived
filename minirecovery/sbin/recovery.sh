#!/sbin/sh
# Main script of the gd1 mini recovery. It shows the status screen, carries
# out a factory reset requested by Android, and otherwise leaves the device
# waiting for adb (adbd runs as root).

. /sbin/minirecovery-lib.sh

# Wait up to 10 s for ueventd to create the by-name links
i=0
while [ ! -e "$BYNAME/OTA" ] && [ $i -lt 50 ]; do
    usleep 200000
    i=$((i + 1))
done

echo 0 > /sys/class/graphics/fb0/blank
for b in /sys/class/backlight/*/brightness; do
    echo 140 > "$b"
done
screen main
log "started, $(cat /proc/version)"

# Android 14 passes its recovery arguments in the bootloader control block
# on misc (the OTA partition): struct bootloader_message, recovery[768] at
# offset 64, one argument per line.
misc=$(part OTA)
if [ -n "$misc" ]; then
    args=$(dd if="$misc" bs=64 skip=1 count=12 2>/dev/null | tr '\0' '\n')
    log "bootloader message: $(echo $args)"
    # Clear it as AOSP recovery does, so it is acted on only once. Only
    # write when there is something to clear.
    if [ -n "$(echo $args)" ]; then
        dd if=/dev/zero of="$misc" bs=2048 count=1 conv=fsync 2>/dev/null
    fi
fi

# Only an explicit --wipe_data triggers an automatic wipe. Anything else,
# including --prompt_and_wipe_data, waits for a decision over adb.
if echo "$args" | grep -qx -- '--wipe_data'; then
    if wipe_data; then
        screen done
        sleep 2
        reboot -f
    fi
fi
