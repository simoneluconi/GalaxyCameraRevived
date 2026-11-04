# Galaxy Camera Revived

A custom ROM that brings Android 14 (LineageOS 21) to the **Samsung Galaxy Camera**, the 2012 Android camera with a 21x optical zoom.

| Model | Support |
|---|---|
| Galaxy Camera **EK-GC100** (3G, 2012) | Working, see below |
| Galaxy Camera 2 **EK-GC200** (Wi-Fi, 2013) | Planned. It shares almost all of its hardware with the GC100, and the build is organised so that it can reuse the GC100's work; see [docs/GC200.md](docs/GC200.md). |
| Other variants (GC110, GC120) | Not supported |

Everything below describes the GC100 unless it says otherwise.

Samsung stopped at Android 4.1.2. The camera still takes good pictures, but apps no longer install, nothing syncs, and half of the stock services are dead. This project puts a modern, lightweight Android on it and keeps what makes it a camera: the zoom lever, the shutter button, manual exposure, optical stabilisation and the pop-up flash. It also adds something Samsung never offered: **RAW photos (DNG)**.

It is built camera-first. There are no Google services and no phone apps, only what a camera needs, because the device has only about 750 MB of usable RAM.

> **Status: working, not yet released.** The author uses it every day, but it is still under development and there are no public builds yet. See [Downloads](#downloads).

## What works

| Area | Status |
|---|---|
| Boot, screen, touch, landscape UI | Works |
| Camera: viewfinder, autofocus, 16 MP JPEG | Works |
| 21x optical zoom with the zoom lever | Works |
| Half-press focus, full-press shutter | Works |
| P / A / S / M exposure modes | Works |
| Optical image stabilisation (OIS) | Works |
| Pop-up flash, self-timer lamp, AF assist lamp | Works |
| RAW (DNG) next to the JPEG | Works (colour profile still being refined) |
| Exposure and HDR bracketing (3 frames) | Works (the HDR merge itself is not written yet) |
| Lens retracts when the camera closes or reboots | Works |
| microSD (FAT32, exFAT) | Works |
| Rotation sensor, level, compass | Works (compass not checked against a real heading) |
| Wi-Fi | 2.4 GHz works; 5 GHz does not connect yet |
| GPS | Starts and sees satellite status; a first fix has not been seen yet |
| 3G mobile data | Works (tested with an Italian SIM) |
| SMS | Receiving works; no voice calls (no dialer, by design) |
| Audio | Works |
| Bluetooth | Not checked yet |
| Video recording | Not tested yet |
| Standby battery drain | About 1%/hour with Wi-Fi on; being improved |
| Security | SELinux is permissive and the security patch level is from early 2025 |
| Updates | By re-flashing; there is no over-the-air update |

The full list of open items is in [docs/TODO.md](docs/TODO.md).

## Features for photographers

- **The zoom lever zooms.** Slow and fast stages, as on the stock firmware, driving the 21x optical zoom (23–483 mm equivalent, f/2.8–5.9).
- **A real shutter button.** Half press to focus, full press to shoot. A tap on the screen sets the focus point; centre, multi-area and face AF are in the Fn menu.
- **P, A, S and M.** Set aperture, shutter speed (16 s to 1/2000 s) and ISO (100–3200). The camera shows the exposure it plans to use, and M mode has an exposure meter.
- **RAW (DNG).** Every shot can save a DNG next to the JPEG. The DNG is the sensor's own data, before Samsung's heavy noise reduction and sharpening. It opens in Lightroom, Camera Raw and RawTherapee, among others.
- **A lens profile for the GC100.** With it, Lightroom and RawTherapee correct the lens's barrel distortion and corner shading in the DNGs. See [Downloads](#downloads).
- **OIS on or off**, metering (matrix, centre, spot), white balance presets, self-timer with the front lamp, and 3-frame exposure bracketing.
- **The pop-up flash works as a switch.** Raise it to use the flash, close it to turn the flash off.
- **Full EXIF.** Shutter, aperture, ISO, focal length (and 35 mm equivalent), GPS when available, orientation, artist and copyright.
- **Photos go to the microSD card** by default, in `DCIM/GC100/`.

It ships with two apps of its own:
- **GC Camera**: the camera app, built for the physical controls. [More](camera-app/README.md).
- **GC Gallery**: a fast gallery for the camera's photos. It pairs each JPEG with its DNG and shows the exposure info, a histogram and star ratings. It is new and still being tested. [More](docs/GALLERY-APP.md).

Planned: a faster HDR, the camera as the home screen, instant-on standby, and a build for the Galaxy Camera 2 (EK-GC200).

## Install

**Read [docs/INSTALL.md](docs/INSTALL.md) before you start.** It has every step, and how to go back to Samsung's firmware.

In short:
1. **You need**: an EK-GC100 (the Galaxy Camera 2 is not supported yet; the GC110 and GC120 are not supported), a Windows PC, Odin 3.10.7, Samsung's USB drivers, a good USB cable, and the battery at 50% or more.
2. **Download Samsung's official firmware** for your camera first, and keep it. With it, Odin can always bring the camera back to stock.
3. **Install the mini recovery** with Odin, then **back up** the camera's own partitions (IMEI, Wi-Fi and Bluetooth addresses, calibration). Without that backup, a damaged EFS partition cannot be repaired.
4. **Flash the build** with Odin, then do a factory reset from the mini recovery.

**Warnings**
- Flashing a custom system always has some risk. You do it at your own risk.
- **Never flash a PIT file and never tick "Re-Partition" in Odin.**
- **Never flash a bootloader** (`sboot.bin`, `tz.img`) or anything to the PARAM partition. A bad bootloader is the one thing Odin cannot fix.
- Don't use old custom recoveries or kernels from forums to wipe the camera. Some 2012-era kernels can permanently kill the internal memory (the Exynos "superbrick" bug).
- Never unplug the cable while Odin is writing.

## Downloads

**Builds**: coming via [GitHub Releases](https://github.com/simoneluconi/GalaxyCameraRevived/releases) once the first public build is ready.

**Lens profile**: [`profiles/Samsung EK-GC100.lcp`](profiles/Samsung%20EK-GC100.lcp), an Adobe lens profile for the GC100's zoom lens (measured on a GC100; the GC200 has the same lens, but needs its own check). It corrects barrel distortion at the wide end and evens out the corner shading. It is meant for **RAW (DNG) files only**: the camera's JPEGs are already corrected by the camera.

- **Lightroom Classic / Camera Raw (Photoshop)**:
  1. Copy the `.lcp` file to:
     - Windows: `%APPDATA%\Adobe\CameraRaw\LensProfiles\1.0\`
     - macOS: `~/Library/Application Support/Adobe/CameraRaw/LensProfiles/1.0/`
  2. Restart Lightroom or Photoshop.
  3. DNGs from GC Camera turn the profile on by themselves. For other DNGs: Develop → Lens Corrections → Profile → Enable Profile Corrections, Make "Samsung", Model "Samsung 4.1-86.1mm f/2.8-5.9".
- **RawTherapee**: in the Transform tab, open "Profiled Lens Correction", choose "LCP file" and pick the `.lcp`. At the wide end, crop the dark corners by hand: RawTherapee does not read the crop that GC Camera stores in the DNG.

How it was measured, and its limits: [docs/LENS-PROFILE.md](docs/LENS-PROFILE.md).

## For developers

- [docs/TECHNICAL.md](docs/TECHNICAL.md): architecture, the M9MO image processor, the RAW pipeline, the source trees and how to build.
- [docs/CAMERA-APP.md](docs/CAMERA-APP.md): the camera HAL's parameters and the camera app's design.
- [docs/RECOVERY.md](docs/RECOVERY.md): the mini recovery, backups and restore.
- [docs/TODO.md](docs/TODO.md): known issues and workarounds.
- [docs/GC200.md](docs/GC200.md): the plan for the Galaxy Camera 2: what it shares with the GC100 and what a GC200 build still needs.

## Credits

This port stands on the work of many people:
- **[html6405](https://github.com/html6405)**: the LineageOS 21 trees for Exynos 4412 devices (smdk4412-common, the kernel, hardware/samsung, the i9300 device tree, the HAL1 camera framework branches). This project would not exist without them.
- **[LineageOS-UL](https://github.com/LineageOS-UL)** ("LineageOS for Ultra Legacy devices"): the Android 14 base patched for old kernels and hardware.
- **Paul Kocialkowski and the [Replicant](https://replicant.us) project**: the free `exynos_camera` HAL and the sensors HAL that our camera and sensor support are built on.
- **treammc and tubbohere**: the LineageOS kernel bring-up for the Galaxy Camera GC110 (gd1wifi), with the board files and panel fixes.
- **[FoxesInLove/RawGalaxyCamera](https://github.com/FoxesInLove/RawGalaxyCamera)**: the earlier effort to get RAW out of the GC100.
- **[adamoutler](https://github.com/adamoutler/EK-GC100_Galaxy_Camera)**: Samsung's original GC100 kernel source, published on GitHub.
- **LineageOS, AOSP and CyanogenMod contributors**, StarKissed (the 2013 GC100 recovery whose approach the mini recovery follows), and **libjpeg-turbo**.

## Licences

| Part | Licence |
|---|---|
| Kernel (fork of html6405's smdk4412 kernel, including Samsung's `m9mo.c`) | GPL-2.0 |
| Camera HAL (based on Replicant's `exynos_camera`) and sensors HAL | GPL-3.0-or-later |
| Rest of the device tree (based on LineageOS / html6405) | Apache-2.0 |
| libjpeg-turbo (`camera-app/third_party/libjpeg-turbo`) | IJG / BSD-3-Clause / zlib, see its `LICENSE.md` |
| GC Camera, GC Gallery and their shared modules (`camera-app/app`, `gallery`, `gccore`, `common`), and the planned PC tool built on gccore | GPL-3.0-or-later ([text](LICENSES/GPL-3.0-or-later.txt)), so that GPL-3 code from RawTherapee and darktable can be ported into them |
| Tools, docs and the lens profile | To be confirmed before the first release |

Samsung's proprietary files (firmware, drivers, libraries) are **not** in these repositories. They are extracted from the official firmware when the system is built.

This project is not affiliated with Samsung. "Samsung" and "Galaxy" are trademarks of Samsung Electronics.
