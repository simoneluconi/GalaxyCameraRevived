# GalaxyCameraRevived

Goal: run a modern Android (**LineageOS 21 / Android 14**) on the Samsung Galaxy Camera **EK-GC100** and keep the photography features: 21x optical zoom, OIS, P/A/S/M manual modes, flash. **RAW capture is also a goal** (the user asked on 2026-10-31). The stock 4.1.2 firmware has many dead services.

RAW (**works since 2026-11-01**, `setprop persist.vendor.camera.raw 1`; details in docs/TODO.md):
- m9mo.c's `RUNNING_MODE_RAW` sets CAPPARM reg 0x78 to 1, takes frame 0 with `YUVOUT_MAIN=0x05` (raw), then frame 1 as a 16MP JPEG (size 0x33). The HAL restarts FIMC between the two frames.
- **Format**: 20440000 bytes, a continuous big-endian 10-bit stream, 4736x3452 (5920 bytes per row) plus a 4160-byte tail, GRBG. It is the whole uncorrected lens image circle. Decode with `tools/m9mo-raw.py`.
- Black level 64 (dark frame), white 1023, active area x 2..4671.
- The HAL reports the layout as `raw-*` camera parameters; black and white can be overridden with `persist.vendor.camera.raw.black`/`.white`.
- The HAL reads the ISP's exposure after each shot (`exif-*` parameters) and writes `<raw>.txt` next to a RAW.
- **`tools/m9mo-dng.py`** makes a valid DNG from `.raw` + `.txt` (checked with exiftool and LibRaw).
- Still to do: a real colour profile, white balance from the ISP, lens correction and crop, and the DNG writer in our app.

**Publishing**: if the port works, the user wants to publish everything on GitHub. Keep the work repo-shaped from the start: device tree, kernel fork, HAL changes, tools and docs.

Never commit these to a public repo:
- Samsung's proprietary blobs and firmware: `device-dump/`, `system/`, the `.img` files, `RS_M9MO*.bin`, modem.bin. Keep them gitignored; LineageOS convention is a separate `vendor/samsung/gd1` extracted by `extract-files.sh`.
- The device serial, the WiFi MAC (`getprop.txt`, `wifi.txt`), or IMEI/`efs` data.

GPL code (kernel, m9mo.c) and the Apache code from html6405's trees can be published. Licences: the camera HAL and sensors HAL are GPL-3.0-or-later (Replicant), the rest of the device tree Apache-2.0. **GC Camera, GC Gallery, gccore, common and the PC tool are GPL-3.0-or-later** (user, 2026-11-04, to port RawTherapee/darktable code), with the text in `LICENSES/`. The tools, the docs and the lens profile are still to be decided. Keep this unit's serials, MAC and local setup in the git-ignored `CLAUDE.local.md`, never in tracked files. The `play-store-publish` skill covers the GitHub part (README, Pages).

The user is Italian, technically comfortable, and runs a homelab. Talk to the user in Italian. **Everything else is in English**: code, comments, commit messages, docs, README, CLAUDE.md and file names.

## Device

- **Model** EK-GC100, codename `gd1` (product `gd1xx`), board `smdk4x12` / exynos4.
- **SoC and memory**: Exynos 4412 quad A9, 768MB RAM, Mali-400. Panel s6d6aa1 4.8" 720p.
- **Software**: stock Android 4.1.2 (SDK 16), build JZO54K.GC100XXBMC4 (26 Mar 2013), CSC ITV, baseband GC100XXBMC3, bootloader GC100XXBMC4. BMC4 is the last official firmware; there was never a 4.3.
- **Root**: not rooted (`ro.secure=1`, no su). The old shell has no grep/head/wc, so filter output on the PC side.
- **adb serial**: the camera shows one serial on stock and in the mini recovery, and **another under our LineageOS** (the board serial). This unit's serials and WiFi MAC are in the git-ignored local notes: @CLAUDE.local.md
- **Flashing a full build without Odin**: `tools/flash-adb.sh prepare|push|flash` (docs/RECOVERY.md).
- **adb path**: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (not on PATH). **Always pass `-s <serial>`**, because the user's phone is usually connected too (see CLAUDE.local.md).
- **Recovery**: Odin download mode can always restore stock. SamMobile has GC100XXBMC4 firmware.
- **Raw dumps** of the stock system are in `device-dump/stock-4.1.2/` (getprop, packages, cpuinfo, wifi, etc.).

## Key findings (2026-10-31)

1. **The camera ISP is an external Fujitsu M9MO.** Its kernel driver `m9mo.c` (11k lines, GPL) exposes everything as V4L2 controls: `V4L2_CID_CAMERA_OPTICAL_ZOOM_CTRL`, `_ZOOM`, `_PASM_MODE`, `_SHUTTER_SPEED`, `_F_NUMBER`/`_APERTURE_*`, `_ISO`, `_IMAGE_STABILIZER`/`_IS_OIS_MODE`, AF/touch AF, flash, bracketing, WB. The ISP encodes JPEG itself (`V4L2_CID_CAM_JPEG_MAIN_OFFSET/SIZE`, `_THUMB_*`, `_POSTVIEW_*`). The intelligence lives in the ISP firmware, and Samsung's closed HAL is mostly a translator. Copy in `reference/kernel-m9mo/`.
2. **html6405's open Exynos4412 trees are the base.** He is very active and still committing in Oct 2026.
   - github.com/html6405/android_device_samsung_smdk4412-common, branch `lineage-21.0`
   - github.com/html6405/android_kernel_samsung_smdk4412, branches `lineage-21.0`, `lineage-21.0-BPF` and `lineage-21.0-3.0`
   - github.com/html6405/android_device_samsung_i9300, branch `lineage-21.0`. This is the model device tree.
   - github.com/html6405/android_hardware_samsung, branch `lineage-21.0-exynos4`
   - github.com/html6405/vendor_interfaces: reverse-engineered OEM HALs.
