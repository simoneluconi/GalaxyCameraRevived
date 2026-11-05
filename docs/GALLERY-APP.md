# GC Gallery: the photo gallery for the Galaxy Camera EK-GC100

A photography-first gallery that goes with GC Camera and also opens any picture. It is a second APK in the `camera-app/` Gradle project (module `:gallery`, package `com.simoneluconi.gcgallery`), sharing the theme with the camera through `:common`.

**Licence**: GPL-3.0-or-later for GC Gallery (`gallery/`) and the shared `common/` module, the same as GC Camera and gccore (user, 2026-11-04; text in `LICENSES/GPL-3.0-or-later.txt`). Files carry `SPDX-License-Identifier: GPL-3.0-or-later` headers as they are touched.

## 1. What it does

- **Storage first** (user, 2026-11-04: "focus on the camera"): the gallery shows one storage at a time.
  - It opens on the **SD** card (where GC Camera saves) whenever one is mounted, and on internal storage otherwise.
  - With a card in, an "SD | Internal" switch at the start of the bar steps out to internal storage. The user asked for "SD", not "microSD", and not "Phone": it is a camera.
  - Picking Internal sticks for that session. A fresh start goes back to the card.
  - A card pulled out while the gallery is open switches to Internal with a message.
  - Scope = volume: `external_primary` is internal; every other volume counts as the card (`MediaIndex.Scope`).
- **Compact viewer bar** (user, 2026-11-04):
  - one star button (outlined, or filled with the count) that opens the five stars in a popup;
  - one rotate button that opens left / right / flip in a popup, which stays open for repeated turns.
- **Grid**: day sections, square centre-cropped cells, and RAW, DNG and star badges.
  - Albums are chips within the chosen storage: the camera folder (`DCIM/GC100/`) first, then All, Rated, the other folders, and Trash.
  - Pinch or the zoom lever changes the column count (3–10).
  - A fast scroller on the right shows the date.
- **Pairs**: `GC100_0042.jpg` and `GC100_0042.dng` in the same folder are one item with a RAW badge. Trash and rotate act on both; "RAW" in the selection bar trashes only the DNGs.
- **Selection**: a long press starts it, dragging extends it (with auto-scroll), and tapping a day header selects the whole day. Actions: rate, rotate, share, RAW to trash, trash; in the Trash: restore and delete forever.
- **Viewer**:
  - swipe between photos;
  - double tap or pinch for 100% (up to 300%), with a full-resolution decode of the visible area;
  - the info panel on the right (below the photo when held vertically), with an RGB/luma histogram and blinking clipped highlights;
  - rotate left/right, flip, stars, share, trash.
- **Info panel**: exposure (shutter, f, ISO, bias, EV100, PASM, metering, WB, flash), lens (focal length, 35 mm equivalent, zoom factor from LensSpecification), GC Camera's XMP `gc:` fields, location (decimal and DMS, altitude, accuracy, compass direction, GPS time, "open in maps", copy), file details, and for a pair the DNG facts (sensor size, CFA, black/white levels, active area).
- **Keys**:
  - zoom lever: in the grid it sets the columns; in the viewer it zooms. The fast stage toggles fit/100%, and wide at fit goes back to the grid.
  - Half or full shutter press opens the camera (`STILL_IMAGE_CAMERA`).
- **Intents**: `MAIN/LAUNCHER` + `APP_GALLERY`, `ACTION_VIEW image/*`, and `android.provider.action.REVIEW(_SECURE)`.
  - GC Camera's last-shot button sends REVIEW with the JPEG's MediaStore uri; the viewer opens on that shot inside its folder.
  - A picture from another app opens alone, view and share only.
- **Rotation** (user, 2026-11-05): the gallery follows the device into all four positions (`fullUser`), unlike GC Camera, which stays landscape and turns its controls in place.
  - The activities handle the change themselves (`configChanges`), so the selection, the open photo and the info panel survive a turn.
  - Held vertically:
    - the edit actions move to a bar at the bottom: the viewer's stars, rotate, share and trash, and the grid's selection actions;
    - the viewer's info panel opens below the photo, above that bar;
    - the grid has its own column count (4 by default, against 6 held level);
    - the storage switch shows icon and count only.
