# Shared helpers of the gd1 mini recovery (busybox sh).
# Nothing here ever sends a discard or erase to the eMMC: only plain writes.

BYNAME=/dev/block/platform/dw_mmc/by-name

log() {
    echo "minirecovery: $*" > /dev/kmsg
    echo "$*" >> /tmp/recovery.log
    chmod 0644 /tmp/recovery.log
}

# Block device of a partition, looked up by its name. Partition numbers are
# never hard-coded.
part() {
    if [ ! -b "$BYNAME/$1" ]; then
        echo "no partition named $1" >&2
        return 1
    fi
    readlink -f "$BYNAME/$1"
}

# Mount point of a block device, or nothing. Mount sources are resolved
# first: a partition mounted through its by-name link shows up under that
# path in /proc/mounts, not as /dev/block/mmcblk0pN.
mount_point() {
    while read src mnt rest; do
        case "$src" in
            /dev/*) [ "$(readlink -f "$src")" = "$1" ] && { echo "$mnt"; return 0; } ;;
        esac
    done < /proc/mounts
    return 1
}

is_mounted() {
    mount_point "$1" > /dev/null
}

# Show one of the pre-rendered screens in /res.
screen() {
    zcat "/res/$1.ppm.gz" > /tmp/screen.ppm && fbsplash -s /tmp/screen.ppm -d /dev/graphics/fb0
    rm -f /tmp/screen.ppm
}

# Make LineageOS reformat a partition on the next boot. fs_mgr formats a
# "formattable" partition when its first 4 KiB are zero (partition_wiped()).
zero_start() {
    dev=$(part "$1") || return 1
    mnt=$(mount_point "$dev") && { umount "$mnt" || return 1; }
    dd if=/dev/zero of="$dev" bs=1048576 count=4 conv=fsync 2>/dev/null
}

# Factory reset: /data (including the internal storage) and /cache.
wipe_data() {
    log "wiping USERDATA and CACHE"
    screen wiping
    if zero_start USERDATA && zero_start CACHE; then
        sync
        log "wipe done"
        return 0
    fi
    log "wipe failed"
    screen error
    return 1
}
