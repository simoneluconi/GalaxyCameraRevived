# GC Camera

A camera app for the Samsung Galaxy Camera EK-GC100 running LineageOS 21. It drives the M9MO ISP through the camera HAL in `device/samsung/gd1/camera`:
- the 21x optical zoom from the physical lever;
- half-press focus and full-press shutter;
- P/A/S/M exposure with an exposure meter;
- OIS and the pop-up flash.

The design brief and the HAL contract are in [`../docs/CAMERA-APP.md`](../docs/CAMERA-APP.md).

## Build

Builds with Android Studio's JDK:

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

## Install

`adb install` fails on the device's 3.4 kernel, so push the APK and install it from the shell:

```
adb -s <serial> push app\build\outputs\apk\release\app-release.apk /data/local/tmp/gccamera.apk
adb -s <serial> shell pm install -r /data/local/tmp/gccamera.apk
```

## Licence

GC Camera (`app/`) and gccore (`gccore/`) are licensed under the GNU General Public License, version 3 or (at your option) any later version: see [`../LICENSES/GPL-3.0-or-later.txt`](../LICENSES/GPL-3.0-or-later.txt). Files ported from other GPL projects keep their original copyright notice.

GC Gallery (`gallery/`) and the shared theme module (`common/`) are still to be confirmed. libjpeg-turbo (`third_party/libjpeg-turbo`) keeps its own licence (`LICENSE.md` there).