- **Theme**: light/dark follows the system. Same colour tokens as GC Camera, plus `scrim` and `rating`. Floating translucent bars, with no blur (Mali-400).

- **Later: the Galaxy Camera 2 (EK-GC200) too** (user, 2026-11-04). Audit of 2026-11-04 for GC100-only assumptions:
  - **Fixed**: the camera folder is any `DCIM/GC<3 digits>/` (`MediaIndex.isCameraFolder`), and the default album is "the camera folder, whichever it is" (`MediaIndex.CAMERA`).
  - **Already generic**:
    - no Build.DEVICE/MODEL checks and no EXIF Make/Model filters;
    - storage by volume (`external_primary` vs removable), never by a `/storage/<id>` path;
    - the zoom factor comes from LensSpecification;
    - JPEG/DNG pairing goes by base name;
    - the RAM budgets scale: thumbnails are 1/8 of `maxMemory`, and the screen decode follows the display size. On 2 GB the heap is larger, so the cache is too.
  - **Open**: the zoom lever's fast stage is recognised by scan code 545/546 (`Keys.isFast`). The GC200's keylayout must keep those scan codes, or the gallery needs another way to tell fast from slow (e.g. a key repeat-rate heuristic). Half press and full press go by keycode (FOCUS/CAMERA), so they are fine if the keylayout maps them.
  - **Open**: GC Camera's folder name is a setting; a folder renamed outside `GC\d{3}` loses its camera-folder place (it shows as a plain album).

## 2. Constraints and choices

- **Performance**:
  - **No AndroidX** besides `exifinterface`: no RecyclerView, coroutines or image loader.
  - The grid is one Canvas view (`ThumbGridView`), and threads are plain executors.
- **Thumbnails** (`thumbs/ThumbLoader`):
  - memory LRU (RGB_565, 1/8 of the heap), plus a disk cache `cacheDir/thumbs/<id>_<date_modified>.jpg` (short side 256, q85);
  - 2 background workers, with the visible cells first, then one screen ahead and half a screen behind;
  - when nothing is wanted, one worker warms the disk cache for the rest of the storage, newest first;
  - sources, in order: the EXIF thumbnail, the DNG's embedded JPEG, BitmapFactory 1/8 (libjpeg-turbo DCT scaling), and ImageDecoder for other formats.
  - **JPEGs are read into memory with one pread, then decoded from the bytes.** Over FUSE (no passthrough on kernel 3.4) BitmapFactory's small reads each go through MediaProvider. Measured 2026-11-04, 16 MP from the SD: 1.6 s per cold thumbnail before, ~0.95 s after (read ~0.3 s + 1/8 decode ~0.5 s).
- **Build** (in gd1 since 8683bb4, `PRODUCT_PACKAGES += GcGallery` in the "Camera-first app set" block of gd1.mk):
  - `device/samsung/gd1/app/GcGallery` is an `android_app_import`, `presigned` + `preprocessed`. The build requires both for a v2-signed APK with targetSdk ≥ 30, and uses it exactly as is, so it must stay zipaligned, as Gradle's release output is.
  - A default-permissions XML grants READ_MEDIA_IMAGES and ACCESS_MEDIA_LOCATION. MANAGE_MEDIA is `preinstalled`, so the system copy holds it.
  - Trebuchet's home layout overlay puts GC Gallery on the bottom row.
  - Refresh the APK with `tools/update-gallery-prebuilt.sh` (WSL) after `:gallery:assembleRelease`, then commit `app/GcGallery/GcGallery.apk` in the gd1 repo.
