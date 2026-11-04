# android_device_samsung_gd1

<!-- Draft for the device/samsung/gd1 repository. Check the folder list against the tree
     after the current restructuring (common.mk / BoardConfigCommon.mk) before publishing. -->

LineageOS 21 (Android 14) device tree for the **Samsung Galaxy Camera EK-GC100** (`gd1`), on top of LineageOS-UL and html6405's Exynos 4412 trees.

Project home, install guide and docs: https://github.com/simoneluconi/GalaxyCameraRevived

## What is here

| Folder | What it is |
|---|---|
| `camera/` | The camera HAL (HAL1): Replicant's `exynos_camera` with an M9MO preset. Optical zoom, AF, P/A/S/M, OIS, flash, brackets and RAW. The parameters are documented in the main repo's `docs/CAMERA-APP.md`. |
| `libsensors/` | Sensors HAL 1.0 for the LSM330DLC (accelerometer, gyroscope) and the AK8963C compass, rotated into the landscape frame. |
| `keylayout/` | `gpio-keys.kl`: shutter half/full press (FOCUS / CAMERA) and the zoom lever (ZOOM_IN / ZOOM_OUT, slow and fast). |
| `shims/` | `libgps-shim` (GNSS HAL wrapper) and `libgpsd_shim` (stubs the Android 4.1 sensor API that the stock BCM4752 daemon links). |
| `sepolicy/` | Vendor policy for our daemons (cbd, gpsd, GNSS). SELinux is still permissive. |
| `legacy/health/` | The AOSP health@2.0 HAL and libraries, which LineageOS-UL removed but the exynos4 HAL needs. |
| `overlay/`, `rootdir/`, `configs/`, `init/`, `system_ext/` | Overlays (no voice or SMS, no doze, landscape), fstab and init scripts, audio and GPS configs, first-boot setup. |
| `RemovePackages/` | The camera-first debloat: phone, media and store apps overridden. |
| `app/GcGallery/` | GC Gallery as a prebuilt APK (built from the main repo's `camera-app/`). |
| `libstlport/` | A prebuilt `libstlport.so` that the proprietary blobs link. **Move it to the extracted vendor before publishing**: it comes from the i9300 vendor blobs. |
| `proprietary-files.txt`, `extract-files.sh`, `setup-makefiles.sh` | The blob list. No blob is committed. |

## Building

Full instructions are in the main repo's [docs/TECHNICAL.md](https://github.com/simoneluconi/GalaxyCameraRevived/blob/main/docs/TECHNICAL.md). In short:

```
repo init -u https://github.com/LineageOS-UL/android.git -b lineage-21.0 \
    -g default,-qcom,-device,-mips,-darwin,-notdefault --depth=1
# local manifests from the main repo: manifests/gd1.xml, manifests/zz-lineage-snapshot.xml
repo sync -c -j8
# blobs: see "Blobs" below
source build/envsetup.sh
lunch lineage_gd1-ap2a-userdebug
m droid
```

`/vendor` is flashed to the stock HIDDEN partition (`vendor.img` → `hidden.img`). The build has no recovery (`TARGET_NO_RECOVERY`): the RECOVERY partition holds the project's mini recovery.

## Blobs

Proprietary files are never committed. `extract-files.sh` takes them from a blob source made by the main repo's `tools/mk-blobsrc.sh`:
- the GC100's own files from the stock GC100XXBMC4 `/system` (Wi-Fi NVRAM and firmware, Bluetooth patchram, GPS daemon and HAL, ...);
- the common Exynos 4412 files from html6405's public i9300 LineageOS 21 build (Mali, MobiCore, the XMM6262 `cbd` and RIL).

## Safety

This tree is set up so that a build never writes the bootloader's PARAM partition (`SMDK4412_NO_PARAM_SCRIPT`), and the kernel never writes the camera ISP or touch firmware. Keep it that way. The anti-brick rules are in the main repo's `docs/INSTALL.md`.

## Licence

Apache-2.0 (`LICENSE`), except:
- `camera/`: GPL-3.0-or-later (from Replicant's `exynos_camera`, see `camera/NOTICE`);
- `libsensors/`: GPL-3.0-or-later (from Replicant), except `libsensors/akmdfs/` (AKM's library, Apache-2.0, see its `NOTICE`).

## Credits

html6405 (smdk4412-common, i9300, kernel, hardware/samsung), LineageOS-UL, Paul Kocialkowski / Replicant, treammc and tubbohere (GC110 bring-up), and the LineageOS and CyanogenMod contributors.
