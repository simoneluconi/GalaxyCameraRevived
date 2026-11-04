# android_kernel_samsung_smdk4412 (branch `gd1`)

<!-- Draft for the kernel fork. Put it in the gd1 branch as README.gd1.md (or similar), so
     the upstream README stays untouched. -->

html6405's Linux 3.4 kernel for Exynos 4412 devices (`lineage-21.0-BPF`), with the changes for the **Samsung Galaxy Camera EK-GC100** (`gd1`). Defconfig: `lineageos_gd1_defconfig`.

Project home and docs: https://github.com/simoneluconi/GalaxyCameraRevived

## What the gd1 branch adds

- **Board**: `lineageos_gd1_defconfig`, the s6d6aa1 panel timings, build fixes for the GC1 board drivers, vmalloc 256 MB, CPU hotplug with pegasusq.
- **Camera (M9MO ISP, `drivers/media/video/m9mo.c`)**:
  - ISP firmware writes, firmware dump, factory/calibration controls and debug writes are refused, and FIMC refuses the matching ioctls;
  - the lens retracts on close, reboot and power off (polling lens-off, as in Samsung's final GC100 driver), and a killed process no longer cuts the ISP short;
  - AE bracket and HDR captures, the OIS capture mode per drive mode, the self-timer lamp, and LSC/LDC written in parameter mode.
- **Input**: the pop-up flash switch (`SW_STROBE_INSERT`, sysfs `strobe_insert`), re-read on resume; the Melfas touch firmware is never flashed.
- **Storage**: FAT `time_offset=` mount option (Android 14's vold needs it), high speed on SD 3.0 cards without UHS-I, 512 KB read-ahead for SD cards, and a dcache stopgap that warns instead of BUG() on a dentry still in use at unmount.
- **Other**: the AN30259A LED driver is disabled (the chip is not on the GC100 and it panicked).

## Safety

Do not re-enable the M9MO firmware update paths or the touch firmware update. A failed write kills the camera module or the touch controller.

The eMMC "superbrick" protection here comes only from the per-chip quirks in `drivers/mmc/card/block.c`. Check the eMMC model before any wipe from a kernel you did not build from this tree.

## Licence

GPL-2.0, as the Linux kernel.
