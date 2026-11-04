# Installing Galaxy Camera Revived (and going back to stock)

This guide is for a careful person who has never flashed a phone before. Read it once from start to end before touching the camera. Every step says what can go wrong.

The technical background is in [RECOVERY.md](RECOVERY.md) (the mini recovery, backups, restore) and [TECHNICAL.md](TECHNICAL.md).

## 1. The safety rules, in plain words

The camera can be brought back to Samsung's firmware with Odin in almost every case. These rules cover the few cases where it cannot.

1. **Never touch the bootloader.** Never flash `sboot.bin` or `tz.img`, and nothing that writes the PARAM partition. If the bootloader breaks, the camera cannot even reach download mode, and only a JTAG repair shop can save it. Our packages never contain these files.
2. **Never re-partition.** In Odin, never load a PIT file and never tick "Re-Partition". Our build fits the camera's original partitions.
3. **Back up the camera's identity first.** The EFS partition holds the IMEI, the Wi-Fi and Bluetooth addresses and factory calibration. It is unique to your camera and is not in Samsung's firmware. Step 5 saves it. Keep that backup private: it contains your IMEI.
4. **Don't wipe with old tools.** Some old Exynos kernels, recoveries and "wipe" tools from 2012–2013 forums can kill the internal memory for good when they erase it (the "superbrick" bug). Wipe only with our mini recovery, which never sends an erase command.
5. **Never update the camera module's firmware.** The lens and image processor have their own firmware inside the module. Our system never writes it, and you should never use tools that do. A failed update there kills the camera function for good.
6. **Power and cable.** Battery at 50% or more. A known-good data cable, plugged into a USB port on the back of the PC (not a hub). **Never unplug while Odin is writing.**
7. **The lens.** The lens retracts when the camera app closes or the camera reboots. If you ever find it out with the camera off (after pulling the battery, for example), switch on, open the camera app once and close it: the lens goes back in.

## 2. What you need

