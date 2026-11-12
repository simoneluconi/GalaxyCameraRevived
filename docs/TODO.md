# Workarounds and improvements

This file tracks shortcuts taken to get the first boot working, and things that work but could be done better. Each entry says where the shortcut is, why it was taken, and what the proper fix would be. Remove an entry once it is fixed.

## Before release: remove test-only logging and files (user, 2026-11-02)

The user's storage must not fill up with debug output. Keep only what explains crashes and kernel panics, all size-capped by Android itself: tombstones, ANR traces, `last_kmsg`/pstore and dropbox. Remove or switch off everything that only exists for tests:
- HAL bring-up paths that write files: `persist.vendor.camera.raw` (raw frames and `.txt` in `/data/vendor/camera`) and `vendor.camera.test.raw_burst` (RAW burst frames in the same directory). Also the `vendor.camera.test.*` PASM overrides.
- Logd persistence (`/data/misc/logd`, `logpersist`) and the adbd trace (`persist.adb.trace_mask`, `/data/adb/adb-*`) if enabled.
- Leftovers in `/data/local/tmp` (this unit only): test binaries, APK copies and backups removed on 2026-11-06. The logs (`camtest*.log`, `kmsg*.log`, `wpa.log`, `wd.txt`, `memwatch*.log`) and the camera session's `gctest.sh`/`gcdev` stay, at the user's request.
- Logd persistence is on: 256 files of ~2.2 MB, about 560 MB on /data (504 MB on 2026-11-06, back to the first boot). The user wants debug logs kept and decided on 2026-11-06 to leave it as is for now, public builds included; revisit before the first release if storage matters.
- Verbose kernel logging added for bring-up (e.g. the `m9mo_wait_capture_interrupt` per-interrupt `cam_info`), and the HAL's per-frame `ALOGD`s, down to what a user build needs.

## Kernel (`kernel/samsung/smdk4412`, branch `gd1`)

- ~~Touchscreen `mms_ts_gc.c` has no screen-off suspend.~~ **Fixed 2026-11-11 (kernel 4b7ff31c)**: an fb notifier powers the controller down when fb0 blanks and up when it unblanks; system resume leaves it off while the screen is blank. Checked on both cameras over adb (power off/on, I2C answers after resume); touch by finger after a blank still to be confirmed by hand.
- **External display `s3cfb_extdsp` has the same earlysuspend `#undef`.**
  - Its `!CONFIG_HAS_EARLYSUSPEND` suspend/resume had never been compiled by Samsung. They were rewritten to mirror the earlysuspend ones and are untested.
  - Check whether the GC1 actually needs `CONFIG_FB_S5P_EXTDSP` (Samsung enabled it). If not, disable it.
- **microSD: fixed 2026-11-02**, verified on a Samsung 32GB card, vfat and exFAT.
  - vfat did not mount: Android 14's vold always passes `time_offset=<minutes>`, which kernel 3.4's FAT driver did not know (EINVAL). Kernel 3501a1ea backports mainline's `time_offset=` option. Timestamps survive a remount.
  - exFAT mounts as is. The card is now exFAT (label GC100, UUID EBEF-F525).
  - The card ran at the 25 MHz default speed (about 7 MB/s): `mmc_read_switch()` recorded high speed only for pre-SD 3.0 cards, and this slot has no UHS-I. Kernel b773eaef fixes that (mainline did too): 50 MHz high speed, checked with an md5 of 64 MB of random data.
  - Read-ahead: 512 KB for SD cards, set in the kernel (9e46b1e2) and again in gd1 after smdk4412-common's tweaks.rc. Files now read at about 19 MB/s and write at about 11 MB/s; raw eMMC reads run at 52 MB/s.
  - gd1 231a8fe sets `external_storage.sdcardfs.enabled=false` (FUSE and bind mounts, as on GKI). `CONFIG_SDCARD_FS` could go from the defconfig too.
