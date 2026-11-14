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
- **TV (micro-HDMI, docs/HDMI.md)**: with a TV connected, the viewer's photo is on the TV, never the screen's contents.
  - The TV follows swipes (the photo the pager settles on), rotations and the sharper decodes; the grid and other apps leave hdmid's notice on the TV.
  - Zoomed in, the TV shows the part on screen (`PhotoPagerView.visibleRegion`): half-size frames while it moves (`onViewChanged` → `TvOut.live`), then full size and sharp once it settles (`onSettled`); back at fit, the whole photo.
  - A TV button appears in the top bar: the TV's name, the mode, and a list of resolutions (Auto, then every mode, marking the ones the TV does not list).
  - The screen stays on while a TV is connected (FLAG_KEEP_SCREEN_ON): showing photos must not end on the screen timeout.
- **Wi-Fi transfer** (user, 2026-11-14): the camera serves its photos to a browser on the same Wi-Fi. It is switched on by hand, from the grid's Wi-Fi button or the Quick Settings tile, never on its own, because of the battery.
  - **The code is the `:transfer` library** (`camera-app/transfer/`, its README), so another app can host it; GC Gallery is its host (`transfer/GalleryPhotoSource`, `GalleryApp` as `TransferHost`).
  - **Address**: `https://gc100.local:8443` (`gc200.local` on the GC200; the transfer answers mDNS for that name itself), with the IP as a fallback. The certificate is self-signed, made once per name and kept across networks. The browser warns once; after the click-through Chrome and Edge keep a secure context, so `showDirectoryPicker` works (checked in Chrome and Edge 157).
  - **Away from any Wi-Fi** (user, 2026-11-14): the camera makes its own network, a local-only hotspot (`startLocalOnlyHotspot`: no internet behind it, so the 3G is never shared).
    - The screen shows its name and password, a `WIFI:` QR code to join from a phone, and a QR of the page's address.
    - The server and mDNS use the hotspot's address. On the BCM4334 the hotspot replaces the client connection while it runs, and Wi-Fi comes back when the transfer stops.
    - The QR encoder is our own (`:transfer`'s `QrCode`, byte mode, level M, versions 1-10).
    - It needs NEARBY_WIFI_DEVICES (`neverForLocation`), granted by the ROM's default permissions; the app never asks.
  - **Access**: open on the local network by default. An optional password is set, changed or removed on the camera only, never from the page, so someone else on the Wi-Fi cannot lock the owner out. A browser types it once and keeps a 400-day cookie; changing the password signs every browser out. The password is stored as PBKDF2, the session tokens as SHA-256. A wrong password costs 1 s, and 10 misses lock logins for a minute.
  - **Page**: the photos by day from the gallery's own thumbnail cache, JPEG+DNG as one item, the storage switch, and a JPEG + RAW / JPEG / RAW choice kept in the browser.
    - "Copy new" writes into a folder picked once (its handle kept in IndexedDB), streaming each file into a `FileSystemWritableFileStream`, and skips what the folder already has by name and size.
    - A different photo with the same name gets a date-stamped name (UTC, so every computer picks the same one; `_2`, `_3` after it), never written over.
    - An interrupted copy can leave only an empty file at its name (the data goes to the browser's swap file); an empty file counts as free.
    - Selected photos can be copied or moved to the camera's trash (JPEG and DNG together; GC Gallery restores them). A click opens a photo; the tick, Ctrl/Shift-click, or any click once something is selected, selects.
    - **Viewer**, like the app's:
      - the photo full screen, the thumbnail first, then the original JPEG (a lone DNG shows its ISP JPEG);
      - arrows, keys and swipes; a click on the photo, the wheel or the − / + buttons zoom up to 100% and beyond, dragging pans, and a click on the dark band closes it (touch: pinch and double tap);
      - the info panel (the app's `PhotoInfo`, in the browser's language when the app has it), with an OpenStreetMap link for geotagged shots;
      - save into the folder (or download), and trash.
      - Back closes it. Stars and rotation are still to come: they write files, which needs the SYSTEM_GALLERY role too.
    - Firefox and Safari download one file at a time instead.
  - **Server**: a foreground service (`dataSync`).
    - It listens on the Wi-Fi (or hotspot) address only. It holds a partial wake lock, a Wi-Fi lock and a multicast lock while on.
    - It stops after 10 minutes without requests or bytes sent (a stalled copy counts as idle), when the Wi-Fi goes or its address changes, or from the notification, the tile or the screen.
    - POSTs need an `X-GC` header, so other sites cannot trash photos through the user's browser.
    - Only photos in the gallery's index are served.
  - **Trashing from the page needs the SYSTEM_GALLERY role** (gd1 overlay `config_systemGallery`): the service has no activity on screen to approve a MediaStore request.
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
- `tv/TvOut`: the TV through hdmid's socket: `TvState`, drawing at the mode's size (pixel aspect), the banded sharp decode.
- `meta/PhotoInfo`: the info panel's sections.
- `transfer/`: GC Gallery as the Wi-Fi transfer's host.
  - `GalleryPhotoSource`: the `PhotoSource` over MediaIndex, ThumbLoader, PhotoInfo and MediaTrash. `GalleryApp` is the `TransferHost`.
  - `TransferTile`: the tile under the name the ROM lists (`com.simoneluconi.gcgallery/.transfer.TransferTile`).
  - `MediaTrash`: MediaStore's trash with no activity on screen.
- `ui/`:
  - `GridActivity`, `ViewerActivity`;
  - `MediaAccess` (MediaStore consent requests), `MessageBar`, `Keys`, `Layout` (portrait check, moving views between bars);
  - `widget/`: `ThumbGridView`, `PhotoPagerView`, `StarsView`.
- `:common` (`camera-app/common/`): colour tokens, `Ui.kt`, `HistogramView`, `panel_button_bg`, `panel_bg`, `chip_bg`, the `TextButton` style.
- `:transfer` (`camera-app/transfer/`, its README): the Wi-Fi transfer as a library any app can host. Service, server, certificate, password, mDNS, hotspot, QR encoder, tile base class, screen and page.

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
13. **TV** (a TV or capture card on the micro-HDMI port):
    - the notice with the gallery closed; the viewer's photo once it opens, centred, upright, with black bars;
    - swipes, a rotation and a 1080p sharp decode reach the TV (`GcGallery: TV …` in the log);
    - zoom in with the lever and pan: the TV shows the same part (`(zoomed)` in the log), then sharp;
    - the TV button's list: Auto and a chosen mode switch the output, and the photo comes back at the new size;
    - opening GC Camera from the viewer puts the camera's live view on the TV; `adb shell cat /sys/kernel/debug/ion/exynos_contig_heap` then shows no hdmid buffer (the live view reads the camera's own FIMC memory). Back in the viewer, the photo returns.

14. **Wi-Fi transfer** (the camera and a PC on the same Wi-Fi):
    - start it from the grid's Wi-Fi button and from the Quick Settings tile with the gallery not running (the shade open; nothing opens on screen), and stop it from the tile and the notification;
    - from the PC: `ping gc100.local`, then `https://gc100.local:8443` in Chrome or Edge, accepting the certificate once;
    - choose a folder and "Copy new": the files arrive identical (sha256 against `adb shell sha256sum` of the originals), and a second run copies nothing; the same per kind (JPEG + RAW, JPEG, RAW);
    - a password set on the camera: the page asks once, then not again after a reload; changing it asks again;
    - move a photo to the trash from the page: it is in GC Gallery's Trash with its DNG, and `GcGallery: trash: N files directly` is in the log;
    - with the camera on no Wi-Fi: the start makes the hotspot, a phone joins it from the QR (or a second camera with `cmd wifi connect-network <ssid> wpa2 <pass>`), `gc100.local` resolves there, the page works, and after the stop the hotspot is gone;
    - open a photo: it sharpens after the thumbnail; arrows and swipes move, a double click gives 100%, the info panel matches the app's, and Back closes it;
    - left alone it stops after 10 minutes; afterwards `dumpsys power` shows no `GcGallery:transfer` wake lock.

## 7. Milestones

1. Done 2026-11-04 (verified on the device, §6): grid, viewer, info, keys, intents.
2. Done 2026-11-04 (verified on the device, §6): selection, trash, rotate/flip, stars, share, RAW to trash.
3. ~~Develop~~: dropped with on-device RAW development (§3.6). The gallery does not link gccore.