- **Viewer** (`ui/ViewerActivity`, `ui/widget/PhotoPagerView`):
  - the cached thumbnail shows first, then a screen-size decode, ARGB_8888 (a 16 MP JPEG at 1/4 is 1152x864, 4 MB);
  - the current photo ±1 stay decoded;
  - past the screen decode, `BitmapRegionDecoder` decodes the visible region at the matching sample (`image/Decode.RegionSource`).
- **Orientation**: always drawn through the EXIF orientation matrix (`image/Orientation`); bitmaps are not rotated, except thumbnails.
- **Edits are lossless and in place** (`edit/Edits`):
  - Rotation patches the 2-byte Orientation SHORT in IFD0 of the JPEG's EXIF and of the DNG.
  - Stars patch `xmp:Rating` inside the XMP packet, using its padding (`image/Xmp`). A JPEG without a packet is rewritten once with a padded one. A DNG without room keeps the rating only in `RatingStore`.
  - `IS_FAVORITE` follows rating ≥ 1 when MANAGE_MEDIA is granted.
- **Permissions**:
  - `READ_MEDIA_IMAGES`;
  - `ACCESS_MEDIA_LOCATION`, without which MediaProvider blanks other apps' GPS tags (the info read uses `setRequireOriginal`);
  - `MANAGE_MEDIA`, a special app-op. With it, MediaStore's write, trash, delete and favourite requests complete without a dialog. Grant it with `appops set com.simoneluconi.gcgallery MANAGE_MEDIA allow` or in Settings; the gallery hints once.
- **Trash**: MediaStore `IS_TRASHED` (`createTrashRequest`), with Undo after each trash. MediaProvider deletes trashed items after 30 days.

## 3. What GC Camera writes for the gallery (agreed with the camera session, 2026-11-04)

1. A ~320x240 EXIF thumbnail in each JPEG.
2. The last-shot button: `android.provider.action.REVIEW` + `FLAG_GRANT_READ_URI_PERMISSION`.
3. In the DNG: Orientation always present, and an XMP packet (tag 700) with `xmp:Rating="0"` and ~2 KB of `<?xpacket` padding. The JPEG's XMP packet gets the same rating and padding.
4. GPS altitude, `GPSHPositioningError` and `GPSImgDirection`/`Ref` (magnetic; the compass is not calibrated yet).
5. XMP namespace `gc` = `https://github.com/simoneluconi/GalaxyCameraRevived/ns/gc/1.0/`. Fields:
   - `Pasm` (auto|program|aperture|shutter|manual), `OIS` (True|False);
   - `AFMode` (auto|macro|infinity), `AFArea` (center|multi|face|touch);
   - `Drive` (single|aeb|hdr), `BracketEV`, `BracketFrame`, `BracketCount`, `Timer`;
   - `FlashPopup` (up|down), `ZoomStep`, `ZoomMax`.
6. ~~RAW development on the device~~: dropped by the user, 2026-11-04. After a day of work the "Advanced" development only matched the ISP JPEG at ISO 160 and lost at ISO 400. So:
   - the JPEG is always the ISP's;
   - the DNG stays as it is, for editing on a PC;
   - there is no `gc:Developed`/`gc:DevelopParams`, no Develop screen and no "restore".