- ~~A dentry leaks on the microSD when the camera app saves a photo~~ **Fixed 2026-11-11** (kernel b6060ff9; the trace is 85bd1f22): the leak was in the `renameat2` backport in `fs/namei.c`, not in exFAT. It looked the target up twice (once moved up for `RENAME_NOREPLACE`, once where 3.4's `renameat` did), the second lookup overwrote `new_dentry`, and every rename on any filesystem leaked a reference on the target dentry. MediaProvider renames `.pending-*` into the final name, so each saved photo (and its thumbnail) left a negative dentry behind. Found with a dentry reference trace added to `fs/dcache.c` (off by default, `/sys/module/dcache/parameters/trace_name` and `trace`), which showed the `A` from `sys_renameat2` with no matching `dput`. After the fix, a shot or two plus an unmount of the card log no leak. The stopgap in `shrink_dcache_for_umount_subtree()` stays. Old notes:
  - Symptom: at the next reboot, unmounting the card finds a negative dentry still referenced, e.g. `BUG: Dentry {i=0,n=GC100_0010.dng} still in use (1) [unmount of exfat mmcblk1p1]`. First seen through sdcardfs (`GC100_0003.jpg`), then on exFAT itself with sdcardfs off.
  - That `BUG()` in `shrink_dcache_for_umount_subtree()`, with `oops=panic` from the bootloader, panicked the reboot. The emergency reboot also lost the "reboot to recovery" request, so `flash-adb.sh` waited forever.
  - Stopgap: kernel `fs/dcache.c` now warns and leaves the dentry allocated, as mainline's `umount_check()` does. A leaked positive dentry would also keep its inode ("Busy inodes after unmount").
  - Not reproduced with a plain `echo > file` from the shell, only with files the app saved. MediaStore creates a pending file through FUSE and renames it into place, so suspect `exfat_rename()`, or `exfat_lookup()`'s `d_find_alias()`/`d_move()` alias handling, in Samsung's `fs/exfat`.
  - Reproduced on the first photo (2026-11-02):
    - Sequence: MediaStore insert with IS_PENDING=1; write through `openFileDescriptor("rw")`; ExifInterface rewrite through the same fd after an lseek to 0; update IS_PENDING=0, which is MediaProvider's rename `.pending-*` → `GC100_0011.jpg`.
    - Then `sm unmount public:179,17` gives `BUG: Dentry {i=0,n=GC100_0011.jpg} still in use (1) ..., leaking it`. The stopgap holds: the unmount completes and the card remounts.
    - A negative dentry for the final name keeps a reference: look at the rename onto a name that does not exist yet, and at the lookups MediaProvider makes before it.
  - Test vfat as well, to tell an exFAT bug from a FUSE/VFS one.

## Gallery app (`camera-app/gallery/`, docs/GALLERY-APP.md)

- **Cold thumbnails are slow without an embedded EXIF thumbnail.** BitmapFactory 1/8 over FUSE from the microSD took 0.4–1.9 s per 16 MP JPEG (2026-11-04, measured while GC Camera's preview was running), so the first pass over an existing folder takes a while. The disk cache makes it a one-time cost. The fix is GC Camera embedding a ~320x240 thumbnail (agreed with the camera session). Still possible: a third worker, or a pre-warm job while charging.
- **Star ratings fall back to a full rewrite** (ExifInterface `saveAttributes`, seconds over FUSE) when a JPEG has no XMP packet with padding. That covers every shot taken before GC Camera writes `xmp:Rating` + padding (agreed). A DNG without room keeps the rating only in the gallery's `RatingStore`.
- **No screen rotation for portrait viewing.** The UI is fixed landscape. The camera's in-place control rotation (OrientationSensor) is not ported yet, so a portrait photo is shown small.
- **The camera folder is recognised by name**: any `DCIM/GC<3 digits>/` (`MediaIndex.isCameraFolder`). GC Camera's folder name is a setting, so a renamed folder shows as a plain album.
- **The thumbnail grid uses MediaStore's DATE_TAKEN for day sections**; pictures without it fall back to DATE_MODIFIED.
- **The trash relies on MediaProvider's idle maintenance** for the 30-day expiry. It is not verified that it runs on this ROM.
- **The viewer's photo background follows the theme**; a neutral grey/black option for judging colour is still to add (settings screen).
- `:app` still has its own copies of the colour tokens, `Ui.kt` and `HistogramView`. Moving it to `:common` is the camera session's call.
- **Code review of 2026-11-04, still open** (the crashes, leaks, cache wipes, races and the XMP overwrite are fixed):
  - Each index reload (every shot) filters the photo list several times on the main thread, in `GridActivity.refresh` and `ThumbGridView.buildSections`. That is fine at ~200 photos; move per-scope lists, counts and day keys into the `MediaIndex` worker before thousands.
  - `onDraw` still boxes the Long ids, through `LruCache<Long>`, `fading` and `selected` (2-3 small allocations per visible cell per frame). Use a LongSparseArray or a field on `Photo` if scrolling stutters.
  - `RegionSource` re-reads the JPEG (3-5 MB over FUSE) that the screen decode just read. Keep the current photo's bytes for zooming if 100% feels slow to start.
  - The histogram can be computed twice when the info panel opens while a decode lands.
  - The rotation fallback (`ExifInterface.saveAttributes`, only for a JPEG with no Orientation tag) opens the file without `setRequireOriginal`, so in theory redacted GPS could be written back. GC Camera's files always have the tag.

## Camera app (`camera-app/`)

- **Zoom lever steps `setZoom(±1)`** every 220 ms (slow) or 90 ms (fast), so the motor stops between steps and the zoom stutters (the user noticed, 2026-11-02). The app switches to the HAL's continuous `zoom-action` automatically once `zoom-action-values` appears.
- **The M exposure meter is estimated** from the preview's centre-weighted luma until the HAL reports `live-bv`. It is marked "≈" on screen. It assumes the preview shows the manual exposure, which is not verified.
- **Auto/P/A/S readouts** (fixed 2026-11-03):
  - The app had ignored `live-*`, because the caps are read before startPreview and the HAL publishes them only once the preview runs. It now uses them as soon as a poll carries them.
  - The shutter shown is the shot's planned one, from the M9MO's SMART_READ1. In the dark the preview's TV register reads 0, and it is capped at 1/30 anyway. The decoding comes from Samsung's app and was checked against EXIF: 1 s f/2.8 ISO 400.
  - The app decodes it from `m9mo-live-raw` until the HAL publishes `live-shot-tv/av/iso`; it already prefers those keys. Remove the decoder (`SmartRead1` in `M9mo.kt`) once they are in.
  - ISO from SMART_READ1 is only in whole stops (100/200/400…), so the app shows `live-iso` while the preview's AE is in range.
  - A/S checked 2026-11-04: in A the time follows the aperture (f/8 gave 1/8 at ISO 400), and in S the aperture and ISO follow the time. EXIF matches.
- **Mode tests of 2026-11-04** (test hook: the `TEST_SHOT` broadcast in debug builds, `ViewfinderActivity.testShotReceiver`): A, S, M up to 16" with RAW, AE bracket with and without RAW, and Home during a RAW shot all work. Fixed on the way:
  - the first-frame watchdog was a fixed 15 s, so a 16" exposure got its preview restarted mid-exposure and the HAL lost the frame. It now adds the exposure time (the set time in S/M, the ISP's plan otherwise);
  - settings changed during a capture never reached the following shots. The app now holds them until startPreview; the HAL got its own fix the same day;
  - the EXIF exposure time is snapped to the ISP's steps within 1/6 EV (TV 800 had printed as 1/256);
  - `bracket-exif` was never stored, because the HAL's parameter strings cannot hold ';'. The HAL now separates frames with '|', and each bracket frame gets its own EXIF.
- **JPEG save** (fixed 2026-11-04): it took 3–13 s, because ExifInterface rewrote the whole 4 MB file through FUSE. `storage/JpegMeta` now writes the metadata in one pass in front of the ISP JPEG:
  - EXIF with a 320x240 IFD1 thumbnail;
  - XMP with xmp:Rating, the gc: capture tags and 2 KB of padding, so the gallery can patch it in place.
  - The JPEG now lands ~1 s after the shot. The first shot after app start takes ~5 s, as the JIT warms up.
  - exiftool validates the JPEG and the DNG without warnings.
  - The DNG still takes 5–6 s after the JPEG: 36 MB through FUSE, at about the card's write speed.
- **HAL options not yet in the app** (HAL cf42ec64, 2026-11-03). Measured 2026-11-04 on one indoor scene (~3000 K), set with the viewfinder open:
  - `whitebalance=kelvin` + `wb-kelvin` (3000–10000, step 100): works, direction correct. 3000 K gives B/G 1.34, 5500 R/G 1.38 / B/G 0.85, 8000 R/G 1.36 / B/G 0.68, and auto 1.00/0.97. To do: a Kelvin row in the Fn menu's WB section, with EXIF WhiteBalance = manual.
  - `sharpness`, `contrast`, `saturation` (−2..2): all work, in roughly even steps.
    - Laplacian std 4.4 / 6.0 / 7.7.
    - Luma std 48.8 / 58.2 / 65.4.
    - Saturation 0.057 / 0.078 / 0.104.
    - To do: ISP JPEG "look" settings under Settings → Image. Sharpness −1 or −2 should mean fewer halos.
  - `wdr` (on|off): works, mildly on this scene. Luma std drops 58.1 → 54.6, and shadows and corners lift a little. Try it on a backlit scene.
  - `lens-shading-correction`, `lens-distortion-correction`: work since the kernel writes PARM 0x07/0x1B in parameter mode. Before that fix, set from monitor mode, they had no effect. The readback shows both on by default.
    - **LDC off**: the ISP outputs the whole uncorrected image circle, with barrel distortion and black corners, like the RAW.
    - **LSC off**: about 2% darker towards the edges.
    - Keep both on, the default. They are not in the app; LDC off could become an "uncorrected / full circle" option later.
  - `flash-ev` (−2..2): **no effect** (2026-11-04). The scene was a dark room lit only by the flash. In M 1/30 f/4 ISO 100, −2…+2 gave mean luma 68–72 with no trend; P was the same. Kernel #45 reads CAPPARM 0x28 back, and the value lands (step 4, readback 4), so the ISP ignores it in this flash mode. Not in the app; likely to be dropped from the HAL.
- **ISP low-light limits** (measured 2026-11-04, dark room):
  - auto, P and A cap the exposure at 1 s and ISO 400. In A at f/8 that leaves the frame ~3 EV under; only S and M go longer (16" works).
  - An AE bracket at that cap has nothing left to vary when the ISO is fixed: the test had ISO 100 left over from M, so all three frames came out 1 s, ISO 100, equally dark. That is expected, not a bug. With ISO auto, the bracket at the cap is still to try.
  - App idea: warn when A/P hit the cap ("use S/M or raise ISO").
- **Self-timer lamp** (`timer-led`): done and verified 2026-11-03. The app sets it at countdown start and turns it off at the end, on cancel and on pause; the ISP blinks it slow, then fast.
  - The lamp needed its LDO powered: kernel #44.
  - Self-timer + RAW + OIS off had failed ("No frame 0"). Fixed in HAL 51e09e92, which no longer sends TIMER_MODE.
  - Verified: GC100_0077 (5 s, RAW, OIS off) and GC100_0078 (10 s, RAW, OIS on), with the user watching the lamp.
- **Theme on the very first launch**: `UiModeManager.setApplicationNightMode` only takes effect from the next process start, so the first launch after install follows the system theme. Harmless; it fixes itself on the next start.
- **Format SD** opens Android's storage settings, because formatting needs the privileged `MOUNT_FORMAT_FILESYSTEMS`. It becomes in-app once the app ships in the ROM as a priv-app (the HOME launcher step).
- **On-device RAW development ("Advanced" JPEG): removed** (user, 2026-11-04). It only reached parity with the ISP JPEG at ISO 160 and lost at ISO 400; the outcome and the numbers are in docs/RAW-PIPELINE.md.
  - The app saves the ISP JPEG plus the DNG, and development is left to PC tools.
  - gccore keeps the DNG writer and `matchIsp` (the ISP's white balance for AsShotNeutral, via its tone curve and matrix in `isp.h`).
  - The develop code, the RCD port, the noise model and `gcdev` are in git history (e8e6fa0, ff0c19f), should it ever come back.
- **Lens calibration gaps** (gccore `lens.cpp`):
  - The Lightroom Distortion value (−k1 × 100, so 27 at 4.1 mm) is a guess. The user checks it by eye on the test DNGs in Downloads\GC100-confronto-RAW, then the mapping gets rescaled.
  - Zoom steps 7.5, 11.3 and 13.9 mm (and 86 mm) were not captured, so they are interpolated. Re-shoot them once the HAL keeps the zoom across a reopen.
  - The wide-end fits reach only r ≈ 0.75, so k2 is uncertain near the corners.
  - Vignetting: the ISP hardly corrects it, so the real fall-off needs a flat field: a white diffuser in front of the lens, shot at every zoom step. Until then the .lcp VignetteModel and the in-app development use v1 = −0.375, chosen by the user by eye in Lightroom (slider 150).
  - Colour: ColorMatrix1 is fitted on one series of colourful pairs under one lamp (cross-checked on a second series and the face scene).
    - **Checked in daylight (2026-11-06, `tools/gc100-colour-fit.py`, 6 overcast pairs of 2026-11-05):** developed like a raw editor (matrix, Bradford to the shot's neutral, the ISP tone curve), it is within dE76 2.7 median of the ISP's JPEG, as good as the best 3x3 fitted on those pairs (2.4) and no worse than one derived from `camToSrgb` (2.65). So it stays, and a daylight matrix adds nothing; the ISP appears to use one matrix for both lights.
    - What remains is the ISP's non-linear look: saturated colours (autumn yellows, C* > 20) differ by about dE 9 whatever the matrix. Reproducing it needs a HueSatMap/LookTable (a DCP, or ProfileHueSatMap in the DNG), not a matrix. Optional: Lightroom's own profiles add their own look anyway.
    - A tungsten series would still show whether StdA needs its own matrix (dual-illuminant).
- **The zoom is lost when the camera is fully reopened**: the lens comes out at wide while `zoom` says N. The app re-sends the zoom after startPreview, but the HAL seems to drop the unchanged value. Reported to the HAL session on 2026-11-02.
- **DNG gaps** (gccore `dng.cpp`), to fix before calling the DNGs "Lightroom-ready":
  - ColorMatrix1 is single-illuminant (see Colour above); dual-illuminant with ForwardMatrix later.
  - AsShotNeutral is still grey world, which comes out too warm. gccore's `matchIsp` now reads the ISP's white balance from the JPEG preview that the DNG embeds anyway: use it in `writeDng`.
  - No lens opcodes, by the user's choice: the DNG stays raw. The removable correction comes from the Adobe lens profile (`profiles/Samsung EK-GC100.lcp`, distortion + vignetting) plus an XMP crop.
  - No NoiseProfile.
  - Uncompressed (33 MB); lossless JPEG (DNG compression 7) would roughly halve it.
  - Not yet opened in Lightroom/ACR; run Adobe's `dng_validate` too.
- **mDNIe on GC1 has no "outdoor" tuning.** `mdnie_table_gc1.h` has no outdoor dimension, so `TUNING_TABLE()` ignores it and outdoor mode does nothing. Possible improvement: add an outdoor (sunlight) table, which is useful on a camera.
- **AK8963C compass**: `layout = 1` and `outbit = 1` are taken from Samsung's GC100 source. Verify the axis orientation on the device. `ak8963_get_position()` and `select_func_s16()` in `midas-sensor.c` are now unused.
- **`CONFIG_RTC_POWER_OFF`** (enabled by Samsung) is dropped by Kconfig because of unmet dependencies. Power-off alarm only, so low priority.
- **Earlysuspend Kconfig symbols** are still `y` with no implementation behind them. Any other GC1 driver that uses them will hit the same problem.
- **M9MO ISP safety guards** (firmware update and factory/calibration controls) are pending; see the anti-brick rules in CLAUDE.md.
- **[HIGH, hardware] The lens must never stay extended.** On 2026-10-31 the user found the lens out after a day of `adb reboot`, `poweroff -f` and Odin flashes. Unpowered it is harmless, but it is exposed to knocks and dust.
  - **Reboot with the camera open: fixed and verified 2026-11-01** (kernel 0fb751df + abbe35eb; see CLAUDE.md, "Lens safety"). A normal close retracts in ~1 s and a reboot in ~6 s.
  - Still open:
    - `poweroff` with the camera open (same path, not tested);
    - suspend with the camera open (camera standby), and low battery;
    - a kernel panic or a battery pull cannot be covered. One proper camera open and close brings the lens back in.
  - Cause (user, 2026-10-31): stock Android starts the camera app at boot, so the lens comes out, and our `adb reboot` then cut the power without closing the app. Our kernel did not cause it.
  - Stock opening the camera at boot is also the behaviour we want for our camera app as HOME.
- **vmalloc raised to 256MB** (2026-10-31). Binder maps about 1MB of vmalloc per process, and the bootloader's `vmalloc=144m` ran out at ~250 processes, so new processes died with "Binder driver could not be opened". `early_vmalloc()` now keeps the largest value and the gd1 CONFIG_CMDLINE asks for 256m. With HIGHMEM no RAM is lost.
  - Fewer processes (debloat) is still the real fix.
  - A smaller per-process binder area could be a further option.
- **bionic: "Ignore invalid pthread_t" from html6405, on our `gd1` bionic branch.** libMali's context destroy passes an invalid pthread_t to pthread_join, which aborted system_server and launcher3 (RenderThread) and restarted the framework. It is now a warning.
  - html6405's "pre-P mutex behaviour" squash (932094c) did not cherry-pick cleanly and is not applied. Only needed for processes with target SDK < 28.
- **AN30259A LED driver disabled** (2026-10-31). It panicked the kernel: samsung_battery calls `enable_led_an30259a()` on charge state changes, but the GC100 has no AN30259A on i2c-21 (the init write fails), so the client is NULL.
  - Samsung's gc1pq_00_defconfig had it enabled too, maybe harmless with their driver.
  - If the GC100's front lamp (AF assist / self-timer) turns out to be on another driver, wire it up separately.
- ~~Camera regulators stay on from boot.~~ Not the case any more (checked 2026-11-11): on both cameras, right after boot and with the camera never opened, CAM_ISP_1.2V/1.8V, CAM_SENSOR_*, MOT_3.3V and OIS_1.5V read `disabled` with no users.
- **[boot time] Crypto self-tests at boot**: dozens of "alg: ... Failed to load transform" lines, from testmgr. Measured 2026-11-11 on the GC200: they take about 0.13 s (0.535 → 0.660 s). Left on: Samsung's FIPS code reports through them, and the gain is small.
- **First boot of our kernel** (2026-10-31, in RECOVERY with the mini ramdisk): it works. Log in `device-dump/ourkernel-dmesg-1.txt`.
  - Working: panel and mDNIe, Melfas touch, AK8963 and LSM330, MAX77693 charger/MUIC with the MAX17047 fuel gauge, bcmdhd, Mali/UMP, the FIMC/M9MO sysfs nodes, and the xmm6262 modem_if with its HSIC link.
  - MemTotal is 753516 kB.

- **Pop-up flash state: done 2026-11-01** (kernel dfd9146f, gd1 688b341).
  - Verified on the device: SW 0x0f and `/sys/class/sec/sec_key/strobe_insert` are 1 with the flash up and 0 with it down. getevent labels 0x0f `SW_PEN_INSERTED`, which is its modern input.h name.
  - The ISP's STROBE_UP_DOWN byte matches. The HAL reports it as `flash-popup=up|down`.
  - With `flash-mode=on` and the flash down, a shot completes normally without firing.
  - Still open: `m9mo-strobe-charge` reads 0 in every state seen so far, so decode it with the flash up and the mode on (charging?). Also show the state in our app's UI.

  Original notes:
- **Pop-up flash state was not reported** (found 2026-11-01). Samsung's GC100 kernel reads GPIO `STR_PU_DET_18V` in `gpio_keys.c` (IRQ + delayed work) and reports `EV_SW SW_STROBE_INSERT` (0x0f, "strobe out"). The board file sets `.gpio_strobe_insert = STR_PU_DET_18V` and configures the pin. Our kernel (html6405/treammc) has the define in input.h but none of that code.
  - Port it from `~/android/ref/ek-gc100-samsung` (sparse clone of adamoutler/EK-GC100_Galaxy_Camera): `drivers/input/keyboard/gpio_keys.c` lines ~772-871, `mach-midas.c` ~1949 and ~3460.
  - Expose the state to the HAL and our camera app, e.g. as a sysfs attribute next to `sec_key_pressed` and a camera parameter. Android itself ignores `SW_STROBE_INSERT`.
  - With the camera on, the ISP also reports it (`M9MO_CAPPARM_STROBE_UP_DOWN`, plus `STROBE_CHARGE`). Firing is ISP-driven through `FLASH_MODE` (`m9mo_set_flash`).
  - Stock gpio-keys also had key 0x240 (`KEY_FAKE_PWR`, Samsung's fake power off).

## Code review of 2026-11-01: what is still open

Fixed the same day and flashed (full build over adb, 2026-11-01 21:5x):
- camera HAL gd1 2a48edf;
- sensors gd1 b607fa1;
- kernel 13c96831, 9f16697d, 72f706d6;
- ueventd smdk4412-common f3f3109;
- debuggerd system/core cf3a6bb and system/unwinding 5b4cd57;
- `flash-image`, `minirecovery-lib.sh` and `flash-adb.sh`.

Verified on the device:
- **native crash backtraces work**: a SIGSEGV'd `sleep` gave `tombstone_00` with a full symbolized backtrace;
- p4/p10 are `root root 0600`, p7 is `root radio 0640`, and `/dev/block/param` is gone;
- the sensors are listed;
- two shots in a row take ~1.1 s each (was ~2 s), the capture thread exits before the buffers are freed, the lens retracts in 1.07 s on close, and there were no crashes.
- The first boot after this flash sat on the Samsung logo for ~2 min (new runtime APEX: ART recompiles once). Expected.
- **Do not mount /data with `noload` in the mini recovery**: on the stock 3.0 kernel `umount` then spins forever in the kernel (and `sync` hangs behind it). `flash-adb.sh` is back to plain `ro`. Leaving the mini recovery took a sysrq reboot, which then booted recovery again (the reboot reason stays "recovery"); a normal `adb reboot` from there fixed it.

Still open:
- **[power] CPU hotplug: on since 2026-11-01.** Verified at idle with the screen on: only CPU0 online at 500 MHz, and cpuidle's `LOW_POWER` (AFTR) state in use.
  - **Fixed 2026-11-11: hotplug could panic either camera** with a false "Watchdog detected hard LOCKUP" from the other-cpu lockup detector (kernel 58e0e25b; details in docs/GC200.md). The GC100 hit it after 77 rounds of a pegasusq burst-load test; with the fix it ran far past that without a panic. If a "hard LOCKUP" shows up again in a `last_kmsg`, look at the detector first.
  - pegasusq only samples while CPU0 is awake (deferrable timer): on a quiet system the frequency and the online mask stay where the last burst left them until something wakes CPU0. The CPUs are in WFI meanwhile, but not in AFTR. Measure whether that costs idle power with the screen on.
- **[power] Screen-off drain, first measurement (night of 2026-11-01).**
  - Conditions: 7 h 55 min unplugged, screen off 99.8%, Wi-Fi connected (2.4 GHz), modem on without a SIM, GPS idle.
  - Awake only 2% of the time (468 suspends; 51 failed with `alarmtimer` -EBUSY).
  - Raw fuel-gauge SoC fell from 98.42% to 89.91%: about 1.1%/h. With the 1650 mAh pack (check the stock battery) that is about 18 mA average and about 4 days of standby. It starts from a full charge, so this is slightly pessimistic.
  - About one wakeup per minute. The biggest sources:
    - `l2_hsic` (514): the modem waking the AP. With no SIM it keeps searching for emergency service. Power the modem down when no SIM is present.
  - **Second measurement (2026-11-05 20:23 → 11-06 22:15, SIM in, mobile data off, Wi-Fi connected, BT on with a remote paired):** 100% → 59% in 25 h 52 min, about 1.6%/h. Awake 3% of the time; the voltage fell steadily (4.145 → 3.79 V), and the faster % drop between 85% and 74% is the gauge's curve. `l2_hsic` 2013 wakes, `NETLINK` 10097, `wlan_*` ~2400.
  - **3G stays as stock Android handles it** (user, 2026-11-06): the modem stays registered for SMS, mobile data follows the user's switch. No custom radio-off-on-Wi-Fi logic and no 2G-only default.
    - `mmc1_detect` (519): the microSD slot is rescanned on every resume.
    - `battery-monitor` (475): the sec battery driver polls every minute even while suspended; check whether its sleep interval can be longer.
    - `wlan_wake`/`wlan_wd_wake`: Wi-Fi keeping the connection up.
  - Repeat the measurement with Wi-Fi off and with airplane mode, to split the modem and Wi-Fi shares. Kernel f23cd48c enables `HOTPLUG_CPU` and makes pegasusq the default governor (html6405's 3.4 config had neither, so CPUs 1-3 never went offline). Exynos4 AFTR/LPA cpuidle needs only CPU0 online (`cpuidle-exynos4.c` `is_only_onlining_cpu`).
  - smdk4412-common sets `pegasusq/min_cpu_lock` to 2 at boot; gd1 now resets it to 0 right after. Watch for UI lag; 1 is the fallback.
  - Measure: idle `online` mask, time in AFTR (`/sys/devices/system/cpu/cpu0/cpuidle`), screen-off drain.
- **[power] Earlysuspend.** The defconfig keeps `CONFIG_EARLYSUSPEND`/`HAS_EARLYSUSPEND=y` (from the i9300) with no implementation, so s3cfb and bcmdhd compile out their normal suspend paths. Check that FIMD really suspends. Touch (`mms_ts_gc`) has no earlysuspend and scans with the screen off whenever a wakelock is held; blank it through an fb notifier.
- **Defconfig debug and panic options: decided 2026-11-12.**
  - Hung task: no panic (kernel cef55d75). The likely hung task here is a write to a slow or failing microSD, and a reboot then loses the photo and leaves the lens out; the warning and its stack reach the persisted kernel log. Android already writes `hung_task_panic 0` at boot (`llkd.rc`, `khungtask.enable=false`), so builds before cef55d75 behave the same once init is up.
  - Soft and hard lockups still panic (a real hang; `last_kmsg` keeps the cause; the false hard lockup is fixed since 58e0e25b).
  - `SCHED_DEBUG`, `SCHEDSTATS`, `TIMER_STATS`, `PM_DEBUG` and `PROFILING`/`PERF_EVENTS` stay: their cost is negligible and they help with the debug logs the user wants kept.
- **Sensors**:
  - ~~timestamps are evdev's `CLOCK_REALTIME`, but Android expects `CLOCK_BOOTTIME`~~ fixed in gd1 ac213dd (2026-11-12): each event is shifted by the current BOOTTIME − REALTIME difference, checked with `dumpsys sensorservice` and the orientation listener;
  - AKFS load/save races with the compass thread in activate/deactivate;
  - deinit frees the data while the detached thread lives.
- **Camera HAL**:
  - **the AF thread** (`exynos_camera_auto_focus_thread`, detached) calls `auto_focus_thread_stop()` on itself when the AF ends. After clearing `auto_focus_enabled`, that sends AF off and the AE/AWB unlock and then destroys `auto_focus_mutex`. A new `start_auto_focus` in that window re-initialises the mutex and gets its AF turned off, and its mutex destroyed, by the old thread. `quiesce()` checks only `auto_focus_enabled`, so a camera closed right after an AF can free the struct under the ending thread.
    - **A plain joinable thread is wrong (tried 2026-11-12, reverted; patch kept out of the tree):** the FOCUS notify is a synchronous HIDL call into cameraserver, and CameraClient holds its lock while it calls the HAL. `cancelAutoFocus` → `pthread_join` (or locking a mutex held across the notify) waited for the AF thread, while the AF thread waited in `notifyCallback` for cameraserver's lock: a deadlock, seen with `debuggerd -b` (camera stopped answering AF and the shutter). The original's 1 s give-up in `thread_stop` is what avoids it ("taking too long" showed up once in the same test, and the camera carried on).
    - **Rule for any fix:** a HAL entry point called by cameraserver must never wait without a bound for a HAL thread that can be inside a callback.
    - **Design to try:** the mutex lives with the camera (created in `exynos_camera_start`, destroyed in close); the notify happens outside the mutex; `stop()` never waits, it bumps a generation counter and clears `enabled` under the mutex and sends AF off and the unlock itself; the thread sends them only if its generation is still current; `quiesce()` waits, bounded, on a count of live AF threads after `callback_lock`, which suppresses new notifies.
    - **Test** with `input keyevent FOCUS` (press and release within ms, so AF start and cancel back to back) in a burst of 10, AF then shutter at once, AF then HOME at once, and repeated touch AF, then compare `Starting thread`/`Exiting thread` counts. The release HAL passed this: 23 of 23, a shot taken, no crash;
  - the recording paths (S5C73M3 heritage) are untouched and untested;
  - `preview_window` is cleared by every preview stop.
- **init**:
  - `gd1-bringup.sh` runs on every userdebug boot: 3 min of dumps, including the serial and MAC (root-only since 2026-11-06, they were world-readable). Keep the last_kmsg copy (it caught the lens bug); the user wants the debug logs kept (2026-11-06), so the rest stays on too;
  - EFS is mounted rw with `errors=panic`, and `restorecon_recursive /efs` rewrites xattrs at every boot.
- **Mini recovery**: rebuild it so that its built-in `flash-image` and `minirecovery-lib.sh` get the review fixes. Until then flash-adb stages the new `flash-image`, which still sources the old lib.
- **3G: data and SMS work (2026-11-04/05, Italian SIM on the WindTre network, APN `internet.wind`).**
  - cbd had never run: init failed `setexeccon(u:r:cpboot-daemon:s0)` (status 6) because no device sepolicy was built. gd1's `sepolicy/vendor` has a permissive `cpboot-daemon` domain now.
  - **SMS sending was fixed in the RIL shim** (smdk4412-common f90d50e). The Samsung RIL answers `GET_SMSC_ADDRESS` with a hex PDU field (`0791…`), Android encoded that again as a number, and the network rejected every SMS with RP cause 95. The shim now reports `+39…`. Found with `strace -x -e read,write -p <rild>` on the `/dev/umts_ipc0` fd.
  - Not done: `SET_SMSC_ADDRESS` still passes Android's number straight to the RIL, which probably wants the PDU form. Untested; nothing in our builds sets the SMSC.
  - The modem nodes (`/dev/umts_*`, `link_pm`, `modem_br`) are labelled `radio_device` since 2026-11-05 (in the next full build), see the SELinux entry below.
  - Overnight idle with the SIM (2026-11-04/05): rild stayed up, no modem reset, quiet radio log.
- **GPS: first fix in 35 s (2026-11-05)**, after gd1 ab8d1ce configured Broadcom's LTO download (`/vendor/etc/gps_debug.conf`; it had never been downloaded, so every start was cold). The 7-day file keeps fixes fast offline. See "GPS" below. Still open:
  - The daemon's data moved to `/data/vendor/gps` (`gps_vendor_data_file`, in the next full build). Still in `/data` itself: the HAL↔daemon pipes `/data/.gps.interface.pipe.to_gpsd` / `.to_jni`, hardcoded in both blobs. Under enforcing a vendor domain may not create files there (Treble neverallow); options are binary-patching the path in both blobs, or a symlink init creates.
  - The satellite list had garbage entries after the first one: the GC100 HAL's GpsSvInfo is 24 bytes (one extra Samsung field), the shim read 20. Fixed in libgps-shim (next full build); check the satellite list in GPSTest.
  - `IgnoreJniTime="true"` (stock value) still ignores Android's time; try `false` if fixes are slow.
- **Stuck Home/touch (2026-11-05): a launcher race, patched in Trebuchet (local, installed as an update).** A swipe-up asks the shell for a recents transition; on this slow device the shell started it after the launcher had already dropped that gesture's listeners, so nobody finished it. The active recents transition then kept every touch and gesture: the app looked frozen, Home and the shade did nothing (also the GPSTest "touch not responding" of 13:36). The patch finishes an orphan recents animation at once. Unblock by hand: `am force-stop com.android.launcher3`. Published as the `gd1` branch of our Trebuchet fork (2026-11-06, after a day without a recurrence) and fetched by `manifests/gd1.xml`.
- **`screencap` (and so probably user screenshots) can hang forever** (2026-11-05, reproduced; backtraces in `device-dump/logs/2026-11-05-stuck-home/`). SurfaceFlinger is idle; screencap waits in `SyncScreenCaptureListener::waitForResults()`. Cause: gralloc (`libgralloc_ump`) allocates every buffer UMP-only (its ION path is disabled, `ret = -1`), and a UMP buffer crosses processes only as a secure id, which keeps nothing alive. SF frees the capture buffer once it has sent it, so a slow client's import fails (`_ump_ukk_size_get() failed`, `gralloc_register_buffer Failed to create UMP handle`, `GraphicBuffer: unflatten ... -5`) and the result never arrives. Not related to the ION heap size. Fix options: keep the last capture buffers alive a few seconds in SF (frameworks/native), or a gralloc that passes an fd.
- **Bluetooth works (2026-11-05, tested with the user's phone)**: BCM4334 up with `bcm4334_murata.hcd`, scan, SSP pairing with code check, the bond survives a reboot, Share → Bluetooth from GC Gallery sent a 3.2 MB JPEG in ~15 s (~215 KB/s, MD5 identical). A Bluetooth remote shutter is still to test.
  - **The Bluetooth stack sometimes aborts at boot** (GC100 tombstones of 11-03, 11-08, 11-11 twice): "Done waiting for debug information after HCI timeout (WRITE_EXTENDED_INQUIRY_RESPONSE)", or "Hardware Error Event with code 0x00" from the controller. Android restarts it and Bluetooth then works, so users only see a short delay. It happened once in 21 back-to-back reboots on 2026-11-11. Suspects: the BCM4334's UART after patchram download (baud switch, `bluetooth/` config) or the controller still busy right after power-up. Look at the HCI snoop log of a failing boot.
- **DocumentsUI crashes after its process was killed** (NPE in `IconHelper` on `ConfigStore.isPrivateSpaceInDocsUIEnabled()`, 2026-11-05): an AOSP bug on activity re-creation, seen because of the low memory.

## Device tree (`device/samsung/gd1`), first-boot gaps

- **[low, security] SELinux is permissive** (`androidboot.selinux=permissive` in `BOARD_KERNEL_CMDLINE`, userdebug only).
  - **Decision (user, 2026-11-06): stay permissive, public builds included.** It is an unofficial build for enthusiasts, the app set is tiny (ours plus F-Droid), and the 3.4 kernel and the April 2025 patch level limit what enforcing would buy. The cost would be about a day of work, plus the risk of rare denials in the camera, GPS and modem paths. The README states it. Revisit only if the user asks.
  - html6405's `smdk4412-common/selinux/` exists but no makefile references it, and it is older than the policy in his shipped i9300 build (that one labels `/vendor/bin/cbd` and has `glgps`/`tweaks`).
  - gd1 now has `sepolicy/vendor` (2026-11-01) with only what init needs to start our daemons: permissive `cpboot-daemon` and `gpsd` domains, and a label for the GNSS service.
  - rild, wpa_supplicant and other services still run in the `init` domain (their binaries lack a transition), which only works because everything is permissive.
  - Proper fix: write policy for our services (RIL/cbd, GPS, camera, sensors, Wi-Fi, power HAL), collect denials in permissive mode, then switch to enforcing.
  - Modem device nodes: labelled `radio_device` in `sepolicy/vendor/file_contexts` (2026-11-05). rild already has access as `hal_telephony_server`, cbd has an allow. Before that they carried the generic `device` label and rild's ioctl 0x6f27 was denied (logged only).
  - Also check that kernel 3.0 really loads the Android 14 policy (`/sys/fs/selinux/policyvers`, dmesg at boot).
- **Root folders** are limited to `efs` (`BOARD_ROOT_EXTRA_FOLDERS`). The common tree's `misc` and `preload` have no label in UL and broke the system image.
- **VINTF**: the device manifest declares `target-level="5"`.

- ~~Sensors HAL missing, so no auto-rotate~~ **Done 2026-11-01** (gd1 d4dcd1a, `libsensors/`): LSM330DLC accel and gyro plus the AK8963C compass, rotated into the landscape frame. Auto-rotation, the level and EXIF orientation work. The compass is still unchecked against a real heading.
- **GPS** (port of 2026-11-01; fixes outdoors since, about 35 s with downloaded assistance data, see the README). This camera reads `/sys/class/sec/gps/hwrev` = `b`, so it has a **BCM4752** (`gpsd_4752` + `gps_4752.xml`). Revisions 0–a use `gpsd_47511`, the i9300's chip.
  - Same layout as the i9300: GNSS 1.0 legacy service → `gps.smdk4x12` (i9300's Apache `libgps-shim`, copied to `gd1/shims`) → the stock HAL as `gps.exynos4.vendor.so` → socket → the stock daemon, installed as `/vendor/bin/gpsd`.
  - The daemon links the Android 4.1 `SensorManager` API. `gd1/shims/libgpsd_shim` (LD_PRELOAD) stubs it with no sensors, and `configs/gps.xml` sets `SensorsEnable="false"`. It also provides `SSLv3_client_method`. Sensor-aided navigation is lost; a real bridge to the Android 14 sensor API would bring it back.
  - The vendor service had also been missing from the VINTF manifest. With it declared but failing, system_server waits for it forever and the boot hangs on the animation: keep the GNSS HAL working or out of the manifest.
  - The stock HAL has text relocations, so the GNSS service executable gets an SDK override of 22 in BoardConfig (the linker matches only executables).
  - The stock HAL passes NMEA without a terminating NUL; libgps-shim now copies it, or the HIDL wrapper aborts in `hidl_string::setToExternal`.
  - gpsd's `RILClient` (AGPS through libsecril-client) gets "connection refused" from rild; harmless without a SIM, check with one.
  - `LbsServer`/`gllto.glpals.com` (Broadcom assistance and LTO download) may no longer exist; check what the daemon does without them.
- **Audio mixer paths** (`configs/tiny_hw.xml`) are the i9300's. The WM1811 codec is the same, but the GC100 has no earpiece and has a different mic/speaker layout; check against stock.
- **Keys: done 2026-11-01** (gd1 a56d14e, `keylayout/gpio-keys.kl`; see CLAUDE.md). Codes recorded on 2026-10-31 with our kernel:
  - shutter: half press `0x210` (KEY_CAMERA_FOCUS), full press `0x220` with 0x210 still held;
  - zoom T: `0x215` (KEY_CAMERA_ZOOMIN), then `0x221` about 30 ms later (second contact or speed);
  - zoom W: `0x216` (KEY_CAMERA_ZOOMOUT), then `0x222`;
  - Power: `0x74`;
  - flash switch: SW 0x0f, registered by gpio-keys since kernel dfd9146f; the toggle itself is not tested yet.
- **cbd and libsec-ril.so** come from the i9300 LOS21 vendor (I9300 4.3) and drive the GC100's XMM6262 modem firmware (GC100XXBMC3); the modem boots with them. The GC100 stock `/sbin/cbd` (static, almost the same build) is the fallback.
- **The modem sometimes failed to come up at boot: fixed 2026-11-12 (kernel 29bd350e), watchdog kept as a safety net.** Found 2026-11-11 in the persisted logs: about one boot in seven on 11-02, 11-08 and 11-11, none in 21 test reboots that night.
  - Symptom: rild polls `modem status` 0x1 (CRASH_RESET) and then 0x3 (BOOTING) forever, never 0x4 (ONLINE). It never registers `IRadio/slot1`, so com.android.phone dies with "failed to complete startup" every 90 s and there is no 3G until the next reboot. cbd keeps calling `xmm6262_on` (1.5 % CPU, khubd busy).
  - Workaround (gd1 ec111bf): `gd1-modem-watchdog.sh`, run once at boot_completed, restarts cbd and rild if `gsm.version.baseband` is still empty a minute later (twice at most). On a running system that brings the modem back in about 20 s: CRASH_RESET, then cbd's `xmm6262_on` retries every 2 s until the boot ROM answers, then ONLINE.
  - The kernel log is in logd since gd1 8b74275 (`ro.logd.kernel=true`; logd skipped it on low-RAM devices), so failures now show their `mif:` lines in `logcat -b kernel`.
  - **First failure with the kernel side logged (2026-11-12 06:27, USB connected to the PC; logs in the git-ignored `device-dump/logs/2026-11-12-modem-fail/`):**
    - The boot ROM stage worked (`link connected = [1]`). When the modem re-enumerated after the boot loader download (`new high speed USB device number 3`), every control transfer failed (`detected XactErr len 0/8 retry 32`, `ep0in 3strikes`, then `port 2 reset error -110` every 0.6 s), and the modem went to CRASH_RESET.
    - From then on, nothing short of a reboot helped: cbd's own `xmm6262_on` retries, both watchdog restarts, and a manual stop of cbd and rild with EHCI off for 30 s. Even the boot ROM never connected again (`link connected = [0]`).
    - Lead: the 4412 USB PHY is shared by EHCI (bit 1 of `usb_phy_control.usage`), OHCI (bit 2) and the USB gadget (bit 4). While the gadget holds it, `exynos4_usb_phy20_exit` returns at "still being used" and `exynos4_usb_phy20_init` at "Already power on PHY" (`arch/arm/mach-exynos/setup-usb-phy.c`), so turning EHCI off and on never resets the HSIC host link or PHY1. With the camera on USB, cbd's recovery therefore cannot reset the link; only a reboot does. `sys.usb.config none` does not release the gadget's bit while VBUS is present, so this was not testable in software.
    - The watchdog fired as designed. Its last try did not wait before giving up; that is fixed, and it now also logs a recovery ("modem up after N restarts").
  - **Cause, confirmed the same day (logs in `device-dump/logs/2026-11-12-hsic-stress/`):**
    - At boot EHCI comes up before the gadget, so cbd's first EHCI off/on still resets the PHY. In both failed boots the gadget came up during the modem boot (06:27:46, `USB_STATE=CONFIGURED`); the next EHCI off/on, for the modem's re-enumeration after the boot loader download, then skipped the reset, and that enumeration failed.
    - Kernel 29bd350e: when EHCI starts on a PHY that is already on, `exynos4_usb_phy20_init` pulses the host link and PHY1 resets (never PHY0, the gadget's, and not while OHCI is up).
    - Test, GC100 on USB to the PC, cbd and rild restarted in a loop (each restart is an EHCI off/on with the gadget up): the unpatched kernel came back 7 times and stuck on the 8th exactly as at boot (16 XactErr, then `port 2 reset error -110` for good); the patched one came back 28 of 28 times, in 6-20 s, with 3-9 host resets each, and adb over USB never dropped. The boot where the race happened on the patched kernel (gadget up during the modem boot, one XactErr) was ONLINE 0.5 s after the reset.
    - Plain reboots and mini recovery round trips (8 boots on 44650660, then more on 29bd350e) all came up; the time spent in the mini recovery is not a factor.
    - The XactErr themselves remain (1-3 per modem boot, also on good boots): the patch makes the link recover, it does not explain why the first transfer fails. Off USB (no gadget) the full PHY reset path was always taken, so camera use away from a PC was presumably never affected.
- **The vendor blobs had the i9300's Wi-Fi firmware and NVRAM and its Bluetooth patchram** (found 2026-11-01). extract-utils looks for a blob at its destination path first, so the full i9300 `vendor/` in the blob source shadowed every `system/...:vendor/...` entry. `tools/mk-blobsrc.sh` now builds a source whose `vendor/` holds only the files we take from the i9300; re-extracted, all blobs match the GC100 dump.
- **Wi-Fi works on 2.4 GHz, not on 5 GHz** (2026-11-01).
  - With the i9300 firmware and NVRAM every association failed (`CONNECT` event status 1). With the GC100's own files, 2.4 GHz associates, completes the 4-way handshake and reaches the internet.
  - **Debugged 2026-11-04: on this unit 5 GHz receives but its transmissions are never acknowledged.**
    - The "status 1" is the firmware's generic join failure (`WLC_E_SET_SSID` status 1), not an AP rejection. With the firmware console on (`echo 20 > /sys/module/dhd/parameters/dhd_console_ms`), every 5 GHz join logs `wl0: JOIN: authentication failure, no ack`: the camera sends the 802.11 auth frame and no AP acknowledges it.
    - Scans see every 5 GHz AP with good RSSI (-54 to -68 dBm), so reception works.
    - Tried, all with the same "no ack":
      - two APs: the user's mesh on ch40 (5200) and the S25 hotspot on ch149 (5745);
      - two country codes, IT/1 and US (forced with `cmd wifi force-country-code`);
      - two calibrations, the GC100's and the i9300's `nvram_net.txt` (its `swctrlmap_5g` differs);
      - two firmwares, the GC100's 6.10.58.813 and the i9300's 6.10.58.114 (bind-mounted over `/vendor/etc/wifi/bcmdhd_sta.bin_b2`).
    - Ruled out:
      - the module variant: `.cid.info` says "0", because the MAC prefix a0:21:95 is not in macloader's table, so the default file loads, but the Murata and default files are identical;
      - a missing `aa5g` (the i9300 lacks it too);
      - the chanspec format (2.4 GHz works);
      - a board GPIO for the 5 GHz PA (there is none: the FEM is driven by the module).
    - The EK-GC100 is FCC-certified for 5 GHz (FCC ID A3LEKGC100: UNII and DFS reports), so the design transmits there.
    - **Driver check, same day.** Our bcmdhd is 1.141.44.4; Samsung's GC100 driver is 1.28.11.12 (adamoutler/EK-GC100_Galaxy_Camera, `drivers/net/wireless/bcmdhd`).
      - Found and fixed a real bug (kernel bff38bf5): the roam cache built 11ac chanspecs for the ioctl version 1 firmware, and the join sends them unconverted (5 GHz ch40 went out as 0xd028). It is now legacy; `init_roam: ioctl version 1, legacy chanspecs` confirms it. **5 GHz still fails the same way after it.**
      - The same in both drivers:
        - the join command (extended `join` iovar, scan params, roam-cache channel list);
        - the country table (IT/1);
        - no TX power set at init.
      - The 11ac-era init iovars of ours (txbf, vht_features, lpc, ack_ratio...) are for 4335/4339/4354 and not built for the 4334 (checked in `.dhd_linux.o.cmd`).
      - Firmware state during a 5 GHz join, read with the kernel's `gd1_wl_debug` diagnostics (kernel e4988ab6, `echo 1 > /sys/module/dhd/parameters/gd1_wl_debug`):
        - the join chanspec is legacy and right (0x1b95 = ch149, 20 MHz);
        - `per_chan_info` for ch40 and ch149 is 0x7 (valid HW/SW, 5 GHz; not passive, radar or restricted);
        - qtxpower is 127 (no limit), with one tx/rx chain.
      - Forcing the antenna before the join (`gd1_wl_ant` 0, 1 or 3) changes nothing.
    - ~~Most likely a hardware fault of this unit's 5 GHz transmit path.~~ **Not a fault of this unit (2026-11-11): the GC200 fails exactly the same way** (`JOIN: authentication failure, no ack` on 5200 MHz with RSSI -47 dBm, its own firmware 6.10.58.115 B2 and its own calibration), so it is something in our software. Ruled out on the GC200 the same day:
      - the driver: **Samsung's own GC200 bcmdhd** (from its 3.4.39 kernel, built into ours on a test branch with wakelock stubs) fails the same way;
      - the board WLAN code (`board-midas-wlan.c` is identical to Samsung's apart from a TAB3 IRQ flag) and the country tables (IT → IT/1 in ours and Samsung's);
      - the nvram: `aa5g=1` added, the factory `nvram_mfg.txt` differs only in `ccode`; the default vs Murata file choice (`.cid.info` is "0") cannot explain the GC100, whose two files are identical;
      - Bluetooth on (BT_REG_ON high) or off;
      - the AP: two APs on the GC100, a strong signal on the GC200.
    - **Decisive test, same evening: the whole stock Wi-Fi stack fails the same way.** In the GC200 mini recovery with the **stock 3.0.31 kernel** (`device-dump/gc200-tests/rec-stockk`), Samsung's stock `dhd.ko` 1.28.19.28 from the stock ramdisk, the stock firmware and nvram, and the stock `wpa_supplicant` 2.1 with its libraries (`device-dump/gc200-tests/stockwifi`, driven by a small static control client because the dump has no `wpa_cli`; Samsung's supplicant only scans after `USE_NETWORK ON`): 2.4 GHz reaches COMPLETED at once, 5200 MHz gets `ASSOC-REJECT status_code=1` over and over. **So it is not our software**: what is left is the 5 GHz radio of these cameras (both units) or the radio environment. Stock Android adds nothing to the Wi-Fi path beyond what this test ran.
    - **Fallback to 2.4 GHz (user decision 2026-11-11, after the stock test): done** in gd1's `rro_overlays/WifiOverlayGalaxyCamera` (gd1 a38fd1d, 346fb13). A rejecting BSSID is blocked at the first rejection, the network is disabled only after 20 rejections, and the pause after 5 consecutive failures (hardcoded in ClientModeImpl) is 15 s instead of 5 min. The GC200 connects on 2462 MHz after one 5200 MHz rejection, the GC100 within seconds of a Wi-Fi restart. 5 GHz stays enabled, so another router or a fixed radio would still use it.
  - Side effects:
    - ~~after two failures Android temporarily disables the whole network~~ solved by the fallback overlay above;
    - a Wi-Fi hotspot on 5 GHz fails too ("Failed to set country code" in SoftApManager, a separate HAL issue). A 2.4 GHz hotspot works, and since gd1 c0ea980 (`config_wifiSoftap5ghzSupported` false) the hotspot settings offer 2.4 GHz only.
  - **Country code by location (user request 2026-11-11): done** with Android's own `config_wifiUpdateCountryCodeFromScanResultGeneric` in the same overlay: without a telephony country, the country comes from the 802.11d element of nearby access points (several, all agreeing). The GC200 reads IT at home; the GC100 keeps its SIM's. Still open: the HAL's set-country path used by SoftAp fails ("Failed to set country code"), so a 5 GHz hotspot cannot start; the supplicant path (`COUNTRY` → `wldev_set_country`) works.
- **Recovery is our own mini recovery, not LineageOS recovery.** `TARGET_NO_RECOVERY := true` because the 8MB partition is too small (LOS21 recovery is 15.7MB). The mini recovery (`minirecovery/`, docs/RECOVERY.md) is in RECOVERY on both cameras and in daily use since 2026-10-31 (backups, wipes, whole-build flashes over adb).
  - **No UI**: adb only, plus status screens. A key-driven menu (zoom lever and shutter) could be added later by reading `/dev/input` from the script.
  - **No zip install**: updates go through Odin tars (boot, system, and vendor as `hidden.img`) or through `flash-image`/`adb sync`. Public users will need Odin.
  - **Optional: measure a slimmed LOS recovery.** Use a recovery-only kernel config with no camera, Mali, MFC, Wi-Fi, BT, audio or modem, and a ramdisk without fastbootd. Keep it only if it fits in 8MB.
  - **Repartition (PIT) only as a last resort, and with an explicit exception to anti-brick rule 1.** RECOVERY (p6) sits before RADIO, CACHE and SYSTEM, so growing it moves all of them. The firmware package has no PIT, so the stock PIT would first have to be read from download mode (e.g. `heimdall download-pit`).
  - The busybox binary is fetched by hand from Alpine. Script that fetch (with the docker `apk fetch` command from docs/RECOVERY.md) before publishing.
- ~~SurfaceFlinger aborts with "EGLContext creation failed"~~ **Fixed on 2026-10-31** by copying the Mali GLES modules into /system/lib/egl (see "First full boot" below). History (logs in `device-dump/bootlog-2/`):
  - EGL initializes (Mali r3p2, kernel and user API 29), but the EGL extension string has only the loader's own extensions; Mali's KHR_image/fence/native_buffer are missing.
  - The Mali, UMP and gralloc blobs are bit-identical to the working i9300 LOS21 build, and frameworks/native matches html6405's r08 in the EGL loader and RenderEngine. Suspect the kernel side (gd1 defconfig/board: Mali/UMP/ION/fb) or device config.
  - `gd1-bringup.sh` saves the live dmesg and device permissions to `/data/bringup/` for the next attempt.
- **adb "offline" under LineageOS: FIXED 2026-11-01.** The camera now shows as `device`, with a root shell. The fixed adbd APEX was swapped into `/system/apex` by hand from the console (the original is in `/data/local/tmp/com.android.adbd.apex.orig`), and the next full build includes it.
  - adbd uses the legacy FunctionFS path (nonblocking_ffs=0, aio_compat=1). The 3.4 f_fs rejects the v2 descriptors (EINVAL) and accepts the v1 fallback.
  - The kernel side works: `gd1_ffstest` (bringup-tools) binds `/dev/usb-ffs/adb` with v1 descriptors and receives the host's CNXN.
  - Bug in adbd (`packages/modules/adb` transport.cpp, `fdevent_register_transport`): `#if ADB_HOST` wrapped only half of the "start detached" condition. On the device it reduced to `t->type == kTransportUsb`. Only the legacy path sets that type, so its transport was registered, left in kCsDetached and never started (no read/write threads).
  - Fix: `packages/modules/adb` branch `gd1` f31cfce, which applies the detached setting only on the host. Worth sending upstream.
  - The adbd trace with `persist.adb.trace_mask` goes to `/data/adb/adb-*`, not to logcat.
- **First full boot: 2026-10-31.** It reaches the LineageOS setup wizard. The fixes were:
  - the Mali GLES modules copied into /system/lib/egl;
  - IAdaptiveBacklight dropped from the manifest (the s6d6aa1 has no CABC);
  - debugging over the USB ACM root console (`tools/serialsh.ps1`, `tools/serialpush.ps1`).
- **Landscape and touch done** (2026-10-31): `ro.surface_flinger.primary_display_orientation=ORIENTATION_90` plus `sec_touchscreen.idc` with `touch.orientation = ORIENTATION_90`, both tested on the device.
- **No lock screen** (user, 2026-10-31): `config_disableLockscreenByDefault`, with the lock entries hidden in Settings.
- **Lessons from the debloat (2026-10-31)**:
  - never `pm disable` `com.android.sdksandbox`: PackageManagerService throws "There should exactly one sdk sandbox package" and system_server crash-loops. To recover with the framework stopped, fix both `package-restrictions.xml` and `-backup.xml`/`.reservecopy` via abx2xml/xml2abx;
  - a vendor init service cannot run `settings`/`pm` ("inaccessible or not found"), so framework-side scripts belong in system_ext;
  - SystemUI writes its in-memory tile list straight back over any outside change to `sysui_qs_tiles`, so the setup script sets it while SystemUI is restarting.
- "Discover Trust" onboarding notification: off via `settings put --lineage system trust_interface_hinted 1` in gd1-setup.sh (next build).
- **[UX] Display orientation and density.** The panel is 720x1280 portrait, and stock runs the display rotated (SurfaceFlinger orientation=1, landscape 1280x720) at density 320.
  - Under LineageOS the UI comes up in portrait at 320 dpi (360x640dp). The setup wizard pages are cramped: Next is only reachable by scrolling (the display size is correct).
  - Decide on fixed landscape (stock-like), probably with a lower density (240–260) for a camera UI. Also consider dropping the setup wizard from the product.
- **Setup wizard** (user, 2026-10-31): skip most of LineageOS's onboarding.
  - Option 1, recommended: drop LineageSetupWizard so AOSP Provision marks the device set up, with Italian locale and timezone defaults in the build.
  - Option 2: keep it but skip the SIM, Lineage services, fingerprint/lock, theme and navigation pages.
- **[UX] UI lag** (user, 2026-10-31): the quick settings tile label marquee stutters on the Mali-400. Ideas:
  - no marquee on QS tile labels;
  - default animation scales 0.5x via the SettingsProvider defaults overlay;
  - review the CPU governor/hotplug and Mali DVFS together with the power work.
- **[UX] Vibration far too strong** (user, 2026-10-31). Lower the motor intensity (vibrator HAL amplitude or the kernel's max duty), or default haptic feedback off.
  - Found 2026-11-12: the vibrator HAL is the legacy `vibrator@1.0` with no amplitude control (`dumpsys vibrator_manager`: no capabilities), so Android's intensity settings cannot scale it. The only knob is `/sys/class/timed_output/vibrator/pwm_value` (0-100, mapped onto 50-100 % PWM duty by `max77693_haptic.c`; 50 at boot, writable by `system`). A lower default is one `write` in gd1's `init.target.rc` once the user picks a value by feel (24 was set live on the GC100 for that).
- **/vendor is mounted read-write** (found 2026-11-01): the HIDDEN line in `rootdir/fstab.smdk4x12` has no `ro`. Add `ro` for the next build, after checking that nothing in smdk4412-common/i9300 writes to /vendor at runtime and why html6405's fstab leaves it rw.
- **Hand-swapped files on the device**: all included in the build flashed on 2026-11-01 via `tools/flash-adb.sh`, so this list is history. Since then only `camera.smdk4x12.so` (gd1 10639a6) has been pushed by hand. The old originals in `/data/local/tmp/*.orig` can go:
  - `/system/apex/com.android.adbd.apex`, the adb fix;
  - `/vendor/overlay/framework-res__lineage_gd1__auto_generated_rro_vendor.apk`, no doze;
  - `/vendor/lib/hw/sensors.smdk4x12.so` (new) and `/vendor/etc/vintf/manifest.xml` (with the sensors HAL);
  - `/vendor/usr/keylayout/gpio-keys.kl` (new) and `/vendor/overlay/org.lineageos.platform-res__lineage_gd1__auto_generated_rro_vendor.apk` (camera key, no LED/backlight/burn-in).
- **Camera HAL status (2026-11-01, gd1 `camera/`)**: preview, optical zoom (pinch), AF and auto exposure work in OpenCamera. The ISP powers up only when the preview starts, so the lens no longer cycles at boot. Still missing:
  - **JPEG still capture works** (2026-11-01, first 16MP picture from OpenCamera). Still open:
    - ~2 s from shutter to JPEG. Measure each step (preview stop, exposure, ISP compression, transfer, preview restart), then try keeping the buffers or the capture mode;
    - the old S5C73M3 picture thread no longer starts for the M9MO (gd1 d794b97);
    - no EXIF from the HAL: the ISP JPEG is passed on as is. Check what the ISP puts in it; the HAL could add EXIF from the M9MO EXIF controls (ISO, shutter, aperture, focal length, flash);
    - the flash fires and exposes correctly (user, 2026-11-01, flash "on" in OpenCamera);
    - **flash closed** (user decision, 2026-11-01): the app handles it. The HAL only exposes the pop-up state, read from the ISP (see "Pop-up flash state" above), and does not change the flash mode on its own. Checked: with the flash down and the mode "on", the shot completes without firing;
  - **RAW works** (2026-11-01, gd1 d794b97 + 10639a6), behind `setprop persist.vendor.camera.raw 1`:
    - each shot gives the raw frame and then the 16MP JPEG, in about 3 s, and the preview resumes. The raw frame goes to `CAMERA_MSG_RAW_IMAGE` and to `/data/vendor/camera/IMG_<time>.raw`. Test files are in `device-dump/raw-tests/`;
    - **format** (`tools/m9mo-raw.py`): 20440000 bytes, a continuous big-endian 10-bit stream (not MIPI RAW10), 4736 px = 5920 bytes per row, 3452 rows plus a 4160-byte tail. The last 64 columns are zero, and the left columns read ~50 (optical black?). Bayer **GRBG**, checked against the JPEG colours;
    - **the frame is the whole lens image circle**: strong barrel distortion and vignetting, and a much wider view than the JPEG. The ISP corrects and crops for its JPEG. A DNG needs `WarpRectilinear` + `FixVignetteRadial` opcodes (per zoom position) or a lensfun profile, plus the crop (`DefaultCrop`);
    - FIMC stops after every frame in JPEG mode (lastend), so the HAL restarts it between the raw frame and the JPEG. STREAMON is safe there: the M9MO is already in still capture;
    - **black level 64** (dark frame 2026-11-01, `device-dump/raw-tests/dark-*`): 63.2-63.6 on all four channels, flat across the frame, dark noise σ ≈ 2.8, 1231 hot pixels > 200 at the long auto exposure. Columns 0-1 and 4666-4735 are zero (active area x 2..4665, HAL fixed 2026-11-04);
    - the HAL reports the layout as parameters (gd1 7cdc1a4): `raw-size`, `raw-format`, `raw-cfa`, `raw-active-area`, `raw-black-level`, `raw-white-level`. Black and white can be overridden with `persist.vendor.camera.raw.black` / `.white` (user, 2026-11-01: keep them tweakable);
    - the ISP JPEG has no exposure EXIF (no exposure time, ISO, F-number). The HAL now reads them from the ISP after each shot (gd1 e2f4472) and reports them as `exif-iso`, `exif-tv` (APEX x100), `exif-av` (APEX x100), `exif-bv`, `exif-ebv`, `exif-flash`, `exif-fl` (mm x1000) and `exif-fl-35mm` (mm x100). For a RAW shot they also go to `<raw>.txt`. Still to do: write them into the JPEG (our app, or the HAL);
    - the ISP's EXIF flash read 9 ("fired, compulsory") with the pop-up flash down and the mode "on". It seems to follow the mode, not the actual firing; check with the flash up;
    - **DNG: `tools/m9mo-dng.py`** turns `.raw` + `.raw.txt` into an uncompressed 16-bit DNG with exposure EXIF. exiftool validates it and LibRaw (rawpy) develops it with correct colours. Placeholders: ColorMatrix1 treats the sensor as linear sRGB, and AsShotNeutral is a gray-world estimate. Next: a real colour profile (ColorChecker + dcamprof), the ISP's own white balance if it can be read, lens correction opcodes, and the same writer in our app;
    - still to do: the black level per ISO (and whether the ISP already subtracts or clamps anything), the white level (1023 assumed; check a clipped highlight), what the 4160-byte tail holds, whether the data is before NR/sharpening, the raw at other zoom positions, and RAW at other picture sizes (the HAL forces 4608x3456 now);
    - then the DNG writer (in our app, or in the HAL as a vendor parameter) with the EXIF exposure values from the ISP;
    - writing to `/data/vendor/camera` is a bring-up shortcut: the app should get the frame from `CAMERA_MSG_RAW_IMAGE`. OpenCamera ignores it;
  - the zoom lever: OpenCamera steps one zoom level per key press. Our app should drive `V4L2_CID_CAMERA_OPTICAL_ZOOM_CTRL` (continuous TELE/WIDE/STOP, slow/fast) instead;
  - **PASM works** (2026-11-01, gd1 ead43a0). The M9MO runs the exposure program and the HAL passes the mode and values:
    - parameters: `pasm-mode` (auto/program/aperture/shutter/manual), `shutter-speed` (the ISP's 30 steps, 16 s to 1/2000), `aperture` (f x10), `iso` (now up to ISO3200; manual forces ISO100 if auto);
    - verified via `exif-*`: A f/5.6 → AV 473; S 1/250 → TV 797; M 1/30 f/2.8 ISO200 → exactly that;
    - OpenCamera does not know these parameters. For tests, `setprop vendor.camera.test.{pasm,shutter,aperture,iso}` overrides them on every setParameters. Bring-up only: drop it once our app exists;
    - still open: the aperture limits per zoom position (the ISP clamps; read them back and publish them in `aperture-values`), the real values chosen by the ISP during the preview (for the viewfinder), exposure compensation in P/A/S, and auto ISO in S mode (it stayed at 400 in a dim room).
  - touch AF, OIS, the flash state in the UI;
  - **preview latency** (user, 2026-11-01: noticeable lag). Fewer capture buffers (`EXYNOS_CAMERA_CAPTURE_BUFFERS_COUNT` is 6, up to ~200 ms at 30 fps; try 3-4), and a fixed 30 fps preview through `V4L2_CID_CAMERA_FRAME_RATE` (OpenCamera asks 15-30 fps, and in low light the ISP drops to 15). Measure before and after.
- **Kernel oops on a fast camera close/reopen** (2026-11-01, old kernel without m9mo_shutdown). Likely related: every close then took 5 s, because the lens-off waited for a masked interrupt; kernel abbe35eb brings that to ~1 s. Re-test a fast close/reopen.
  Original notes: `klist_put` NULL dereference in the FIMC/i2c release path (`i2c_unregister_device` → `klist_del`). It happened a second after the first close, while the camera provider restarted and opened the camera again. The close before it logged `m9mo_set_lens_off: M9MO_INT_LENS_INIT isn't issued, 0x0`, yet the lens did retract. Log: `/data/bringup/last_kmsg/last_kmsg-20261101-163635.txt` on the device (copy it to device-dump). The HAL's lazy power-up avoids that sequence, but the FIMC open/release path needs a race review (two opens of FIMC0 at once?).
- ~~No native crash backtraces on this kernel~~ (found 2026-11-01, **fixed the same day**, see below).
  - tombstoned fails with "failed to create temporary tombstone in /data/tombstones: Is a directory". That is the EISDIR a kernel without `O_TMPFILE` returns, and 3.4 has none.
  - crash_dump also fails to read thread registers ("failed to fetch registers ... I/O error"), probably no `PTRACE_GETREGSET` on 3.4 ARM.
  - **Fixed and verified 2026-11-01**: system/core cf3a6bb (tombstoned, named temp file) and system/unwinding 5b4cd57 (PTRACE_GETREGS fallback). Root cause of the second: `PTRACE_GETREGSET` is under `CONFIG_HAVE_ARCH_TRACEHOOK` in kernel/ptrace.c, and 3.4 ARM does not select it.
  - Test: `kill -SEGV` a throwaway native process, e.g. `sleep 100 & kill -SEGV $!`, then check `/data/tombstones/tombstone_00` for a backtrace.
  - Leftover `.temp_*` files can stay behind only if tombstoned itself dies mid-dump.
- **Intermittent camera provider crash during a capture** (2026-11-01, once in about 15 shots): SIGSEGV at address 0 in a HwBinder thread of the provider, 20 ms after take_picture, while the picture thread stopped the preview. The thread logged no HAL entry point, so it is probably in the HIDL camera device wrapper (a callback racing with the preview stop). It crashed mediaserver too. Needs a backtrace (see above).
- **Clock**: it had drifted ~5.5 min. `auto_time` is on but there is no network, so no NTP. Set from the PC with `adb shell cmd alarm set-time <ms>` (it reaches the RTC; it survived a reboot). With Wi-Fi it should sync on its own. 24-hour format is now the default (gd1-setup.sh, gd1 6864d0c).
- **Restarting the camera provider leaves the camera unusable until a reboot** (seen 2026-11-01).
  - Killing the provider also restarts CameraService, which runs inside mediaserver (uid media, 1013) in this HAL1 setup.
  - The new CameraService has an empty "allowed device users" list and rejects every client ("cannot connect from device user 0").
  - It asks system_server for the user list, but CameraServiceProxy drops the call: "Calling UID: 1013 doesn't match expected camera service UID".
  - Fix it in html6405's `frameworks/base` HAL1 fork (accept the media UID in CameraServiceProxy), or run CameraService as cameraserver. Until then, push a new HAL and reboot.
- **The modem stays on all the time.** cbd and the GNSS service no longer restart (fixed 2026-11-01), but nothing turns the XMM6262 off when mobile data is unused. Airplane mode should power it down; check what it really does to the modem and its HSIC link.
- **Lens left out after a crash**: the shutdown hook cannot help after a kernel panic or a battery pull. Any camera open and close re-homes the lens. Consider a quick open/close at boot, done by the HAL or our app, if the ISP reports the lens out.
- The device overlays are built as RROs on /vendor (`PRODUCT_ENFORCE_RRO_TARGETS := *` in smdk4412-common), not into framework-res. To test an overlay change, build `<pkg>__lineage_gd1__auto_generated_rro_vendor` and push that.
- **Sensors HAL** (gd1 `libsensors/`, 2026-11-01): working, and auto-rotation verified. Still open:
  - check the compass against a real heading (a compass app, or our camera app), and the gyro sign while turning;
  - the `sensors.smdk4x12.so` on the device lacks the last tweak (continuous reporting mode, compass range 4911.9); the next build has it;
  - AKFS stores its offsets in `/data/misc/akfs.txt` from system_server; fine while permissive, but needs a sepolicy rule (or a vendor path) for enforcing;
  - the 1.0 passthrough HAL loads into system_server. Consider the HIDL sensors service process later (SELinux, crash isolation).
- **Sensor feature flags**: smdk4412-common copies `android.hardware.sensor.light.xml` and `.proximity.xml`, but the GC100 has neither (only the LSM330 accelerometer and gyroscope and the AK8963C compass). Drop them for gd1 so apps do not expect those sensors.
- **USB ACM console: opt-in from the next build** (user, 2026-11-01). It is off by default; `setprop persist.vendor.gd1.console 1` plus a reboot turns it on (userdebug only). `tools/serialsh.ps1` and `serialpush.ps1` still work once it is on.
- **Restarting adbd breaks the ACM console** until reboot (Windows: "device not functioning"). For adb debugging use the persistent `persist.adb.trace_mask` and reboot instead.
- **Quick settings tiles** set to internet, bt, airplane, rotation, location, battery, dark, caffeine (SystemUI overlay). The script resets `sysui_qs_tiles` once.
- **Settings cleanup** (user, 2026-10-31): after the debloated build, list the remaining entries and hide the irrelevant ones with Settings `config_show_*` overlays (phone, SIM, emergency, ...).
- **Backlight stays at 0 until the framework sets it.** That is expected, but check that the lights HAL drives `/sys/class/backlight/panel` (max 255) once booted.
- **Camera HAL is disabled on gd1** (`-DEXYNOS_CAMERA_DISABLED`: zero cameras, `open()` returns -ENODEV).
  - On the first LineageOS boot (2026-10-31), cameraserver opened camera 0 at boot with the i9300's S5C73M3 preset, and the device rebooted right after. Log in `device-dump/bootlog-1/`.
  - Re-enable only with a real M9MO preset (plan step 6).
  - Also find out why the wrong commands crashed the kernel instead of failing; the m9mo/fimc error paths must not take the system down.
- **smdk4412-common device lists**: `gd1` was added to the top-level Android.mk and camera/Android.mk filters. Prebuilt apps (AuroraStore, Via, OpenCamera) are still pulled in by common.mk; strip them per the app decisions in CLAUDE.md.

## Camera HAL stability

- **A failed lens-off left the lens out** (2026-11-02): after a HAL hang the ISP ignored lens-off (LENS 0x28 stayed 0xC4) and was powered down with the lens extended. Recovered then by opening and closing the camera normally.
  - **Fixed and verified 2026-11-04** (kernel 14e17036 + e5a7cf0d). `m9mo_lens_off_or_reset()`, called by `m9mo_remove` and `m9mo_shutdown`, handles a failed lens-off this way:
    1. power-cycles the ISP through the new `m9mo_platform_data.power` callback (`m9mo_power`);
    2. boots it with `m9mo_init`;
    3. **enters monitor mode** and lets the lens settle 1 s;
    4. retries lens-off.
  - Without step 3 the retry reported success with the lens half out: the fresh ISP does not know where the lens is until monitor mode homes it.
  - Test hook: `echo 1 > /sys/module/m9mo/parameters/test_lens_off_fail` (one-shot). With the user watching, the lens retracted fully; the close took ~4 s.
  - Before release: keep the hook or drop it (it is harmless unless written). Still unknown: what 0xC4 means.

- **The capture path relies on a stray ISP frame** (found 2026-11-03).
  - What happens: with one buffer, FIMC skips the first frame interrupt after STREAMON (`fimc_irq_cap[0] SKIPPED`, then it stops). Every frame read (RAW frame 0, the JPEG, bracket frames) works only because the M9MO emits a short stray frame right when the transfer starts, and FIMC keeps the frame after it.
  - When the stray frame is missing, the real frame is skipped and the HAL gets "No frame N".
  - Seen once: Samsung's TIMER_MODE (NEW 0x07) with the OIS off, then a RAW shot. The HAL no longer sends TIMER_MODE (gd1 camera), and that fixed it.
  - **Retry added 2026-11-04** (gd1 60c4f85): after a poll timeout the HAL restarts FIMC and transfers the same frame once more. This covers the RAW frame 0, single JPEGs and bracket frames, but not a RAW shot's JPEG, because the kernel is back in monitor mode by then.
  - **Not exercised yet**: `setprop vendor.camera.test.timer_mode 1` (Samsung's TIMER_MODE, OIS off, RAW) no longer loses the frame, so the retry never ran. The regression tests pass.
  - If the failure shows up again, check the log for "restarting FIMC and transferring it again". The alternative is FIMC with two buffers.
- **Parameters set during or right after a capture are re-applied on a fixed frame count** (workaround, 2026-11-04, gd1 aef6972).
  - Just out of still capture, and just after the preview STREAMON, the ISP takes AE program writes (pasm-mode, shutter, aperture, ISO) but keeps its old program.
  - The HAL applies those parameters at once. Then, on the 15th preview frame after the restart (`M9MO_PARAMS_REAPPLY_FRAMES`, override with `setprop vendor.camera.test.reapply_frames N`), it invalidates its cached pasm-mode, shutter, aperture and ISO and applies again, so that only those four are sent.
  - History: a forced apply at the STREAMON, then a forced apply on the 5th frame, both left the ISP in P. So the cause was what a forced apply re-sends, not the timing.
  - **Works since gd1 e4b0205** (2026-11-04): P→M during an AE bracket, sent straight to the HAL with no app hold, gave a real 16" f/8 ISO 100 shot with both 15 and 60 frames. Before release, drop the `vendor.camera.test.reapply_frames` override (see "Before release").
  - Proper fix: find the ISP state or interrupt that says the AE program is writable again, and wait for that instead of counting frames.
- **Hang on a shot (2026-11-02, OpenCamera), fixed in gd1 camera.** The preview thread sent `CAMERA_MSG_PREVIEW_METADATA` on every frame. CameraService enables that message for every client and its callback spins for the client lock, which `stopPreview()` held while joining the preview thread. It now goes out only during face detection (none on the M9MO). Rule for later HAL work: no thread joins inside a framework call while that thread can be in an enabled callback (docs/CAMERA-APP.md §3.3).
- The AF thread is still not joinable (see the review list); if it becomes joinable, apply the same rule to `CAMERA_MSG_FOCUS`/`FOCUS_MOVE`, which CameraService also enables for every client.

## M9MO features not wired yet (audit of 2026-11-03)

Compared with Samsung's GC100 driver (`~/android/ref/ek-gc100-samsung`) and the stock HAL/app. Fixed in the same audit: the OIS (the HAL had sent ANTI_SHAKE, an AE program, so the optical stabilizer never ran), the self-timer LED and the AF lamp setting (docs/CAMERA-APP.md §3.2).
- **Missing from our kernel** (it comes from the GC110 tree):
  - `m9mo_set_OIS_cap_mode`: ported 2026-11-03 for stills (0x04 single, 0x05 multi-frame). Still missing: the recording modes (0x01/0x02 at ≤30/60 fps) and panorama (0x03), which need recording state in the driver.
  - `m9mo_set_iqgrp`: MON 0x59, the ISP's IQ tuning group per recording size and fps (0x64 1080p, 0x66 720p, 0x69 VGA, 0x65/0x68 at 60 fps). Needed for good video.
  - `m9mo_set_smart_auto_default_value`, `post_init` (split OIS init), `CAPTURE_END`, `FAST_CAPTURE`.
- **Wired 2026-11-03** (docs/CAMERA-APP.md §3.2): sharpness, contrast, saturation, flash EV, WDR, LSC, LDC, white balance in Kelvin and TIME_INFO (hour and minute only).
  - Verified 2026-11-04: WB Kelvin, sharpness, contrast, saturation and WDR work (numbers under "Camera app").
  - LSC and LDC work since kernel 3952a933, which writes them in parameter mode. The ISP defaults are both on.
  - flash-ev was dropped (2026-11-04). The value lands (kernel readback), but the flash exposure did not change from -2 to +2. Samsung's app never sets it.
- **Still never sent by the HAL**: CAF, SMART_SCENE_DETECT, SMART_ZOOM, and the WB custom R/G and B/G gains.
- The OIS movie mode while recording (IS_OIS_MODE movie, with its interrupt).

## Image quality and RAW (ideas, user request 2026-10-31)

The stock M9MO JPEGs look poor: heavy sharpening halos, smeary noise reduction, simple demosaicing.

- **Quick win**: lower the ISP sharpening (M9MO sharpness/contrast CIDs) and use maximum JPEG quality.
- **RAW first**: characterise `RUNNING_MODE_RAW` output: bit depth, Bayer layout, black/white level, and whether it is pre-NR sensor data. Then write proper DNGs.
- **Colour profile without a ColorChecker** (user, 2026-11-01: a ColorChecker costs more than the camera). Plan:
  1. fit the matrix against the ISP's own JPEG of the same shot (Samsung's colour tuning). Use many colourful scenes, in daylight and under tungsten, for a dual-illuminant DNG profile. The JPEG is distortion-corrected and cropped, so register it to the raw first; that registration also gives the lens correction;
  2. as a starting point, the Adobe matrix of another camera with the same sensor, once the GC100 sensor is identified (16MP 1/2.3");
  3. a cheap printed 24-patch card only to check the result.
  - Avoid a chart shown on a screen: display primaries are narrow-band and mislead the sensor.
- **Offline development**:
  - darktable / RawTherapee CLI with a GC100 noise profile (per ISO) and capture sharpening (deconvolution);
  - the colour profile from the plan above (dcamprof can fit it from the matched patches);
  - a lensfun profile (distortion, vignetting, TCA) per zoom position.
- **Burst merge** (HDR+ style align and merge, optionally multi-frame super-resolution) for noise. **A RAW burst from the ISP's bracket does not work** (experiment 2026-11-02, kept on the `raw-burst-experiment` branches of the kernel and gd1):
  - Setup: the HDR bracket (3 frames at 7.5 fps) at EV 0 with CAPPARM 0x78 set, and each frame read with YUVOUT_MAIN 0x05.
  - Format written before the frame select: every frame came out as the ISP's stored 16MP JPEG, in a 10 MiB transfer, while CAPCTRL_IMG_SIZE still claimed 20440000.
  - Format written after the select: the transfer never started (timeout). The HAL's ISP reset recovered both times.
  - Reading the middle frame again as a JPEG after the raw reads also timed out.
  - Conclusion: the ISP keeps bracket frames only as developed JPEGs (Samsung's driver says so too: "convert raw to jpeg ... and store memory on ISP").
  - Measured on the way: 3 exposures at 1/30 s end about 0.6 s after the capture start; each stored frame then takes about 0.12 s to select and 0.5 s to transfer.
  - Left to try: back-to-back single RAW shots (about 3 s each, with the preview restart between them), or `RUNNING_MODE_CONTINUOUS`/`_BURST` (not explored; probably JPEG too).
- **Fast HDR** (user, 2026-11-02: phone-like speed). The ISP's `RUNNING_MODE_HDR` exposes 3 bracketed frames at 7.5 fps (about 0.4 s) and keeps them; the merge (exposure fusion) runs on the device in the background. HAL and app plan: docs/CAMERA-APP.md §6.8.
- **ML denoise** on the user's homelab, not on the device. The camera app uploads DNGs over Wi-Fi and gets the developed JPEG back.
- **On-device development: dropped** (2026-11-04, docs/RAW-PIPELINE.md). The ISP's JPEG is already at this sensor's limit for a single frame; ours reached parity, not better.

## RAM plan (user priority, 2026-10-31)

**Baseline**: 1GB physical, 755MB visible to the kernel (stock MemTotal 773380 kB).
- The kernel reserves about 256MB: ION contig 140MB, MFC secure 36MB + normal 4MB, FIMC0 31MB, FIMC1 32MB, FIMD 12MB, SRP 1MB.
- Already in place from html6405: Go profile (`go_defaults`, `ro.config.low_ram=true`), 512 dalvik heap, lmkd props, in-kernel lowmemorykiller, zram with LZ4 (384MB). No memcg/PSI/vmpressure on 3.0.

**Measure at first boot**: `/proc/meminfo`, `dumpsys meminfo`, `/sys/kernel/debug/ion/*` and lmk kills, at idle, during preview, capture and video.

**Done 2026-11-05**:
- **ION contig heap 140 → 16 MB** (kernel 247b11e0): MemTotal 753 → 864 MB. The heap stayed at 0 bytes with the preview, a 16 MP + RAW capture and a hardware H.264 encode (`screenrecord`). **Untested: video recording**, whose HAL path (`exynos_camera_recording_output_start` → `exynos_v4l2_output_start`) allocates from ION; if recording fails, raise the heap first.
- **cgroups work** (kernel 8cdc05d8): system_server could not move app threads (EACCES on kernel 3.4), so background apps kept all four cores. Android's `allow_attach` hook for CAP_SYS_NICE fixes it; background is cpuset 0-1. `/dev/cpuctl/background/cpu.shares` was 1024 (nobody sets it); init.target.rc now writes 52, as older Android did (next full build).
- **LMK test** (runtime only, lost on reboot): minfree 48/56/64/80/112/144 MB instead of AM's 72/90/108/126/144/180. Redo with the bigger MemTotal, then set it in an overlay (`config_lowMemoryKillerMinFreeKbytesAbsolute`).
- **Stress test passed (2026-11-05, full build 20261105, default LMK thresholds)**: 10 cycles of GC Camera RAW shot → GC Gallery 2 s later → Home → Settings, then 2 min with all four cores busy and a shot under load. The camera process survived the whole run (HOT returns in 0.45-0.8 s, 0.44 s under load), all 11 DNGs complete, the app's adj 50 while saving, LMK killed only cached processes, allocstall +72 in the first two cycles then flat, temperature 41 → 76 °C at 1.4 GHz without throttling. Logs: `device-dump/logs/2026-11-05-stress/`. So the runtime LMK test is not needed for now.
- **Leak watch**: `/data/local/tmp/memwatch.sh` logs PSS and vmstat every 10 min to `/data/local/tmp/memwatch.log` (started 18:39). Compare after a few hours of use; stop with `pkill -f memwatch.sh`.

**Then**:
1. ~~Shrink the ION contig heap~~ (done, see above). Our open camera HAL allocates only its JPEG-encoder and V4L2-output buffers from it (`exynos_ion.c`).
   - Disabling `EXYNOS_CONTENT_PATH_PROTECTION` alone does not free ION, because the non-CPP layout keeps the "ion" region.
   - Check whether the MFC secure region can shrink without breaking video encode/decode.
   - Check whether FIMC0 and FIMC1 (63MB) are still used by the open HAL; it may use ION/USERPTR instead of the reserved regions.
2. **Strip packages** with a `RemovePackages` module (`LOCAL_OVERRIDES_PACKAGES`) in device/samsung/gd1, built from the real `installed-files.txt`. Priority: anything persistent or started at boot. F-Droid is no longer wanted (user, 2026-10-31).
2b. **Next debloat round** (measured RSS on 2026-10-31: system_server 190MB, SystemUI 157, launcher3 89, permissioncontroller 77, mediaprovider 70, LatinIME 64, phone 62, networkstack 58, extservices 52):
   - stop statsd, incidentd and storaged. Check that system_server only warns about missing statsd and stays stable;
   - keep traced/perfetto while we profile the UI, and drop them in the release build;
   - drop LocalTransport, SharedStorageBackup, WallpaperBackup, BackupRestoreConfirmation, PacProcessor, ProxyHandler, SoundPicker and ManagedProvisioning (user OK, 2026-10-31). Without SoundPicker, also hide the notification/ringtone sound entries in Settings;
   - Bluetooth off by default and no lockscreen by default.
   - The big wins are our camera app as HOME (~90MB) and the ION resize (60–70MB).
3. **Tune LMK minfree and zram size** from the measurements (overlay `config_lowMemoryKillerMinFreeKbytes*`).
4. **Our camera app as HOME launcher** (later): drops the launcher process entirely.

## Power and performance (old battery, 2012 hardware)

- Audit the kernel debug options inherited from the i9300/Samsung configs (SEC_DEBUG*, tracing, schedstats) and turn off what is not needed.
- Pick a cpufreq governor and min/max frequencies for idle-heavy camera use, and review CPU hotplug.
- Measure the screen-off drain and wakelocks after the first boot (`dumpsys batterystats`, wakeup_sources), then fix the offenders.
- Make sure the XMM6262 modem can be powered down (airplane mode / data off) without wakeups. The camera has no voice use.
  - Seen 2026-11-01 (no SIM inserted): rild logs `mif: usb_init_communication` every 13 s, forever. Find out what it retries, and stop it when there is no SIM or data is off.
- **The screen turned itself back on ~5 s after every timeout** (user, 2026-11-01), so the device never stayed in standby. Cause: not the charger, but doze.
  - smdk4412-common enables doze for the i9300's AMOLED. On timeout the device dozed, the disabled keyguard hid at once, and SystemUI's `KeyguardViewMediator.handleHide()` woke the screen (`WAKE_REASON_GESTURE`, `BOUNCER_DOZING`).
  - Fix: gd1 a3413ac + fbd0530 clear `config_dozeComponent` (no ambient display on an LCD). **Verified 2026-11-01**: after the timeout the device is `Asleep` with the display OFF, and stays there.
  - Wake-on-plug (`config_unplugTurnsOnScreen=true` from smdk4412-common) stays as is (user, 2026-11-01).
- The MAX77693 charger re-reports the cable every ~35 s at 90% on the PC's USB port (cable state 5, CDP), as it cycles between charging and full. Each report wakes the CPU, and with wake-on-plug it could light the screen too. Check why, and test with a wall charger.
  - **Own charging screen** (user idea, 2026-11-01). The panel is an LCD (s6d6aa1), so there is no burn-in risk, but the backlight costs the same on a black screen, so keep it short.
    - Powered off: a custom off-mode charger theme (camera-style, with percentage), then screen off.
    - Powered on: on a real plug-in, show our charging overlay for a few seconds and then turn the screen off. This could replace the plain wake-on-plug behaviour.
- Reduce the low-RAM / Go profile further: background process limits, zram size, no unneeded persistent services.
- **Camera standby: as it is** (user, 2026-11-08; the instant-on plan is dropped). A short Power press, or the screen timeout, already is the standby: GC Camera releases the camera, the lens retracts and the device suspends. The power menu's "Power off" really powers off (`CONFIG_FAST_BOOT`, Samsung's fake shutdown, stays off).
- **Auto power off** (user, 2026-11-08): after 6 h asleep and unplugged the device powers off; settable in Settings > Battery (Never, 1, 3, 6, 12, 24 h). Waking or plugging in restarts the count.
  - SystemUI `power/AutoPowerOff.java` (frameworks/base `gd1`), started by PowerUI, uses an exact allow-while-idle alarm and `IStatusBarService.shutdown()`. It is off unless the device overlay sets `config_autoPowerOffTimeout` (gd1: 21600000 ms) and creates `Settings.Global auto_power_off_timeout` (ms, 0 = never) on first start.
  - Settings `fuelgauge/AutoPowerOffPreferenceController` (packages/apps/Settings `gd1`) shows the entry only when that setting exists.
  - It never powers off while charging, so it cannot drop the camera into off-mode charging (see "Off-mode charging ended in download mode" below).
  - Verified 2026-11-08 with `dumpsys battery unplug`: asleep and unplugged arms 360 min, waking cancels, plugged arms nothing. The power-off itself (alarm firing) is not yet seen on the device.
- **Settings says "camera", not "phone"** (user, 2026-11-08): gd1 `overlay/packages/apps/Settings/res/values{,-it}/phone_to_camera.xml`, generated by `tools/settings-phone2camera.py` from Settings' product="default" strings plus a short list of plain ones. Calls, phone numbers and phone support keep "phone"; features the GC100 lacks (fingerprint, face, NFC, satellite, watch unlock) were left alone. Rerun it after a Settings rebase and check the Italian agreement (fotocamera is feminine) by eye. The power menu has no emergency call (gd1 `config_globalActionsList`).
- **Micro-HDMI: placeholder only** (2026-11-08). The kernel side works: HPD (`/sys/class/switch/hdmi`), s5p-tvout at 720p60 and mixer graphic layer 0 (`/dev/graphics/fb10`), verified on a TV with `bringup-tools/hdmitest.c`. Use output 2 (HDMI RGB): with output 1 (YCbCr) the TV read the signal as RGB and black came out purple. The display stack has no HDMI for the 4412 (hardware/samsung builds libhdmi only for the 4210, and the hwcomposer has no external display), so for now gd1's `hdmisplash` daemon shows a "support is coming" screen while a TV is connected.
  - Wanted (user, 2026-11-08): no screen mirroring, but GC Gallery's photos and GC Camera's live view on the TV.
  - Gallery: a vendor service that takes a decoded image (or a path) and puts it on graphic layer 0, scaled to the TV mode; hdmisplash can grow into it.
  - Live view: the camera HAL's preview buffers are FIMC memory with physical addresses, so the HAL can hand them straight to the TV video layer (`/dev/video20`, NV12) without a copy, behind a camera parameter.
  - Still to check: 1080p modes and EDID, HDMI audio, and what the tvout driver does across suspend.
- **Off-mode charging ended in download mode** (2026-11-08, twice, both on the wall charger). Workaround: off-mode charging no longer suspends (gd1 `SMDK4412_CHARGER_NO_SUSPEND`, which drops smdk4412-common's `ro.charger.enable_suspend=true`). The root cause is open.
  - What happened: the charger suspends and an RTC alarm wakes it about once a minute. Right after some resumes, processes crashed. ueventd segfaulted (twice at the same address, 0x5c228c6f), and `sleep` aborted in `BORINGSSL_bcm_power_on_self_test`, which means wrong data. After four ueventd crashes init calls `reboot,bootloader`, and on Samsung that sets INFORM3 to 0x12345671, which is download mode. Logs: `device-dump/charger-dl-20261108/` (last_kmsg survives the warm reset; it holds the serial, so keep it out of git).
  - Verified 2026-11-08: with a wake lock held in charger mode (no suspend), 42 min of off-mode charging, 44% to 75%, without a single crash.
  - Charger mode differs from normal suspend in one obvious way: `init.smdk4x12.rc` sets the `powersave` governor, so every resume runs at the lowest CPU frequency and voltage, with all four cores online (`exynos_restore_gov_freq` drops to the minimum right after the PM lock is freed). Suspend under Android (pegasusq) has shown no such crashes.
  - **The governor is the trigger** (tested 2026-11-08): with suspend back on and pegasusq instead of powersave in charger mode, 19 resumes over two runs (29 min from 92%, 62 min from 24%) had no crash. With powersave, the afternoon run had 6 crashes in about 30 resumes, and the morning one crashed after 7. Logs: `device-dump/charger-dl-20261108/test2`, `test3`. Suspect: too low a voltage at the minimum OPP just after resume (ASV table or the resume path). The fix stays "no suspend in charger mode", which has no resumes at all; the finding matters for normal use: never run the 4412 at the minimum frequency with the `powersave` governor across suspend.
  - Until then, keep an eye on unexplained native crashes right after a resume in normal use (the 2026-11-04 JIT jump to address 0 could be one).
  - The fix is in the build flashed on 2026-11-08 (kernel #67), which also replaced the hand-pushed debug rc (`zz-charger-debug.rc`). The old charger logs stay in /cache (`chg-*`); copies are in device-dump.

## ART

- **A JIT-compiled call jumped to address 0** (once, 2026-11-04, tombstone_36, GC Camera's photo-writer thread, 20 s after start).
  - The frames were `#00 pc 0` and `#01 /memfd:jit-cache java.nio.Bits.putShortL+146`: HeapByteBuffer.putShort in little-endian order, writing EXIF tag 0xA002. The callee's entry point was read as null.
  - It only appears once the method is hot. Other code that uses ByteBuffer put*/get* with an explicit byte order (the `Bits.*L` helpers) may hit it too.
  - The app now writes EXIF without ByteBuffer. No other JIT crash in the 37 kept tombstones.
  - To look at:
    - whether it reproduces (a loop of putShort in a test app);
    - UL's ART fork and html6405's issues for armv7 JIT crashes;
    - JIT code-cache collection racing with entry-point updates;
    - the dual-view (memfd) JIT code cache on the 3.4 kernel, i.e. instruction cache maintenance of the executable alias.
  - Fallbacks if it recurs: `dalvik.vm.usejit=false` (apps rely on AOT, slower first runs), or a JIT option that avoids the failing path, once found.

## GC200 NFC tag (looked at 2026-11-11, postponed by the user)

- **Hardware**: an NXP NTAG213 (NFC Forum Type 2, 144 user bytes) that only a phone can read or write. The AP sees just its field-detect line, `GPIO_NFC_IRQ` = GPF0(0) (gpio 47 on our kernel, already an input in gc1-gpio.c). It idles high and drops for 0.1-0.2 s about once a second while a phone's field is on it, which matches the phone polling for tags. Samsung's `drivers/nfc/sf2_nfc_tag.c` (GC200 kernel, `CONFIG_SF2_NFC_TAG`) only turns the falling edge into a uevent on `/devices/virtual/nfc/NFC_TAG`. Stock services.odex rebroadcasts it as `com.android.nfc.nfc_interrupt`, which the stock camera and gallery used for "Tag & Go".
- **Tag content as shipped**: a MIME record `application/com.samsungimaging.connectionmanager` holding the camera's stock Wi-Fi Direct SSID (`AP_SSC_GC200_0-` plus the end of the unit's MAC, so don't copy it into tracked files), and an AAR for the dead Samsung app, so a tap opens the Play Store. The tag is not locked and has no password. Back it up from the phone before rewriting it, and never lock it: making it read-only is permanent.
- **Plan when we pick it up**:
  - kernel: map the field-detect edge to a key (`MACRO_1`, Android 14) for GC Camera and GC Gallery, with no background service;
  - first use: tapping the phone sends the last photo over Bluetooth OPP to the paired phone (no phone app needed); rewrite the tag with a link to the project page;
  - alternative: a Wi-Fi WSC record on the tag, a camera hotspot and a web gallery (faster for RAW, much more work).

## Updates over the air (user wish, 2026-11-04)

- **Our APKs first** (GC Camera, GC Gallery): an update check against GitHub Releases, with the download installed through PackageInstaller. The system copies are presigned with our key, so a same-key APK installs on top of them, as `pm install -r` already does. Options: an F-Droid repo of our own (the F-Droid client is in the build), or a small updater inside each app.
- **System OTA later**: there is no OTA zip today (no recovery in the build, `m droid` only). It needs a recovery that fits the 8 MB RECOVERY partition (or an A/B-less updater in the mini recovery) and LineageOS Updater (removed for now) pointed at our own server.

## Android tree

- **Base frozen at April 2025.** LineageOS-UL 21 stopped on 2025-04-04, so all LineageOS projects are pinned to that date (`manifests/zz-lineage-snapshot.xml`) and the security patch level is about April 2025. Options to move forward:
  - merge newer LineageOS lineage-21.0 into our own copies of the ~30 UL forks;
  - or move to html6405's LineageOS 23.2 line once it supports smdk4412 phones and the kernel.

- **`frameworks/av` is html6405's `lineage-21.0-qpr3-camera` (Aug 2024)** instead of UL's (Apr 2025), because only it has camera HAL1.
  - Media is a frequent CVE target, so this project is about 8 months older in security fixes than the rest.
  - `frameworks/base` follows it: html6405's `camera_hal1-r08` with UL's final head merged on top, so it is current. Its camera Java side is reverted to match the 12.1-era camera AIDL.
  - Proper fix: rebase the HAL1 commits (device1, HALv1 recording path, `[UL/HAL1]` fixes) onto UL's `lineage-21.0` av.
  - Longer term: a HAL3 camera HAL for the M9MO, which would remove the need for both forks.
- **Legacy health@2.0 HAL** is vendored in `device/samsung/gd1/legacy/health` because LineageOS-UL dropped it. Proper fix: an AIDL health HAL for exynos4 (`android.hardware.health-service`) in hardware/samsung.
- **`vendor.lineage.power@1.0`** comes from html6405's `hardware/lineage/interfaces` fork (`lineage-21.0-qpr3`, Aug 2024) instead of UL's. Proper fix: move the exynos4 power HAL to AIDL power and drop the fork.
- **smdk4412-common prebuilt apps** (AuroraStore, Via browser, OpenCamera) are still installed by the common tree. Strip them for the camera-first profile. Keep or replace OpenCamera depending on the camera app choice.
- **`hardware/samsung` `SamsungServiceMode` include** was made optional rather than removed.
- **i9300 `lineage.dependencies`** is invalid JSON (a missing comma), which makes roomservice crash during `lunch`. It is harmless for us; it could be reported upstream.
- **smdk4412-common extract scripts** were rewritten for LOS21 extract-utils. The old `proprietary-files.txt` (pre-treble paths) was replaced, so make sure nothing else used it.
