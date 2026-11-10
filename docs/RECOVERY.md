# Recovery and backup plan (EK-GC100)

The goal is never to end up with a camera that cannot be brought back to stock. Read this before any write to the device.

## What can and cannot be recovered

| What breaks | Recoverable? | How |
|---|---|---|
| BOOT, RECOVERY, SYSTEM, CACHE, DATA (bootloop, no display, bad ROM) | Yes | Download mode + Odin + stock firmware |
| Stock firmware itself | Yes | Full `GC100XXBMC4_GC100ITVBLL1_GC100XXBMC3_HOME.tar.md5` in Odin AP |
| RADIO (modem) | Yes | Stock `modem.bin` (in the same firmware) |
| EFS (IMEI, WiFi/BT MAC, calibration) | Only with our backup | `dd` image restore, see below |
| sboot / tz (bootloader) | **No** (JTAG only) | We never flash them |
| eMMC killed by the superbrick bug | **No** | Never wipe with an unfixed kernel |
| M9MO ISP firmware (inside the camera module) | **No** for us | We never let the kernel update it |

## Prerequisites (do before the first flash)

- [ ] Stock firmware on the PC and MD5 verified: `device-dump/firmware/GC100XXBMC4/` (done 2026-10-31).
- [ ] Odin3 v3.10.7 in `device-dump/tools/` (done). Samsung USB drivers installed (done).
- [x] Download mode tested on 2026-10-31 with `adb reboot download`. The device went straight into download mode, and Windows enumerated it as `SAMSUNG Mobile USB CDC Composite Device` (VID 04E8, PID 685D) with drivers OK. **Exit**: hold Power until it switches off, or pull the battery (it is removable). Stock then boots normally.
- [x] **Baseline download-mode screen** (2026-10-31, before any flash): `ODIN MODE`, `PRODUCT NAME: EK-GC100_EUR_XX`, `CUSTOM BINARY DOWNLOAD: No`, `CURRENT BINARY: Samsung Official`, `SYSTEM STATUS: Official`. There is no KNOX or warranty bit on this 2013 bootloader. After our first custom flash, expect `CUSTOM BINARY DOWNLOAD: Yes (1 counts)` and `Custom`. That is cosmetic and changes nothing about recoverability.
- [x] **Hardware key combo for download mode** (confirmed 2026-10-31): from power-off, hold the **zoom lever towards W (the "−" side) + Power**, and keep holding past the boot logo. It works without adb, so it is the fallback when Android or recovery no longer boots. T ("+") + Power is recovery (verified 2026-10-31).
- [ ] Optional but cheap: a Samsung **USB jig** (301 kOhm on the micro-USB ID pin). It forces download mode even when no key combo works.
- [x] eMMC model read and recorded: Samsung `M8G1GC`, not in the superbrick list (see CLAUDE.md).
- [x] User data: the user does not need the photos on the device (2026-10-31).
- [x] Per-device partitions backed up on 2026-10-31 with `backup-critical`: BOTA0/1, EFS, PARAM, BOOT, RECOVERY, RADIO and OTA.
  - Location: `device-dump/backup/2026-10-31-stock/`. Never commit it: it holds the IMEI and MACs.
  - The SHA1SUMS were verified after `adb pull`.
  - BOOT matches the firmware `boot.img` bit for bit, and EFS is a valid ext4.
  - RECOVERY holds the first mini recovery, not stock; the stock recovery is in the firmware package.
  - OTA was all zeros.

## Mini recovery

LineageOS recovery (15.7MB on the i9300) does not fit in the 8MB RECOVERY partition, and we never repartition. We use our own **mini recovery** instead. It is the same approach as StarKissed's 2013 CWM for the GC100 (`github.com/StarKissed/ek-gc100_recovery`): a Samsung GC1 kernel plus a small ramdisk.

**What it is made of**
- The stock GC100XXBMC4 recovery kernel, init/ueventd and adbd. These are known good on this hardware and do not depend on our kernel, so the mini recovery still works when our kernel is broken.
- Our own `init.rc`, `default.prop` (`ro.secure=0`, so adbd runs as root), scripts and status screens. The sources are in `minirecovery/`.
- A static busybox: Alpine `busybox-static` armv7, fetched with `apk fetch --arch armv7` against Alpine's signing keys.

**Size**: about 5.4MB out of 8MB.

**Build**: `tools/build-minirecovery.sh <stock recovery.img> <busybox.static> <out dir>`. It produces `recovery.img` and an Odin `minirecovery-gd1.tar.md5`. The output contains Samsung binaries, so keep it under `device-dump/` and never commit it.