## 4. Building and installing

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :gallery:assembleRelease
adb -s <serial> push gallery\build\outputs\apk\release\gallery-release.apk /data/local/tmp/gcgallery.apk
adb -s <serial> shell pm install -r /data/local/tmp/gcgallery.apk
adb -s <serial> shell pm grant com.simoneluconi.gcgallery android.permission.READ_MEDIA_IMAGES
adb -s <serial> shell pm grant com.simoneluconi.gcgallery android.permission.ACCESS_MEDIA_LOCATION
adb -s <serial> shell appops set com.simoneluconi.gcgallery MANAGE_MEDIA allow
```

`adb install` fails on this kernel (docs/CAMERA-APP.md §5). Logs are under the `GcGallery` tag: index time, each thumbnail's decode time, each screen and detail decode.

## 5. Code map (`camera-app/gallery/src/main/java/com/simoneluconi/gcgallery/`)

- `GalleryApp`: process singletons (`index`, `thumbs`, `ratings`).
- `data/`:
  - `MediaIndex`: one MediaStore query over all volumes, pairing, folders, ContentObserver;
  - `Photo`;
  - `RatingStore`.
- `thumbs/ThumbLoader`: memory and disk cache, workers.
- `image/`:
  - `Decode`: thumbnails, screen decode, DNG preview, `RegionSource`;
  - `Tiff`: `Fd` pread/pwrite, the TIFF reader, `JpegSegments`;
  - `Orientation`, `Xmp`.
- `edit/Edits`: in-place rotation and rating.
- `meta/PhotoInfo`: the info panel's sections.
- `ui/`:
  - `GridActivity`, `ViewerActivity`;
  - `MediaAccess` (MediaStore consent requests), `MessageBar`, `Keys`, `Layout` (portrait check, moving views between bars);
  - `widget/`: `ThumbGridView`, `PhotoPagerView`, `StarsView`.
- `:common` (`camera-app/common/`): colour tokens, `Ui.kt`, `HistogramView`, `panel_button_bg`.

## 6. Device test list (run after each full build and flash)

1. **First boot, no prompt**: open GC Gallery from the home row. No permission dialog; `dumpsys package com.simoneluconi.gcgallery` shows READ_MEDIA_IMAGES and ACCESS_MEDIA_LOCATION granted (from default-permissions).
2. **MANAGE_MEDIA as a preinstalled app**: `appops get com.simoneluconi.gcgallery MANAGE_MEDIA` (no adb grant), and a trash then needs no system dialog.
3. **Update on top of the system copy**: `pm install -r` of a newer release APK succeeds (same key), and `pm path` points to /data/app. After a factory reset it is the /system copy again.
4. **Storage**: it opens on SD → camera folder. The SD | Internal switch works; pulling the card switches to Internal with a message.
5. **Grid**:
   - cold thumbnail times in the log (`GcGallery: thumb …`), and warm ones instant after a restart;
   - pinch and zoom lever change the columns;
   - fast scroller, day headers;
   - drag selection.
6. **From GC Camera**: the last-shot button (REVIEW) opens that shot inside its folder; a shot taken a second ago also opens. Half press in the gallery goes back to the camera.
7. **ACTION_VIEW** of a JPEG from Files opens it alone: view and share only, and no rotate, rate or trash.
8. **RAW pairs**:
   - one cell with a RAW badge, and the info panel shows the RAW section;
   - rotate turns both files (exiftool Orientation on the pulled JPEG and DNG);
   - "RAW" in the selection bar trashes only the DNG.
9. **Trash and favourites on the SD**:
   - trash, then Undo; restore and delete forever from the Trash album;
   - a rating writes `xmp:Rating` in place (file size unchanged on GC Camera's padded files) and sets IS_FAVORITE.
10. **Viewer**:
    - swipe, double tap 100% (detail decode in the log), zoom lever, wide at fit goes back to the grid;
    - the info panel moves the photo below the bar, with GPS rows on a geotagged shot;
    - clipping blink, RGB histogram.
11. **Rotation**: turn the camera through all four positions in the grid (with a selection) and in the viewer (with the info panel open). The selection and the photo stay, the bars and panel move, and the star and rotate popups open above the bottom bar when held vertically. To force a position from adb: `settings put system accelerometer_rotation 0` and `user_rotation 0..3`, then restore `1`/`0`.
12. **Memory**: `dumpsys meminfo com.simoneluconi.gcgallery` after a fast scroll through every photo and 20 viewer swipes; no lmk kill of GC Camera.

## 7. Milestones

1. Done 2026-11-04 (verified on the device, §6): grid, viewer, info, keys, intents.
2. Done 2026-11-04 (verified on the device, §6): selection, trash, rotate/flip, stars, share, RAW to trash.
3. ~~Develop~~: dropped with on-device RAW development (§3.6). The gallery does not link gccore.