3. **smdk4412-common LOS21 ships an open-source HAL1 camera HAL.** It is `camera/exynos_camera.c` by Paul Kocialkowski (Replicant), not the Samsung blob, and it is preset-based (S5C73M3, ISX012, SR130PC20, S5K6A3). S5C73M3 is also a smart ISP with JPEG output, so its architecture is similar to the M9MO's. Its header `videodev2_exynos_camera.h` defines 276 camera CIDs, and **232 of them are also used by m9mo.c**. Copy in `reference/exynos_camera-html6405-lineage21/`.
4. **There is a GC110 (gd1wifi) LineageOS 14.1 kernel**: github.com/treammc/android_kernel_samsung_smdk4412 (July 2026). It is based on the LineageOS cm-14.1 smdk4412 kernel, with defconfig `lineageos_gc1_defconfig` and board files `arch/arm/mach-exynos/gc1-*.c`, `gpio-rev00-gc1.h` and `mdnie_table_gc1.h`. Its fixes include the s6d6aa1 panel porch (`midas-lcd.c`: h_fp=60, h_bp=60, v_fp=36, freq_limit=51), PMIC S5M8767, magnetometer AK8963C and `VIDEO_M9MO`. The GC110 bring-up was done by tubbohere. Its device tree `device/samsung/gc1` is not published.
5. **Other references**:
   - github.com/adamoutler/EK-GC100_Galaxy_Camera: original Samsung GC100 kernel source (2013).
   - github.com/FoxesInLove/RawGalaxyCamera: a work-in-progress project to get RAW/YUV from the GC100. Rooted it with CF-Auto-Root-gd1-gd1xx-ekgc100 + Odin 3.85.
   - The XDA forum (xdaforums.com/c/samsung-galaxy-camera.1938/) blocks automated fetches (403). Ask the user to look at it in a browser.
   - The GitHub projects are mostly one-person efforts, possibly abandoned. Use them as reference only.

## Dumps (2026-10-31)

- `device-dump/stock-4.1.2/system/`: the full /system pulled via adb (1929 files, sizes checked against `system-ls-lR.txt`). The 6 root-only files (`system-unreadable.txt`) were filled in from the firmware image. `system-ls-lR.txt` keeps the original owners, permissions and symlinks, which matter for proprietary-files and fs_config.
- `device-dump/stock-4.1.2/runtime/`: getevent, input devices, sensors, dumpsys (SurfaceFlinger, audio, input, power), /dev, ps, services, /proc. dmesg and /proc/cmdline are root-only.
- `device-dump/stock-4.1.2/rootfs/`: the readable root files. `partitions.txt` has the by-name map. Under `dw_mmc`: p5 BOOT, p6 RECOVERY, p7 RADIO, p9 SYSTEM, p13 USERDATA.
- `device-dump/firmware/GC100XXBMC4/`: the official firmware `GC100XXBMC4_GC100ITVBLL1_GC100XXBMC3_HOME` (samfw.com, MD5 OK). It is identical to what is installed. It contains sboot, tz, boot, recovery, system (sparse; `system.raw.img` is the unsparsed copy), cache, hidden and modem.bin. `boot/` and `recovery/` hold the unpacked zImage and ramdisk (init*.rc, fstab), with the mkbootimg params in `header.txt` (base 0x10000000, pagesize 2048, empty cmdline).
- Camera facts:
  - The M9MO ISP firmware is **FCGB04** (`/sys/class/camera/rear/rear_camfw`). The update files are `system/etc/firmware/RS_M9MO.bin` and `RS_M9MO_EVT3.1.bin`.
  - The stock HAL is `camera.smdk4x12.so` and the stock app is in `system/cameradata` (`SamsungCamera_Galaxy.apk`).
  - `gpio-keys` codes: 0x74 is power. 0x210, 0x215, 0x216, 0x220-0x222 and 0x240 are the shutter and zoom lever. SW 0x0f is probably the flash pop-up. Map them against the gc1 board file.
- Tooling gotchas:
  - In Git Bash, set `MSYS_NO_PATHCONV=1` before adb commands that take device paths.
  - `adb pull` of a directory aborts on the first broken symlink or unreadable file, so pull per directory and then fill in file by file.
  - `tools/simg2img.py` unsparses images and `tools/unpackboot.py` unpacks boot images. Docker Desktop works (alpine + e2fsprogs-extra `debugfs` reads ext4 images).

## Decision: LineageOS 21, not Linux mainline

Linux would need two things built from scratch: a port of m9mo to modern V4L2, and a full camera app. With LOS21 the HAL, apps and Mali/graphics already work on sibling devices (i9300, n7100). Only the board, the modem and an M9MO preset need adding.