- A Samsung Galaxy Camera **EK-GC100**. Check Settings → About device → Model number. The GC110 (Wi-Fi only), GC120 and the Galaxy Camera 2 (EK-GC200) are different devices: **do not flash this build on them.**
- A microSD card (the camera saves photos there).
- A Windows PC with:
  - [Samsung USB drivers for mobile phones](https://developer.samsung.com/android-usb-driver);
  - **Odin 3.10.7** (other versions may work but were not tested);
  - Android's [platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`).
- **Samsung's official firmware for your camera**, downloaded before you start, for example from SamMobile. The project was tested with `GC100XXBMC4` (Android 4.1.2, the last official release). Check its MD5 after downloading, unpack it, and keep it: this is your way back.
- Our release files (see the [Releases page](https://github.com/simoneluconi/GalaxyCameraRevived/releases)):
  - the **mini recovery** as an Odin package (`minirecovery-gd1.tar.md5`);
  - the **system** as an Odin package (`lineage-gd1-<date>-odin.tar.md5`).

> The mini recovery is built from Samsung's own recovery kernel. If a release cannot include it, the release notes say how to build it from your own firmware download with `tools/build-minirecovery.sh` ([RECOVERY.md](RECOVERY.md)).

## 3. Learn the key combinations (no flashing yet)

Practise these on the stock firmware first. Start each one with the camera **switched off**.

| Mode | Keys | Leave it |
|---|---|---|
| **Download mode** (for Odin) | Hold the **zoom lever towards W ("−")** + **Power**, and keep holding past the logo | Hold Power until it switches off, or pull the battery |
| **Recovery** | Hold the **zoom lever towards T ("+")** + **Power**, until the recovery screen appears | `adb reboot`, or pull the battery |

In download mode the screen says `ODIN MODE` and `PRODUCT NAME: EK-GC100...`. If the product name is different, stop: this build is not for your camera.

The battery is removable. Pulling it is always a safe way to switch off, **except while Odin is writing**.

## 4. Check the internal memory chip

This takes one minute and protects against the superbrick bug.

1. On the stock firmware, enable USB debugging: Settings → About device → tap "Build number" 7 times, then Settings → Developer options → USB debugging.
2. Connect the camera and accept the prompt on its screen.
3. On the PC:
   ```
   adb shell cat /sys/class/mmc_host/mmc0/mmc0:0001/name
   adb shell cat /sys/class/mmc_host/mmc0/mmc0:0001/fwrev
   ```

If stock answers "Permission denied", run the same two commands in the mini recovery (section 5, after step 6) instead: nothing before section 6 erases anything.

The project's camera has `M8G1GC`, which is not one of the chips known to be affected. If yours says **M8G2FA, MAG4FA, MBG8FA, MCGAFA, VAL00M, VYL00M, KYL00M or VZL00M**, stop and open an issue on GitHub before going further.

## 5. Install the mini recovery and back up

The mini recovery replaces only the RECOVERY partition. Stock Android keeps working normally after this step.

1. Enter **download mode** (W + Power) and connect the USB cable.
2. Start Odin. It should show an "Added!" message and a COM port.
3. Options: tick only **Auto Reboot** and **F. Reset Time**. Leave **Re-Partition unticked** and the PIT tab empty.
4. Click **AP** and choose `minirecovery-gd1.tar.md5`. Wait for Odin to check the MD5.
5. Click **Start**. Wait for **PASS** (a few seconds). The camera reboots into stock Android.
6. Enter the mini recovery: `adb reboot recovery`, or switch off and use T + Power. The screen shows the mini recovery status page.
7. Run the backup:
   ```
   adb shell su -c backup-critical
   adb pull /tmp/backup camera-backup
   ```
   It saves `EFS.img`, `PARAM.img`, `BOOT.img`, `RECOVERY.img`, `RADIO.img`, `OTA.img` and a read-only copy of the bootloader (`BOTA0.img`, `BOTA1.img`), with SHA-1 sums in `SHA1SUMS`.
8. Check the sums on the PC (in Git Bash or WSL: `cd camera-backup && sha1sum -c SHA1SUMS`). Every line must say `OK`.
9. **Keep `camera-backup` somewhere safe and private**, with a second copy. Never upload it anywhere: it contains your IMEI and MAC addresses.

The adb serial changes with the mode (the stock adbd and ours report different numbers). If you have several Android devices connected, check `adb devices` and pass `-s <serial>`.

## 6. Install the system

1. Battery at 50% or more.
2. Enter **download mode** (W + Power), connect, start Odin.
3. Options: only **Auto Reboot** and **F. Reset Time**. No PIT, no Re-Partition.
4. **AP**: `lineage-gd1-<date>-odin.tar.md5`. It contains only `boot.img`, `system.img` and `hidden.img` (our `/vendor`).
5. **Start**, and wait for **PASS**. The system is about 1.2 GB, so this takes a few minutes. Don't touch the cable.
6. The camera reboots. **Coming from stock, it needs a factory reset first**, because stock's user data does not work with Android 14:
   - when the screen goes dark after PASS, or if it sits on the logo, switch it off (hold Power or pull the battery);
   - enter the mini recovery with **T + Power**;
   - run `adb shell su -c "wipe-data --yes"`, then `adb reboot`.
7. The first boot takes several minutes (Android prepares its apps once). Then the home screen appears in landscape.

`wipe-data` does not erase the memory chip: it clears the start of the data and cache partitions, and Android formats them on the next boot.

## 7. After installing

- **Insert the microSD card.** GC Camera saves only to the card unless you allow internal storage in its settings.
- **GC Gallery is built in.** If the release lists GC Camera as a separate APK, install it like this (`adb install` does not work on this kernel):
  ```
  adb push gccamera.apk /data/local/tmp/gccamera.apk
  adb shell pm install -r /data/local/tmp/gccamera.apk
  ```
- **Wi-Fi**: use a 2.4 GHz network for now.
- Default time zone is Europe/Rome; change it in Settings → System → Date & time.

## 8. Updating to a newer build

Flash the new `lineage-gd1-<date>-odin.tar.md5` with Odin, as in section 6, steps 1–5. Your photos, apps and settings are kept, and no factory reset is needed between our builds unless the release notes say so.

Developers with a build tree can flash without Odin over adb: `tools/flash-adb.sh` ([RECOVERY.md](RECOVERY.md), "A whole build over adb").

## 9. Going back to stock, and fixing problems

### Back to Samsung's firmware

1. Enter **download mode** (W + Power). This works even when Android or the recovery no longer start.
2. In Odin, put Samsung's full firmware file (for example `GC100XXBMC4_..._HOME.tar.md5`) in **AP**. Options: only Auto Reboot and F. Reset Time. **No PIT, no Re-Partition.**
3. **Start**, wait for **PASS**.
4. If stock then loops on the boot logo, enter Samsung's recovery (T + Power) and choose "wipe data/factory reset".

The download mode screen will say `CUSTOM BINARY DOWNLOAD: Yes`. That is cosmetic: it changes nothing about using or restoring the camera.

### The new system does not start

- Stuck on the logo for more than 10 minutes on the first boot: switch off, enter the mini recovery (T + Power) and run `wipe-data --yes` as in section 6.
- Still stuck: flash the previous build, or stock, with Odin.

### No IMEI, or the wrong Wi-Fi address

The EFS partition is damaged. Restore it from **your own** backup (never one from another camera):

1. Enter the mini recovery.
2. `adb push camera-backup/EFS.img /tmp/EFS.img`
3. Check its SHA-1 against the `EFS.img` line of `camera-backup/SHA1SUMS` (`adb shell sha1sum /tmp/EFS.img`).
4. `adb shell su -c "flash-image --restore EFS /tmp/EFS.img"`, then `adb reboot`.

`flash-image` refuses an image that is not exactly the size of the partition, or not a valid EFS file system.

### The lens is stuck out

Switch on, open the camera app, wait for the viewfinder, then close it. The lens retracts. Never push it in by hand.

### Download mode does not come up

Pull the battery, put it back, and hold W + Power again, longer. A Samsung USB "jig" (301 kΩ on the micro-USB ID pin) forces download mode when no key combination works.