**Enter it**
- `adb reboot recovery`;
- or the **zoom lever towards T ("+") + Power**, held until the mini recovery screen appears. Verified on 2026-10-31 from power-off with the USB cable plugged in.

**Use it over adb.** The stock adbd is a "user" build and always drops to the shell uid (tested 2026-10-31), so the ramdisk carries a setuid-root `su` (`minirecovery/su.c`, a static musl build). Use `adb shell su -c "<cmd>"` or `adb shell` then `su`. The busybox tools are in `/sbin`.

| Command | What it does |
|---|---|
| `backup-critical` | Copies BOTA0/1 (bootloader, read only), EFS, PARAM, BOOT, RECOVERY, RADIO and OTA to `/tmp/backup` with SHA-1 sums. On the PC: `adb pull /tmp/backup`. |
| `flash-image <BOOT\|RECOVERY\|SYSTEM\|HIDDEN> <file>` | `dd` plus read-back verification (after dropping the page cache, so the eMMC itself is read). Boot images must start with `ANDROID!`, and SYSTEM/HIDDEN must be raw ext4. With a `<file>.info`, the source is checked against it before anything is written. Any other partition is refused. |
| `flash-image --restore <EFS\|RADIO> <file>` | Restores a whole-partition backup from `backup-critical`. The image must be exactly the partition size, and an EFS image must be ext4. Without `--restore`, EFS and RADIO are refused. |
| `wipe-data --yes` | Factory reset. |
| `cat /tmp/recovery.log` | What the recovery did. |
| `adb reboot`, `adb reboot download` | Leave the recovery. |

Partitions can be mounted under `/mnt/{system,vendor,data,cache,efs,sdcard}`. Never mount on `/system`: `adb shell` needs the ramdisk's `/system/bin/sh`.

**Factory reset never sends a discard or erase from the stock kernel**
- Samsung's own recovery binary formats with discard, so it is **not** included.
- `wipe-data` zeroes the first 4MiB of USERDATA and CACHE instead. Both are `formattable` in our fstab, so on the next boot fs_mgr sees them as wiped (the first 4KiB are zero) and LineageOS formats them itself.
- A reset requested from Settings works the same way. Android 14 writes `--wipe_data` into the bootloader message on misc (the OTA partition). The mini recovery reads it, clears it and wipes, then reboots.
- Anything else, including `--prompt_and_wipe_data`, waits for a decision over adb.

**Verified on the device (2026-10-31, first version)**:
- it boots from `adb reboot recovery`;
- the status screen is shown correctly;
- the by-name links exist;
- fb0 is 720x1280 at 32bpp (virtual 720x2560, stride 2880);
- the backlight is `/sys/class/backlight/panel`.

Also verified: the T+Power combo, `su`, `backup-critical`, and `adb reboot download` from the mini recovery.

## Getting the partition backup safely

The stock firmware is not rooted, and `/efs` is not readable from adb. Flash only the mini recovery with Odin (a tar holding only `recovery.img`, so only RECOVERY p6 is written). Stock Android keeps booting normally.

Then:
1. `adb reboot recovery`.
2. `adb shell backup-critical`.
3. `adb pull /tmp/backup device-dump/backup/`.
4. Check the SHA-1 sums.

Store the images in `device-dump/backup/` (never committed).

## GC200 (EK-GC200, `sf2wifi`)

The same mini recovery works on the GC200, built from its own stock recovery: `DEVICE=sf2wifi tools/build-minirecovery.sh device-dump/firmware/GC200XXUCNK4/recovery.img ...` (Odin package `minirecovery-sf2wifi.tar.md5`). Differences from the GC100:

- **su does not work.** The stock 2014 kernel (3.0.31) refuses `setuid(0)` from the shell uid with EACCES. init starts `rootd` as root instead, and `rootcmd "<command>"` runs a command through it, for example `adb shell rootcmd backup-critical`.
- **Stock Android restores its own recovery at every boot** (`/system/etc/install-recovery.sh` patches RECOVERY from BOOT). After an Odin flash, untick Auto Reboot and enter the mini recovery directly: hold Power until the camera switches off, then zoom lever towards T + Power (verified 2026-11-10).
- **After writing our kernel from a stock-kernel boot, pull the battery before its first boot.** A warm reboot from Samsung's 2014 kernel (stock Android, or the mini recovery on its stock kernel) into our 3.4 kernel leaves the eMMC and the Wi-Fi SDIO card unusable (`mmc0: cmd_strt_bit not released`, `error -110 whilst initialising MMC card`, no `mmcblk0`). Tested 2026-11-10: the same kernel finds both after a battery pull, and a warm reboot from our kernel to our kernel works too. So: flash, power off, take the battery out for a few seconds, then boot. The first GC200 install guide must say this.
- The download mode screen shows only the Android robot and "Downloading", with no product name or counters.
- The stock recovery has no exFAT modules, so the microSD needs FAT32 there.
- `backup-critical` also saves TDATA (200 MB, mounted as `/tdata` on stock).
- **Backup done on 2026-11-10**: `device-dump/backup/2026-11-10-gc200-stock/backup/` (BOTA0/1, EFS, PARAM, BOOT, RECOVERY, RADIO, OTA, TDATA). SHA-1 sums verified after the pull. BOOT matches the firmware `boot.img` bit for bit, EFS and TDATA are valid ext4, and RADIO and OTA are all zeros. RECOVERY holds the mini recovery.