**Revisited 2026-10-31: stay on LOS21 for now.** The user asked about Linux because of the low RAM.
- **For Linux**:
  - 755MB usable (MemTotal) leaves ~600MB for the camera app on a minimal Linux, against ~300MB on Android Go;
  - we write our own camera app anyway;
  - the user does **not** need F-Droid apps.
- **Against Linux**: the user wants **3G data and GPS**.
  - 3G on Linux is feasible via Replicant's libsamsung-ipc (XMM6262, as on the i9300).
  - **GPS is not feasible on Linux.** The Broadcom `gpsd_4752`/`gpsd_47511` daemon is a closed Android binary that even links Android's sensor framework, and there is no free driver.
- The considered Linux route was an "appliance": our 3.0 kernel plus an Alpine/musl userspace, booted from RECOVERY or microSD.
- **Decision point**: measure RAM at the first LOS21 boot (idle free RAM, camera start, LMK kills during capture and RAW development). Rethink only if that is bad.
- Possible complement: geotagging from the phone's GPS track (timestamp matching) in the external tool.

## Anti-brick rules (user priority: never brick the camera)

These rules are mandatory. Before any step that writes to the device, state the risk and get the user's explicit OK.

1. **Never flash `sboot.bin` (bootloader), `tz.img`, or PARAM (mmcblk0p4).**
   - A bad bootloader is the only unrecoverable hard brick; everything else can be fixed with Odin + stock firmware.
   - Odin flashes contain only `boot.img`, `recovery.img`, `system.img`, `cache.img`, `hidden.img` (our vendor) or the stock `modem.bin`. Never "Re-Partition", never a PIT file.
   - **Userspace must not write PARAM either.** html6405's smdk4412-common `param.sh` dd's `/vendor/param.lfs` over PARAM on first boot. gd1 sets `SMDK4412_NO_PARAM_SCRIPT := true` (2026-10-31).
     - On our first boot it only copied PARAM to `/data/param.bin`, because we ship no param.lfs.
     - The PARAM diff against the backup was 3 bytes at 0x700200–0x700219, the bootloader's own env (`sys_bootm=0x80000` for recovery oneshot), so this is expected.
     - Audit every init script and blob we inherit for writes to raw partitions.
2. **eMMC "superbrick" bug (Exynos4 + Samsung eMMC on 3.0 kernels).**
   - The trigger is an MMC erase/discard (wipe, format, `discard` mount option) from a kernel without the fix. It can permanently kill the eMMC.
   - Before any wipe or format, read the eMMC name and firmware revision (`/sys/class/mmc_host/mmc0/mmc0:0001/{name,fwrev}`; the risky combination is VYL00M fwrev 0x19 and similar).
   - **This camera's eMMC** (read 2026-10-31): Samsung `M8G1GC`, manfid 0x15, oemid 0x0100, 8GB. It is **not** in the kernel's affected list (`drivers/mmc/card/block.c` MMC_QUIRK_MOVINAND_SECURE: M8G2FA, MAG4FA, MBG8FA, MCGAFA, VAL00M, VYL00M, KYL00M, VZL00M). It is a later generation, so low risk, but stay cautious anyway.
   - Our kernel must keep the brickbug fix. Note that html6405's kernel does **not** gate MMC_CAP_ERASE: `mshci.c` re-enables ERASE/TRIM/DISCARD ("BrickbugAftermath"). Protection comes only from the per-chip quirks in `drivers/mmc/card/block.c`, and M8G1GC is not on that list. Discard therefore reaches our eMMC: daily fstrim, plus mke2fs when fs_mgr formats a `formattable` partition. Stock Samsung recovery does the same on this chip during factory reset, so it is accepted. Don't use old leaked kernels or recoveries to wipe.
   - The mini recovery (docs/RECOVERY.md) never discards. It wipes by zeroing the partition start, and LineageOS reformats it on the next boot.
3. **Never let our kernel update the M9MO ISP firmware.**
   - m9mo.c can write `RS_M9MO*.bin` into the ISP's own flash. A wrong or interrupted write could kill the camera module, which is the whole point of the device.
   - Disable or remove the fw-update paths in our kernel. The ISP stays on FCGB04, which works.
   - The same applies to every chip with its own flash:
     - **Touch controller** (Melfas, `mms_ts_gc.c`): `FW_UPDATABLE 0` since 2026-10-31. It had run its boot-time update check on the first boot of our kernel; the version was 0x16 before and after, so nothing was written.
     - Check any other driver that can flash a chip before enabling it.
4. **Back up first, as the very first action once we have root or a custom recovery**, before touching anything else:
   - `dd` of EFS (p3: IMEI, MAC, calibration), PARAM (p4), RADIO (p7) and BOOT/RECOVERY (p5/p6) to the PC;
   - plus `/efs` and the ISP calibration data if it is reachable.
5. **Test new kernels in the RECOVERY partition first.** Flash our boot image as `recovery.img`: if it fails, stock Android still boots normally. Only flash BOOT once that works. (Samsung has no `fastboot boot`.)
   - Once the **mini recovery** (`minirecovery/`, docs/RECOVERY.md) is in RECOVERY and verified on the device, new kernels go straight to BOOT with `flash-image`. If one does not boot, enter the mini recovery and flash the previous boot.img back.
   - Never overwrite the mini recovery with an untested image.
