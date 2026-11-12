# Technical overview

How the port works, for developers. The user-facing story is in the [README](../README.md), the install steps in [INSTALL.md](INSTALL.md).

Other docs:
- [CAMERA-APP.md](CAMERA-APP.md): the full camera HAL contract (every parameter) and GC Camera's design.
- [GALLERY-APP.md](GALLERY-APP.md): GC Gallery.
- [RECOVERY.md](RECOVERY.md): the mini recovery, backups and restore.
- [LENS-PROFILE.md](LENS-PROFILE.md): the lens calibration.
- [TODO.md](TODO.md): every known workaround and open issue.

## 1. The device

| | |
|---|---|
| Model | Samsung Galaxy Camera EK-GC100, codename `gd1` (product `gd1xx`), board `smdk4x12` |
| SoC | Exynos 4412, 4x Cortex-A9 1.4 GHz, Mali-400 MP4 (GLES 2.0) |
| RAM | 1 GB, about 755 MB visible to the kernel after the reserved regions |
| Storage | 8 GB eMMC (Samsung M8G1GC on the reference unit), microSD |
| Display | 4.8" 720x1280 s6d6aa1 LCD, run in fixed landscape |
| Camera | 16 MP 1/2.3" BSI CMOS, 21x zoom 4.1–86.1 mm f/2.8–5.9 with OIS, xenon pop-up flash, **Fujitsu M9MO ISP** (firmware FCGB04) |
| Modem | Intel XMM6262 (same as the Galaxy S III i9300) |
| Wi-Fi / BT | Broadcom BCM4334 |
| GPS | Broadcom BCM4752 |
| Sensors | LSM330DLC accelerometer + gyroscope, AK8963C compass |
| Stock firmware | Android 4.1.2, GC100XXBMC4 (the last official release) |

The GC100 is a close sibling of the i9300 and the other Exynos 4412 devices that html6405 maintains, which is why the port starts from his trees.

## 2. Why the camera HAL is only a translator

The camera is not a bare sensor. All of the photographic intelligence lives in the **M9MO**, an external image processor with its own firmware:
- auto exposure, auto focus and auto white balance, including the P/A/S/M exposure programs;
- the zoom, focus and OIS motors, the shutter and iris, the flash and the lamps;
- the JPEG encoder: the ISP delivers a finished 16 MP JPEG, plus a thumbnail and postview.

The SoC talks to it over I²C (register categories such as MON, CAPPARM, CAPCTRL, PARM, LENS) and receives frames over MIPI CSI into FIMC, the Exynos camera interface.

So the stack is:

```
GC Camera  --Camera1 parameters-->  camera HAL (HAL1)  --V4L2 controls-->  m9mo.c  --I2C registers-->  M9MO ISP
                                         ^                                    |
                                         +------ FIMC (preview YUV, JPEG, RAW) <-- MIPI CSI
```

