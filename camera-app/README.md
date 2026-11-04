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

To be decided before publishing (GPL/Apache compatible, see the project's CLAUDE.md).