6. **Flashing hygiene**:
   - battery at 50% or more;
   - a known-good data USB cable on a motherboard port;
   - never unplug during Odin;
   - Odin 3.10.7, with only "Auto Reboot" and "F. Reset Time" checked;
   - keep the stock firmware (`device-dump/firmware/GC100XXBMC4`) ready for restore.
7. **Lens and motors**: optical zoom, focus and OIS moves only use the ISP's own commands and value ranges from m9mo.c. No raw motor or GPIO poking.

## Build profile: camera-first, ultra-light (user decisions 2026-10-31)

The device is a camera, not a phone, and it must be **as light as possible** (about 768MB RAM).

- **No Google services, no microG, no GApps.** Apps are installed from F-Droid or as APKs only.
- **Keep 3G data, data only.** Port the modem and RIL so mobile data works, but drop voice and SMS:
  - overlays `config_voice_capable=false` and `config_sms_capable=false` (tablet-with-data style);
  - no Dialer, Messaging, Contacts or InCallUI, and no telephony UI beyond APN and SIM settings.
- **Low-RAM / Go-style config.** Start from html6405's i9300 `lineage-21.0-mini-go`: `ro.config.low_ram`, zram, low dalvik heap, a Go launcher, minimal system UI effects.
- **App set** (user decisions 2026-10-31):
  - **Camera: OpenCamera** for now (prebuilt already in smdk4412-common/app; Camera1/HAL1 friendly).
    - **Planned: our own dedicated camera app once the system is stable** (user, 2026-10-31). It targets the zoom lever and shutter keys, PASM dials, OIS and RAW/DNG. **`docs/CAMERA-APP.md` is the full brief for whoever builds it** (HAL parameters, capture/RAW behaviour, keys, constraints, workflow); keep it current when the HAL changes.
      - It also develops RAW photos on the device: the shot saves the DNG plus the ISP JPEG, and a background job replaces the JPEG with a better development, by default while charging.
      - An external PC/homelab tool shares the same C++ core and handles the heavy stages.
      - Details are in docs/TODO.md, "Image quality and RAW".
    - Design the HAL and kernel interfaces with that app in mind: expose M9MO controls cleanly through camera parameters or vendor tags, and avoid OpenCamera-specific hacks.
  - **Store: F-Droid client** bundled.
  - **No extra phone-style apps**: no clock, calculator, music or browser.
  - **Keep**: SystemUI, Settings, TrebuchetQuickStepGo launcher, LatinIME, PackageInstaller, DocumentsUI (Files), WebView (framework needs it, no browser app), our **GC Gallery** (replaced Glimpse on 2026-11-04, user decision; prebuilt in gd1 `app/GcGallery`), AdvancedDisplay (mDNIe colour modes), and the invisible telephony needed for 3G data (TeleService, Telecom, CarrierConfig, TelephonyProvider).
  - **Remove**:
    - phone apps: Dialer, Contacts, Calendar, Stk, EmergencyInfo, CellBroadcast;
    - media and utilities: Music, MusicFX, DeskClock, ExactCalculator;
    - browsers and stores: Jelly, Browser2, **AuroraStore** (Play client, which breaks the no-Google rule);
    - system extras: Nfc/NfcNci/Tag/SecureElement, print services, LiveWallpapers, BasicDreams, PhotoTable, EasterEgg, QuickSearchBox, SettingsIntelligence, ThemePicker/Backgrounds/icon packs, Gallery2, Glimpse and GlimpseFrameworksBaseOverlay, SamsungDoze (it is for AMOLED; the GC100 is LCD), Updater (until we host OTAs), AccessibilityMenu, DeviceAsWebcam, CameraExtensionsProxy, QuickAccessWallet;
    - Via (smdk4412-common prebuilt browser).
  - The i9300 mini-go product pulls in about 50 apps today: `get_build_var PRODUCT_PACKAGES` after `lunch lineage_i9300-ap2a-userdebug`.
- **Power and performance are requirements** (user, 2026-10-31: old battery, old hardware). Prefer the option that idles deeper and wakes less.
  - Proper screen-off suspend in every driver (no earlysuspend shortcuts left in the final build).
  - No polling services, and audit wakelocks (`dumpsys power`, `/sys/kernel/debug/wakeup_sources`).
  - Kernel debug and trace options off where safe.
  - Sane cpufreq/governor and hotplug settings for the 4412.
  - The 3G modem should be able to stay off when data is not needed; WiFi/BT/GPS off by default.
  - Measure battery drain (screen-off current, idle hours) as part of testing, not as an afterthought.
- Every package removal is done in our device tree (`PRODUCT_PACKAGES` overrides / `remove-project`), never by patching LineageOS-UL core repos, so it stays rebaseable and publishable.

## Source base: LineageOS-UL (found 2026-10-31)

- **github.com/LineageOS-UL/android, branch `lineage-21.0`** ("LineageOS for Ultra Legacy Devices") is the manifest html6405's work sits on. It replaces about 30 core projects with legacy-patched forks: art, bionic, build, soong, frameworks/av/base/native, hardware/interfaces, system/core/bpf/netd/sepolicy, packages/modules/Connectivity and others. Use it instead of plain LineageOS.
- The device repos come from html6405 via the local manifest `manifests/gd1.xml` (copied into `.repo/local_manifests/`):
  - smdk4412-common `lineage-21.0`
  - i9300 `lineage-21.0` (reference)
  - kernel `lineage-21.0-BPF`, the newest branch; `-3.0` and plain `lineage-21.0` also exist
  - hardware/samsung `lineage-21.0-exynos4`