## Working on a flashed device without Odin

Once LineageOS (a userdebug build) is installed, most changes do not need a reflash:
- **userspace** (HALs, libraries, configs, apps, init scripts): `m <module>`, then `adb root`, `adb remount`, `adb sync` or `adb push`, and restart the service or run `adb shell stop; start`;
- **kernel**: `ONLY_BOOT=1 tools/flash-adb.sh prepare`, `push` and `flash`; or by hand, `adb push boot.img /tmp/` in the mini recovery, then `flash-image BOOT /tmp/boot.img`. `flash-image` runs only in the mini recovery: it needs its `/sbin/sh` and `/sbin/minirecovery-lib.sh`.

If a new kernel does not boot, enter the mini recovery (combo or `adb reboot recovery`) and `flash-image BOOT` the previous image. Odin is only needed when the mini recovery itself is unreachable.

### A whole build over adb (`tools/flash-adb.sh`)

A full build (BOOT, SYSTEM, HIDDEN/vendor) can be flashed without Odin. Run the script in WSL:

1. `tools/flash-adb.sh prepare`: converts the sparse `system.img`/`vendor.img` to raw ext4 with `simg2img`, gzips them, and writes `<img>.info` (raw size and SHA-1) into `~/android/flash-stage`.
2. `tools/flash-adb.sh push` (LineageOS running): copies the images, the `.info` files and the current `flash-image` script to `/data/flash`.
3. `tools/flash-adb.sh flash`: reboots into the mini recovery and mounts `/data` read-only. Not with `noload`: on the stock 3.0 kernel there, `umount` of a `noload` mount never returns (seen 2026-11-01). It then runs `flash-image` from `/tmp` for SYSTEM, HIDDEN and BOOT, in that order, and reboots only if every partition reads back with the expected SHA-1. On any failure the camera stays in the mini recovery. Back in LineageOS it deletes `/data/flash` (about 600MB). `ONLY_BOOT=1` handles the kernel alone.

`flash-image` accepts SYSTEM and HIDDEN only as raw ext4 (it checks the superblock magic and refuses sparse images), decompresses `.gz` images onto the partition, and still refuses the bootloader, PARAM and TZ. Data and settings are kept. The new `flash-image` is staged at runtime, so the mini recovery image itself is not rewritten. Its built-in copy, and `minirecovery-lib.sh` (whose `is_mounted` now also sees by-name mounts), get the changes at the next mini recovery build.

The adb serial differs by mode: the board serial under LineageOS, the old Samsung serial in the mini recovery (stock adbd). `adb devices` shows the one in use; `tools/flash-adb.sh` takes both from `tools/flash-adb.local`.

## Restore procedures

### Bootloop, black screen, or a bad kernel/ROM

1. Enter download mode: `adb reboot download` if adb still works. Otherwise use the key combo or the USB jig.
2. Odin: put the full stock `...HOME.tar.md5` in **AP** (or the partial tar of the broken partition). Options: Auto Reboot + F. Reset Time only. **No** Re-Partition, **no** PIT.
3. Flash and wait for PASS. Don't unplug.
4. If it then bootloops on stock, boot the stock recovery and run wipe data/factory reset. Samsung's recovery formats with discard, but on this eMMC (M8G1GC, not on the affected list) that is what Samsung's own factory reset does.

### A new kernel does not boot

Enter the mini recovery, then `flash-image BOOT <previous boot.img>`. If the mini recovery is unreachable, use Odin with the boot.img tar.

### EFS corrupted (no IMEI, wrong WiFi MAC)

From the mini recovery: `flash-image --restore EFS efs.img`, then reboot. Only use the image taken by `backup-critical` from this camera, and check its SHA-1 against the backup's sums first.

## Rules that prevent the unrecoverable cases

See "Anti-brick rules" in the project CLAUDE.md:

- never flash sboot/tz/PARAM;
- no wipes with unfixed kernels;
- no ISP firmware updates;
- keep a working mini recovery in RECOVERY before testing kernels in BOOT;
- keep the battery at 50% or more and never unplug during Odin.