- **Kernel**: `m9mo.c` (Samsung's GPL driver, about 11,000 lines) exposes every ISP function as a V4L2 control: `V4L2_CID_CAMERA_OPTICAL_ZOOM_CTRL`, `_PASM_MODE`, `_SHUTTER_SPEED`, `_F_NUMBER`, `_ISO`, `_IMAGE_STABILIZER`, the JPEG offsets and sizes, and so on.
- **HAL**: Samsung's closed HAL was mostly a translator between Camera1 parameters and those controls. We replaced it with an **M9MO preset in `exynos_camera`**, the free HAL1 camera HAL by Paul Kocialkowski (Replicant) that html6405 ships for the i9300's S5C73M3. The S5C73M3 is also a "smart" ISP with JPEG output, and 232 of the 276 camera controls in its header are the ones `m9mo.c` uses, so the architecture fits.
- **Why HAL1**: `exynos_camera` is a HAL1 (Camera1) device. LineageOS-UL's `frameworks/av` has no HAL1 support, so the build uses html6405's HAL1 branches of `frameworks/av` and `frameworks/base` (section 5). Apps see the camera as Camera1; Camera2 would see only a LEGACY device without our vendor parameters.

The HAL lives in `device/samsung/gd1/camera/`. Its parameters (PASM, continuous zoom, metering, live exposure readout, AF areas, brackets, RAW, flash pop-up state, look controls) are documented in [CAMERA-APP.md §3](CAMERA-APP.md).

Behaviour worth knowing:
- The ISP powers up at `startPreview()`, not at `open()`, so the lens stays in at boot. Power-up takes about 2 s.
- `takePicture()` stops the preview and takes the still in a separate FIMC session, about 1–2 s per shot (about 3 s with RAW).
- The ISP JPEG carries no exposure EXIF. The HAL reads the exposure back from the ISP after every shot (`exif-*` parameters) and the app writes the EXIF.

### Safety in the kernel and HAL

These are deliberate and must stay:
- **No ISP firmware writes.** `m9mo.c` can flash `RS_M9MO*.bin` into the camera module. Our kernel refuses firmware update and dump, factory and calibration controls, and debug writes (kernel 6c4576ab, 13c96831, 9f16697d). A failed write would kill the camera module.
- **No touch firmware writes.** `mms_ts_gc.c` has its boot-time update disabled (1e0aecdb).
- **The lens always retracts.** On close, on reboot (init kills the HAL, and the FIMC release runs `m9mo_remove` → lens off) and on power off (an i²c shutdown hook). It uses Samsung's final polling lens-off (LENS 0x28: 4 = retracting, 0 = in). Kernel adbb5a51, 0fb751df, abbe35eb.
- **No writes to PARAM.** smdk4412-common's `param.sh` would `dd` over the bootloader's PARAM partition; gd1 sets `SMDK4412_NO_PARAM_SCRIPT := true`, and ueventd gives no group write access to PARAM, RADIO and HIDDEN.
- Lens and motor moves use only the ISP's own commands and ranges from `m9mo.c`. No raw GPIO or motor access.

## 3. The RAW pipeline

### Capture

The M9MO has a RAW mode that Samsung never exposed:
1. `m9mo.c`'s `RUNNING_MODE_RAW` sets CAPPARM register 0x78 to 1.
2. Frame 0 is read with `YUVOUT_MAIN = 0x05` (raw output).
3. Frame 1 is the normal 16 MP JPEG (size code 0x33).
4. FIMC stops after every frame in JPEG mode, so the HAL restarts it between the two frames.

The app turns RAW on per shot with `raw-capture=on`. Camera1 delivers the frame through `CAMERA_MSG_RAW_IMAGE` only when a buffer has been queued with the hidden `Camera.addRawImageCallbackBuffer(byte[])`, which GC Camera calls by reflection ([CAMERA-APP.md §3.4](CAMERA-APP.md)).

RAW does not work in bracket modes: the ISP keeps bracket frames only as developed JPEGs (tested, [TODO.md](TODO.md) "Burst merge").

### Format (measured)

| | |
|---|---|
| Size | 20,440,000 bytes |
| Packing | a continuous **big-endian 10-bit stream**, 4 pixels in 5 bytes, MSB first (not MIPI RAW10) |
| Geometry | 4736 px stride (5920 bytes per row) x 3452 rows, then a 4160-byte tail |
| CFA | GRBG |
| Active area | x 2..4665 (columns 0–1 and 4666–4735 are zero); no optical-black margin |
| Black level | 64 (dark frame: 63.2–63.6 on all four channels). It depends a little on exposure and ISO: ~60.5 at 1/30 ISO 3200. The DNG says 64. |
| White level | 1023 (assumed) |
| Content | the whole lens image circle: strong barrel distortion and vignetting, wider than the JPEG, which the ISP corrects and crops |

The HAL reports the layout as `raw-size`, `raw-format`, `raw-cfa`, `raw-active-area`, `raw-black-level` and `raw-white-level`. Black and white can be overridden with `persist.vendor.camera.raw.black` / `.white`.

Tools:
- `tools/m9mo-raw.py`: inspects and decodes a raw frame.
- `tools/m9mo-dng.py`: the reference DNG writer (`.raw` + `.raw.txt` sidecar → DNG, validated with exiftool and LibRaw).

### DNG writing in the app

`camera-app/gccore/` is the app's C++ core: the DNG writer and the white-balance match against the ISP's JPEG.
- `dng.cpp` writes DNG 1.4: the 16-bit CFA in a SubIFD, a small sRGB preview in IFD0, the ISP JPEG as the full-size preview, ActiveArea, DefaultCrop, black/white levels, ColorMatrix1, AsShotNeutral, LensInfo, Orientation, exposure EXIF, and an XMP packet.
- It streams row by row: no 16-bit full-frame buffer, because RAM is tight.
- The XMP packet turns on the GC100 lens profile in Lightroom and crops to the ISP's frame ([LENS-PROFILE.md](LENS-PROFILE.md)). The pixels stay raw: no distortion opcode is applied.
- Colour: ColorMatrix1 is fitted against the ISP's own JPEGs of the same scenes (no ColorChecker), single illuminant for now. AsShotNeutral comes from matching the raw against the ISP's JPEG of the same shot.

### Development

There is no RAW development on the camera. An on-device pipeline was built and measured (2026-11-04): it reached parity with the ISP's JPEG at ISO 160 and lost at ISO 400, so it was removed. The camera keeps the ISP JPEG and the DNG, and the DNG is developed on a PC (Lightroom, Camera Raw, RawTherapee). See [RAW-PIPELINE.md](RAW-PIPELINE.md).

## 4. Source trees and branches

The Android build tree combines LineageOS-UL, html6405's Exynos 4412 trees and our own changes. Our patches sit on **`gd1` topic branches** that track the upstream branch, so `repo sync` rebases them.

> The `gd1` branches are published as GitHub forks under [simoneluconi](https://github.com/simoneluconi?tab=repositories) (linked below), and `manifests/gd1.xml` fetches every patched project from them. The local branches track the upstream branch; the forks are pushed from them.

| Path | Upstream | Our branch, and what it adds |
|---|---|---|
| [`device/samsung/gd1`](https://github.com/simoneluconi/android_device_samsung_gd1) (branch `lineage-21.0`) | new | **The device tree** (Apache-2.0, camera and sensors GPL-3.0+). Board config, M9MO camera HAL (`camera/`), sensors HAL for LSM330 + AK8963C (`libsensors/`), keylayout for the shutter and zoom lever, GPS shims for the stock BCM4752 daemon, vendor sepolicy, overlays (no voice/SMS, no doze, landscape), the debloat (`RemovePackages`), the legacy health@2.0 HAL (removed by UL), GC Camera and GC Gallery as prebuilts. |
| [`device/samsung/sf2wifi`](https://github.com/simoneluconi/android_device_samsung_sf2wifi) (branch `lineage-21.0`) | new | **The GC200 device tree** (Apache-2.0). It inherits gd1's `common.mk` and adds the GC200's differences: Wi-Fi only, 2 GB RAM, partitions, touch mapping, init files. |
| [`kernel/samsung/smdk4412`](https://github.com/simoneluconi/android_kernel_samsung_smdk4412/tree/gd1) | html6405 `lineage-21.0-BPF` (3.4) | `gd1`: `lineageos_gd1_defconfig`; GC1 panel timings; M9MO safety (no firmware/factory writes, lens retraction, interruptible-wait fix); pop-up flash switch (`SW_STROBE_INSERT`, sysfs `strobe_insert`); AE bracket/HDR, OIS capture mode, self-timer lamp, LSC/LDC in parameter mode; touch firmware update off; AN30259A LED off (it panicked); vmalloc 256 MB; CPU hotplug with pegasusq; microSD fixes (FAT `time_offset=`, SD high speed, 512 KB read-ahead); a dcache stopgap for an exFAT/FUSE dentry leak. |
| [`device/samsung/smdk4412-common`](https://github.com/simoneluconi/android_device_samsung_smdk4412-common/tree/gd1) | html6405 `lineage-21.0` | `gd1`: build the common modules for gd1; skip its camera HAL; LOS21 extract-utils scripts; Mali GLES modules also in `/system/lib/egl`; optional `param.sh`; no prebuilt Nfc app; ueventd without group write on PARAM, RADIO and p10; its debug props (root adb without authorisation) can be left out, and our release builds leave them out. |
| [`hardware/samsung`](https://github.com/simoneluconi/android_hardware_samsung/tree/gd1) | html6405 `lineage-21.0-exynos4` | `gd1`: `String8::c_str()` (UL's libutils made `string()` private); SamsungServiceMode include optional. |
| [`frameworks/av`](https://github.com/simoneluconi/android_frameworks_av/tree/gd1) | html6405 `lineage-21.0-qpr3-camera` (Aug 2024, has camera HAL1) | `gd1`: UL's full camera platform flag list, which UL's frameworks/base needs. |
| [`frameworks/base`](https://github.com/simoneluconi/android_frameworks_base/tree/gd1) | html6405 `lineage-21.0-qpr3-camera_hal1-r08` | `gd1`: UL's final head (2025-04-04) merged on top, API files updated. **base and av must come from the same HAL1 pair.** |
| `hardware/lineage/interfaces` | html6405 `lineage-21.0-qpr3` | none (replaces UL's, which dropped `vendor.lineage.power@1.0`). |
| [`packages/modules/adb`](https://github.com/simoneluconi/android_packages_modules_adb/tree/gd1) | LineageOS-UL | `gd1` f31cfce: start legacy FunctionFS transports on the device. Without it adbd stays "offline" on kernels without FunctionFS AIO. Worth sending upstream. |
| [`frameworks/opt/telephony`](https://github.com/simoneluconi/android_frameworks_opt_telephony/tree/gd1) | LineageOS-UL | `gd1` 991b87a: PhoneSwitcher fix for single-SIM devices with a legacy radio HAL. Without it mobile data could fail to come up at boot, and the mobile icon showed "!". Worth sending upstream. |
| [`bionic`](https://github.com/simoneluconi/android_bionic/tree/gd1) | LineageOS-UL | `gd1`: html6405's "Ignore invalid pthread_t" (libMali passes a bad pthread_t to `pthread_join`). |
| [`system/core`](https://github.com/simoneluconi/android_system_core/tree/gd1) | LineageOS-UL | `gd1` cf3a6bb: tombstoned works without `O_TMPFILE` (kernel 3.4). |
| [`system/unwinding`](https://github.com/simoneluconi/android_system_unwinding/tree/gd1) | AOSP `android-14.0.0_r67` | `gd1`: libunwindstack falls back to `PTRACE_GETREGS` (no `PTRACE_GETREGSET` on 3.4 ARM). AOSP's history holds a 219 MB test file, over GitHub's 100 MB limit, so the fork's `gd1` is a flat import of the r67 tree plus the patch. |
| [`packages/apps/Trebuchet`](https://github.com/simoneluconi/android_packages_apps_Trebuchet/tree/gd1) | LineageOS `lineage-21.0`, at the snapshot's pinned commit | `gd1` bc7aa77: finish a recents animation that starts after its gesture was dropped. On this slow device that race left Home and every app unable to take touches. |

This repository (GalaxyCameraRevived) holds what is not part of the Android tree: the docs, `manifests/`, `tools/`, the mini recovery sources (`minirecovery/`), the apps (`camera-app/`), the lens profile (`profiles/`) and reference copies of `m9mo.c` and `exynos_camera` (`reference/`).

### The LineageOS-UL freeze

LineageOS-UL 21 stopped on **2025-04-04**: every UL fork's last commit is the same merge of that day. LineageOS itself kept changing `lineage-21.0` afterwards, so mixing UL with current LineageOS breaks the build.

`manifests/zz-lineage-snapshot.xml` pins all 232 `LineageOS/*` projects to their last commit before 2025-04-05 with `<extend-project revision=...>`. It is generated by `tools/pin-snapshot.sh <root> "2025-04-05 00:00" <out>`, which uses `rev-list --first-parent --before`. Without `--first-parent`, merged AOSP side branches (which keep their old dates) get picked. AOSP projects are already pinned by UL to `android-14.0.0_r67`.

Consequence: the security patch level is 2025-03-01, and `frameworks/av` is about 8 months older than that. Moving forward means either merging newer LineageOS into our own copies of the UL forks, or moving to html6405's LineageOS 23.2 line once it supports the 4412 phones and the kernel.

## 5. Building

### Host

Tested on WSL2 Ubuntu 22.04 with 48 GB RAM, 16 threads and about 300 GB of free disk. The usual LineageOS build dependencies, `repo`, and 50 GB of ccache.

### Sync

```
mkdir -p ~/android/lineage && cd ~/android/lineage
repo init -u https://github.com/LineageOS-UL/android.git -b lineage-21.0 \
    -g default,-qcom,-device,-mips,-darwin,-notdefault --depth=1
mkdir -p .repo/local_manifests
cp <this repo>/manifests/gd1.xml <this repo>/manifests/zz-lineage-snapshot.xml .repo/local_manifests/
repo sync -c -j8
```

- The groups leave out the Qualcomm HALs (useless here; the snapshot's qcom-caf display repos also break the analysis). That is about 1333 projects.
- After an interrupted sync, check for emptied checkouts (`git status` in each project) if odd "undefined module" errors show up.

### Blobs (never committed)

No proprietary file is in any of our repositories. They are extracted into `vendor/samsung/...` at build time:

1. **The GC100's own blobs** come from the stock GC100XXBMC4 `/system`: either `adb pull` from a stock camera (no root needed for most of it) or the official firmware's `system.img` (unsparse with `tools/simg2img.py`, read with `debugfs`).
2. **The common Exynos 4412 blobs** (Mali, UMP, MobiCore, SEC OMX, and the XMM6262's `cbd` and `libsec-ril.so`) come from html6405's public i9300 LineageOS 21 build (29.10.2024, XDA). Unpack its block OTA with `tools/unpack-ota.sh` (uses `tools/sdat2img.py`) and loop-mount `system.img` and `vendor.img` read-only.
3. `tools/mk-blobsrc.sh device/samsung/gd1/proprietary-files.txt <gc100 system> <i9300 vendor> <out>` builds a blob source in which `vendor/` holds **only** the files we take from the i9300. extract-utils looks at a blob's destination path first, so a full i9300 `vendor/` would silently replace the GC100's Wi-Fi NVRAM and Bluetooth patchram with the i9300's (it happened once).
4. Extract:
   ```
   cd device/samsung/gd1 && ./extract-files.sh <blob source>
   cd ../smdk4412-common && ./extract-files.sh <i9300 LOS21 mount>
   ```

`tools/classify-blobs.py` helps decide which files in a vendor partition are built from source and which are prebuilt.

### Build

```
source build/envsetup.sh
lunch lineage_gd1-ap2a-userdebug
m droid
```

- Use `m droid`, not `m bacon`: there is no recovery in the build (`TARGET_NO_RECOVERY`, see below), so no OTA zip.
- The kernel is built in-tree with `lineageos_gd1_defconfig`.
- Run `source tools/build-env.sh` (this repo) before `m`: the build then says it was made by `simoneluconi@galaxycamerarevived`, with a build number `gcr.<date>.<time>`, instead of your login, your PC's name and `eng.<user>.<date>`. The kernel's user and host are set in the device tree. The version reads `21-<date>-UNOFFICIAL-GalaxyCameraRevived-gd1`.
- For your own development builds, put your PC's `adbkey.pub` in `device/samsung/gd1/bringup/adb_keys` (git-ignored) and build with `GALAXYCAMERA_DEV_ADB_KEYS=true` in the environment: adb is then trusted from the first boot. Never set it for a build you give to others.
- The device tree is split for a future Galaxy Camera 2 build: `common.mk`/`BoardConfigCommon.mk` (shared) and `gd1.mk`/`BoardConfig.mk` (GC100 only). See [GC200.md](GC200.md).
- Outputs in `out/target/product/gd1/`: `boot.img`, `system.img`, `vendor.img`.

### Packaging for Odin

The `/vendor` partition lives on the stock HIDDEN partition, so `vendor.img` is flashed as `hidden.img`. An Odin package is the usual Samsung `.tar.md5`:

```
cp vendor.img hidden.img
tar -H ustar -cf lineage-gd1-<date>-odin.tar boot.img system.img hidden.img
md5sum -t lineage-gd1-<date>-odin.tar >> lineage-gd1-<date>-odin.tar
mv lineage-gd1-<date>-odin.tar lineage-gd1-<date>-odin.tar.md5
```

Never include `sboot.bin`, `tz.img`, `param.lfs`, a PIT, or any partition other than BOOT, SYSTEM, HIDDEN (and RECOVERY for the mini recovery).

### Partitions

No repartitioning. The stock layout fits:

| Partition | Size | Use |
|---|---|---|
| BOOT p5 | 8 MB | boot (about 6 MB) |
| RECOVERY p6 | 8 MB | the mini recovery |
| RADIO p7 | | stock modem, untouched |
| CACHE p8 | 1 GB | cache |
| SYSTEM p9 | 1.5 GB | system |
| HIDDEN p10 | 360 MB | `/vendor` (about 86 MB used) |
| OTA p11 | 8 MB | `/misc` |
| USERDATA p13 | 4.1 GB | data |
| EFS p3, PARAM p4 | | untouched |

### Flashing a development build

Developers can skip Odin once the mini recovery is installed: `tools/flash-adb.sh prepare`, `push` and `flash` (WSL). It writes BOOT, SYSTEM and HIDDEN through the mini recovery's `flash-image`, which verifies every partition by reading it back and refuses anything else. `ONLY_BOOT=1` flashes the kernel alone. Details in [RECOVERY.md](RECOVERY.md).

Userspace changes do not need a reflash on a userdebug build: `m <module>`, `adb root`, `adb remount`, `adb sync` or `adb push`.

## 6. The mini recovery

LineageOS recovery is 15.7 MB and the RECOVERY partition is 8 MB, so the build has no recovery. `minirecovery/` holds our own small one (about 5.4 MB): the stock GC100XXBMC4 recovery kernel, init and adbd (known good, and independent of our kernel), plus our `init.rc`, scripts, status screens, a setuid `su` (`minirecovery/su.c`, because the stock adbd always drops root) and a static Alpine busybox.

Commands: `backup-critical`, `flash-image` (verified writes, with a whitelist of partitions), `wipe-data --yes` (zeroes the start of USERDATA and CACHE instead of sending an MMC erase). It also handles Android's "factory reset" request from the misc partition.

Build it with `tools/build-minirecovery.sh <stock recovery.img> <busybox static> <out dir>` (plus `SU=<static su>`). **The output contains Samsung binaries**, so it is never committed. Full documentation in [RECOVERY.md](RECOVERY.md).

## 7. Debugging tips

- **Two adb serials.** The same camera reports one serial under LineageOS and another in the mini recovery (the stock adbd). Always pass `-s`.
- **`adb install` fails** on kernel 3.4 (splice EINVAL). Use `adb push app.apk /data/local/tmp/` and `adb shell pm install -r /data/local/tmp/app.apk`.
- **Native crashes** give full tombstones in `/data/tombstones` (thanks to the system/core and system/unwinding patches).
- **Camera logs**: `adb logcat -s exynos_camera`; kernel `dmesg | grep -i m9mo`.
- **Never kill the camera provider** to reset the camera: the restarted CameraService then refuses every client until a reboot (TODO.md).
- **USB ACM root console** (userdebug, opt-in): `setprop persist.vendor.gd1.console 1` and reboot, then `tools/serialsh.ps1 -Port COMx` and `tools/serialpush.ps1`. Useful when adb is broken.
- **Overlays** are RROs on `/vendor`. To test one, build `<pkg>__lineage_gd1__auto_generated_rro_vendor` and push it.

## 8. Known gaps before a public release

From [TODO.md](TODO.md), the ones that matter most:
- SELinux is permissive; several services still run in the `init` domain.
- The touch controller has no screen-off suspend (battery).
- Bluetooth and audio routing are unchecked; video recording is untested.
- 3G data and a GPS fix are unverified.
- Test-only logging and the bring-up script must be off in release builds.
- The security patch level is frozen at 2025-03-01.