- **Camera HAL1** (decided 2026-10-31): UL's `frameworks/av` has **no HAL1** (no `libcameraservice/device1`), and the build failed in smdk4412-common's `libstagefright-shim` (`mVideoBufferMode`). `manifests/gd1.xml` therefore replaces it with html6405's `frameworks_av lineage-21.0-qpr3-camera` (Aug 2024; it has device1, the restored HALv1 recording path and the UL/HAL1 commits).
  - UL's `frameworks/base` needs the Android 14 camera AIDL (`CameraExtensionSessionStats`, `CameraIdRemapping`), which html6405's av reverted to 12.1. So `frameworks/base` is html6405's `lineage-21.0-qpr3-camera_hal1-r08` too, which is UL as of 2025-03-30 plus the HAL1 client side.
  - Our `gd1` branch merges UL's final head `aca8bb274` (2025-04-04) on top. The merge was clean.
  - base and av must always come from the same HAL1 pair.
- **Vendor blobs are a gap.** smdk4412-common needs `vendor/samsung/smdk4412-common-treble`, and i9300 needs `vendor/samsung/i9300`, but there is no public LOS21 vendor repo (html6405 and TheMuppets stop at lineage-19.x/wip). The likely fix is to run `extract-files.sh` against html6405's prebuilt LOS21 i9300 zip for the common Exynos4 blobs (Mali etc.), plus our own GC100 dump for the gd1-specific ones.
- **html6405's public i9300 LOS21 build** comes from the XDA thread xdaforums.com/t/rom-unofficial-14-i9300-lineageos-21-0-beta.4648114/. The builds are 07.01.2024, 18.10.2024, 24.10.2024 and 29.10.2024 (latest, matching the i9300 tree's last commit on 2024-10-27).
  - Working: boot, audio, BT, graphics, **cameras** (so the open HAL1 exynos_camera path works on LOS21), sensors, NFC, WiFi, GPS, RIL, HW video.
  - Broken: BT audio in calls, storage encryption (FDE→FBE WIP), random reboots, and OOM on 1GB RAM.
  - We use this zip only as the blob source for `extract-files.sh`.
  - The zip is in `device-dump/i9300-los21/`, and `vendor-listing.txt` lists its vendor partition.
  - It is a block OTA with **separate system + vendor partitions** (Treble on exynos4).
  - It is unpacked in WSL at `~/android/i9300-los21/{system,vendor}.img` with `tools/unpack-ota.sh` + `tools/sdat2img.py`, and loop-mounted read-only on `mnt/{system,vendor}`; remount after a WSL restart.
  - The vendor blobs come from stock I9300XXUGMJ9 (4.3): libMali, libUMP, MobiCore and the SEC OMX.
- Odin3 v3.10.7 is in `device-dump/tools/Odin3_v3.10.7/` (Windows, for download-mode flashing).
- **RAM is the main risk.** The GC100 has about 768MB, less than the i9300's 1GB, which already struggles. Plan on html6405's i9300 branch `lineage-21.0-mini-go` (Go-style low-RAM config) plus zram and a lean package set. The camera app is the priority, not multitasking.
- The i9300 BoardConfig uses `BOARD_KERNEL_BASE 0x40000000`, but the stock GC100 boot.img header says base 0x10000000. Check what the GC100 sboot expects before the first boot.

## Workarounds log

**`docs/TODO.md` tracks every shortcut, workaround or "could be better" item** (the user asked for this on 2026-10-31). Add an entry whenever a hack is taken to move forward, and remove it when properly fixed.

## Tree state and local patches (2026-10-31)

Everything lives in WSL `~/android/lineage`. Run WSL commands from **script files**: `wsl.exe -- bash -c '...'` from Git Bash loses `$VARS`. Local branches named `gd1` hold our patches, and they must become our GitHub forks before publishing.

- `device/samsung/smdk4412-common` (branch `gd1`):
  1. Drop the prebuilt Nfc app (it clashes with packages/apps/Nfc; html6405 patched build/make instead).
  2. Replace the CM-era extract scripts with LOS21 extract-utils ones. `proprietary-files.txt` now lists only the common 4412 blobs (Mali + EGL, MobiCore clients, mfc_fw, libsecnativefeature); `common.mk` includes `smdk4412-common-treble-vendor.mk`.
  - Generate the vendor with: `./extract-files.sh ~/android/i9300-los21/mnt`.
- `hardware/samsung` (branch `gd1`):
  - make the `SamsungServiceMode/Android.mk` include optional (the dir is empty);
  - use `String8::c_str()` in the exynos4 hwcomposer and libcamera, because `string()` is private in UL's libutils.
- `packages/modules/adb` (UL, branch `gd1`, tracking `losul/lineage-21.0`): f31cfce starts legacy FunctionFS transports on the device. Without it adb stays "offline" on kernels without ffs aio. The adbd trace (`persist.adb.trace_mask`) goes to `/data/adb/adb-*`.
- `bionic` (UL, branch `gd1`): html6405's "Hack: Ignore invalid pthread_t" (libMali passes a bad pthread_t to pthread_join on context destroy).
- Native crash backtraces on kernel 3.4 (2026-11-01, verified: tombstones in `/data/tombstones` with full backtraces):
  - `system/core` (UL, branch `gd1`) cf3a6bb: tombstoned falls back to a named temporary file when `O_TMPFILE` fails (EISDIR on 3.4).
  - `system/unwinding` (branch `gd1`) 5b4cd57: libunwindstack falls back to `PTRACE_GETREGS` when `PTRACE_GETREGSET` fails with EIO, because 32-bit ARM 3.4 has no `HAVE_ARCH_TRACEHOOK`.
- `frameworks/av` (html6405 `lineage-21.0-qpr3-camera`, branch `gd1`): `camera/camera_platform.aconfig` takes UL's full camera flag list, because UL's frameworks/base needs those flags (e.g. `camera_hsum_permission`).
- `device/samsung/gd1` (new git repo, branch `lineage-21.0`): `legacy/health/` holds AOSP health@2.0 impl, libhealthservice, libhealthstoragedefault and the generic `android.hardware.health@2.0-service`, all removed by UL but needed by html6405's exynos4 HAL. It has its own `license` module.
- `manifests/gd1.xml`: `hardware/lineage/interfaces` → html6405 `lineage-21.0-qpr3`, because UL dropped `vendor.lineage.power@1.0`. Do NOT swap `hardware/interfaces`: html6405's fork (Jan 2024) is too old for UL's QPR3 base.
- `external/rust/crates/atty` was found emptied by the interrupted first sync and restored with `git checkout`. If odd "undefined module" errors appear, run a dirty-checkout scan.
- The reference target `lineage_i9300-ap2a-userdebug` passes analysis. Build log: `~/android/build-i9300.log`.

**LineageOS-UL 21 is frozen at 2025-04-04.** Every UL fork's last commit is the same "Merge github/lineage-21.0" of that day. LineageOS kept changing lineage-21.0 afterwards (e.g. the `IUserSwitchObserver.onBeforeUserSwitching` signature changed in Jan 2026), so mixing UL with today's LineageOS breaks the build.
- `manifests/zz-lineage-snapshot.xml` (generated by `tools/pin-snapshot.sh <root> "2025-04-05 00:00" <out>`) pins all 232 `LineageOS/*` projects to their last commit before that date via `<extend-project revision=...>`; 97 of them actually moved back.
- The pin uses `rev-list --first-parent --before`. Without `--first-parent`, merged AOSP side branches (which keep their old dates) get picked, e.g. a Wifi commit still using the removed `product_variables.pdk`.
- AOSP projects are already pinned by UL to `android-14.0.0_r67`.
- Repo groups: `repo init -g default,-qcom,-device,-mips,-darwin,-notdefault --depth=1` (1333 projects). Qualcomm HALs are useless here and the snapshot's qcom-caf display repos break analysis.
- Our patched projects sit on **`gd1` topic branches tracking the html6405 upstream** (`branch.gd1.remote=html6405`, `branch.gd1.merge=<upstream>`), so `repo sync` rebases them instead of detaching HEAD. A plain `git checkout -B` branch gets detached by sync; that already happened once.
- Consequence: the Android security patch level is about April 2025. It is accepted for now and tracked in docs/TODO.md.

**Watch**: html6405 is actively developing **LineageOS 23.2 (Android 16)** for smdk4412-common, hardware/samsung and core forks, with commits on 2026-10-31: Go defaults, legacy Mali, Wi-Fi-only libcutils. There is no 23.2 kernel or i9300 branch yet. LOS21 stays our target for now (lighter, and there is a known-good binary); reconsider once 23.2 matures.

## Partitions: no repartition needed (2026-10-31)

html6405's i9300 LOS21 build needs a **custom PIT** (recovery 16MB, vendor ~560MB on HIDDEN). We never flash a PIT. The GC100 stock layout fits as is:

| by-name | GC100 size | use in our build |
|---|---|---|
| SYSTEM p9 | 1572864 KB = 1610612736 B | system; same as html6405's `BOARD_SYSTEMIMAGE_PARTITION_SIZE` |
| HIDDEN p10 | 368640 KB = 377487360 B | **/vendor** (as html6405 does); the i9300 vendor uses only 86MB. Stock hidden.img is preload junk and is restored by Odin stock. |
| BOOT p5 | 8192 KB | boot; html6405's boot.img is 6.2MB, OK |
| RECOVERY p6 | 8192 KB | **too small for the 15.7MB LOS recovery**. Use a small recovery (stock-kernel backup recovery, or a slimmed/xz LOS recovery); Odin does the flashing anyway. |
| CACHE p8 | 1048576 KB | cache |
| USERDATA p13 | 4337664 KB = 4441767936 B | data |
| OTA p11 | 8192 KB | /misc (as html6405's fstab) |
| EFS p3, PARAM p4, RADIO p7 | | untouched (anti-brick rules); RADIO = stock modem |

## GC100 hardware vs i9300 (from the stock dump)

- **Modem: Intel XMM6262** (`ril.modem.board`), same as i9300, with the same Samsung IPC RIL (`/dev/ttyS0`, cbd). The i9300 LOS21 RIL stack likely works; keep the GC100 modem firmware.
- **Wi-Fi/BT: Broadcom BCM4334** (+ murata variant), same chip as i9300. **Use the GC100's own `nvram_net.txt*`** (board RF calibration) and its bcm4334*.hcd.
- **GPS: gpsd_4752** (BCM4752, `gps_launch_gd1.sh`); the i9300 uses glgps/47511, so these differ.
- **Compass: AK8963C** (libakm); the i9300 has AK8975.
- MobiCore (`mcDriverDaemon`), Mali and UMP match the common 4412 set.
- **Kernel**: html6405's `lineage-21.0-BPF` already has `MACH_GC1`, gc1-gpio/power/jack.c, m9mo.c/h and midas-camera.c, identical to treammc's GC110 kernel. Still missing:
  - the s6d6aa1 porch fix in `midas-lcd.c` (h_fp 50→60, h_bp 15→60, v_fp 3→36, add freq_limit 51; check against Samsung's GC100 source in adamoutler/EK-GC100_Galaxy_Camera `master`);
  - a `lineageos_gc1_defconfig` (start from treammc's, adapt to LOS21 i9300's, add the XMM6262 modem bits for GC100).
- References are in WSL `~/android/ref/`: `treammc-kernel`, `los-cm14.1-kernel`, `html6405-hwif`.

## Plan

1. **Pull stock blobs and configs** via adb (no root needed for most of /system): `/system/lib/hw/camera*`, `libcamera*`, `libsec*`, the stock camera APK, `/system/etc` (media_profiles.xml, audio, gps), `/system/vendor`, firmware and init*.rc. Also `/proc/partitions`, `/dev/block/platform/*/by-name` if readable, and the M9MO firmware file(s). Put everything under `device-dump/`.
2. **Set up the build env**: WSL2 Ubuntu on this PC (Ryzen 7 3800X 8c/16t, 64GB RAM, ~520GB free on C:). WSL is not installed yet; only docker-desktop exists, and installing may need a reboot, so ask the user first. Then `repo init` LineageOS 21 plus html6405's manifests and trees. The download is roughly 100GB and the full build needs 250-300GB.
3. **Kernel**: port the gc1 board files, defconfig and M9MO from treammc's kernel (cm-14.1 base) into html6405's `lineage-21.0` smdk4412 kernel. Add the GC100 modem and 3G parts, which the GC110 lacks. 3G is needed for data only.
4. **Device tree `device/samsung/gd1`**: based on i9300 + smdk4412-common, with GC100 partitions, modem/RIL, sensors and the panel.
5. **First boot** with display, touch and WiFi. The camera comes later.
6. **M9MO preset in exynos_camera.c**: preview, capture with JPEG offsets, then optical zoom, PASM/shutter/aperture/ISO and OIS. The physical zoom lever and shutter key need to be mapped too.
7. **Dedicated camera app** (after the system is stable). It replaces OpenCamera and drives the hardware fully: zoom lever, half-press focus, PASM, OIS and RAW. It is a separate repo, in English, publishable.

## Status

- [x] Device identified, stock dumps saved
- [x] Feasibility research, base trees chosen
- [x] Pull stock blobs + official firmware (see "Dumps" below)
- [x] WSL2 build env: Ubuntu-22.04 (user in CLAUDE.local.md). Use `wsl -u root` for admin; never store the user's password. Build deps and repo 2.71 are installed, ccache is 50G, and `.wslconfig` sets 48GB RAM, 16GB swap, 16 CPUs. Git identity is `simoneluconi` / `12747695+simoneluconi@users.noreply.github.com`.
- [x] Source sync into `~/android/lineage` in WSL. Use `--depth=1`, LineageOS-UL manifest + `manifests/gd1.xml`; logs are in `~/android/sync*.log`.
- [x] Kernel port (builds; not booted yet)
- [x] Device tree. **First full build OK on 2026-10-31.**
  - Build with `m droid`, not `bacon`: there is no OTA zip without a recovery. Patch level 2025-03-01.
  - Odin tars are in `device-dump/lineage-gd1-20261031/`: `boot`, `system`, and `hidden` (= vendor.img).
  - Mini recovery: `device-dump/minirecovery/` (stock kernel) and `device-dump/minirecovery-ourkernel/` (our kernel + the same ramdisk, the kernel test for step 2 of the flash plan).
- [ ] Flash plan, one step at a time with the user's OK each time:
  1. **done 2026-10-31**: mini recovery (stock kernel) to RECOVERY, then `backup-critical`. The backup is in `device-dump/backup/2026-10-31-stock/` and verified. `su` is needed because the stock adbd drops root;
  2. **done 2026-10-31**: our kernel + mini ramdisk to RECOVERY. It boots, and display, touch, keys, sensors, charger and USB work (`device-dump/ourkernel-dmesg-1.txt`);
  3. **done 2026-10-31**: LineageOS boot/system/hidden, then `wipe-data`. **It boots to the LineageOS setup wizard.**
     - Fixes needed on the way: camera HAL disabled (the S5C73M3 preset crashed the boot), Mali GLES modules in /system/lib/egl, no LiveDisplay IAdaptiveBacklight, and `param.sh` skipped.
     - adb is still "offline". Debug through the **USB ACM root console**: `tools/serialsh.ps1 -Port COMx`, files via `tools/serialpush.ps1`. Windows shows it as "SAMSUNG Mobile USB Modem".
- [x] First boot: home screen reached on 2026-10-31
- [x] **Stable daily-use base, end of 2026-10-31**:
  - stability fixes: AN30259A LED off, vmalloc 256m, bionic pthread_t hack;
  - debloat: 75 apps overridden plus the Mainline apps disabled by gd1-setup.sh (never sdksandbox);
  - landscape display and touch, no lock screen, no setup wizard, slimmer QS tiles and Settings.
  - The latest Odin package is in `device-dump/lineage-gd1-20261031/`.
- [ ] **Next, agreed order** (user, 2026-10-31):
  1. **adb: fixed 2026-11-01.** It was an upstream adbd bug: the legacy FunctionFS transport was never started (`packages/modules/adb` branch `gd1`). adb now works as root, so use it instead of the serial console. Also fixed: the screen woke ~5 s after every timeout because of doze; gd1 now has no ambient display;
  2. **sensors HAL: done 2026-11-01** (gd1 d4dcd1a, `libsensors/`). LSM330 accel + gyro and AK8963C compass, with a 90° rotation into the landscape frame. Auto-rotation is verified on the device. The compass is not yet checked against a real heading;
  3. camera M9MO preset, with lens-retract safety and the shutter/zoom keys.
     - **Camera HAL (gd1 `camera/`, last ead43a0)**: preview, optical zoom, AF, AE, 16MP JPEG, flash and its pop-up state, RAW and DNG, the exposure readout (`exif-*`) and **P/A/S/M** all work. The ISP powers up only when the preview starts, so the lens stays in at boot.
       - OpenCamera cannot drive PASM. Test it with `setprop vendor.camera.test.{pasm,shutter,aperture,iso}`.
       - Next: the colour calibration from the user's RAW+JPEG shots, then our own camera app (docs/TODO.md).
     - **Our camera app "GC Camera" is in `camera-app/`** (started 2026-11-02, running on the device).
       - It is Kotlin + framework Views, light theme by default, saving to the microSD.
       - Brief, decisions and code map: docs/CAMERA-APP.md §1 and §9. Workarounds: docs/TODO.md "Camera app".
       - The user splits the work: one session does the app, another the HAL/kernel. HAL requests go to the HAL session.
       - **Code review of 2026-11-01** (HAL, kernel, sensors, tools): the fixes are flashed and verified. What is still open is in docs/TODO.md, "Code review of 2026-11-01". Native crash backtraces now work.
     - **Our gallery "GC Gallery" is `camera-app/gallery/`** (started 2026-11-04; module `:common` shares the theme). It is a separate APK, `com.simoneluconi.gcgallery`. Brief, the camera↔gallery contract (EXIF thumbnail, REVIEW intent, XMP `gc:` fields, rating padding) and the code map are in docs/GALLERY-APP.md; workarounds are in docs/TODO.md "Gallery app". A third session does the gallery; changes to `app/` or existing gccore files go through the camera session.
     - **Full build flashed over adb on 2026-11-01** with `tools/flash-adb.sh` (first real use; it needed `wait-for-recovery`). Everything that had been hand-swapped is now in the build.
     - **Keys: done 2026-11-01** (gd1 a56d14e, `keylayout/gpio-keys.kl`). Half press is FOCUS (528), full press CAMERA (544), the zoom lever ZOOM_IN/OUT (slow 533/534, fast 545/546; the fast stage arrives nested inside the slow one). Verified on the device. LineageOS camera button settings are enabled (`config_deviceHardwareKeys=32`).
     - **Lens safety: done and verified 2026-11-01** (kernel adbb5a51, 0fb751df, abbe35eb). A reboot with the camera open now retracts the lens; the user watched it.
       - At reboot, init SIGKILLs the camera HAL, and the FIMC release runs `m9mo_remove` → `m9mo_set_lens_off`.
       - Two bugs had left the lens out:
         - the interruptible ISP wait returned at once on the pending signal;
         - the lens-off waited for `INT_LENS_INIT`, which `m9mo_init_param` masks, so every close sat out the 5 s timeout.
       - It now uses Samsung's final polling lens-off (LENS reg 0x28: 4 = retracting, 0 = in). A normal close takes ~1 s and a reboot ~6 s.
       - The `m9mo_shutdown` i2c hook stays for a power off without a release.
     - **Pop-up flash: done 2026-11-01**. Kernel dfd9146f provides `SW_STROBE_INSERT` and sysfs `strobe_insert`, where 1 = up. The HAL reports `flash-popup=up|down` from the ISP (gd1 688b341). With the flash closed and the mode "on", the shot completes without firing.
  - **3G, GPS, Wi-Fi and CPU hotplug (2026-11-01)**; details are in docs/TODO.md.
    - The modem boots and sees the network; data is not tested yet (no SIM). cbd had never run, because no device sepolicy was built; gd1 now has `sepolicy/vendor`.
    - `/vendor` is now read-only and not `nosuid`. On kernel 3.4 a nosuid mount kept every vendor service in the `init` domain.
    - The vendor blobs had the i9300's Wi-Fi and BT files. `tools/mk-blobsrc.sh` builds the blob source now. Wi-Fi works on 2.4 GHz; 5 GHz association is rejected (open).
    - GPS: the stock BCM4752 daemon and HAL behind shims (`gd1/shims`); the chip reports satellite status, a first fix is still to be seen outdoors.
    - Kernel: pegasusq with CPU hotplug.
  - Then BT and audio checks, SELinux enforcing, and power measurements.
  - Small items are in docs/TODO.md: vibration strength, QS lag, second debloat round, Settings entries.
- [ ] Camera HAL M9MO preset (mostly working, see step 3 above)
