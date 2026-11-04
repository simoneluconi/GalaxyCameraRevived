# Brief: the dedicated camera app for the Galaxy Camera EK-GC100

This is the starting brief for building our own camera app. It replaces OpenCamera, which is only the bring-up app. It covers what the app must do, what the platform and the camera HAL offer, and how to build and test on the device. Read `CLAUDE.md` (project rules, device facts, anti-brick rules) and `docs/TODO.md` ("Camera HAL status", "Image quality and RAW", "RAM plan", "Power and performance") alongside it.

Facts here were measured on the device up to 2026-11-01. The HAL is still moving: when something here disagrees with the HAL source, the source wins. Update this file then.

## 1. What the app is

A camera-first app for a real camera, not a phone camera app. The user's decisions (2026-10-31 / 11-01):

- **Physical controls first**:
  - the zoom lever drives the 21x optical zoom (slow and fast stages);
  - half press focuses, full press shoots;
  - the pop-up flash state is shown.
- **P/A/S/M** exposure modes plus auto, with shutter speed, aperture and ISO dials. The ISP computes the exposure; the app only sets the mode and values and shows what the ISP chose.
- **OIS** on/off.
- **RAW**:
  - every shot can save a DNG next to the ISP JPEG, for development on a PC (Lightroom, RawTherapee, darktable);
  - **no on-device development** (user, 2026-11-04): ours only reached parity with the ISP's JPEG, so the "Advanced" JPEG was removed everywhere (docs/RAW-PIPELINE.md).
- **Later**: the app becomes the HOME launcher (the device is a camera), saving about 90MB of RAM. It also needs an instant-on standby: Power releases the camera and suspends, and waking brings the viewfinder back fast.
- **Code, comments, commits and docs in English.** The user talks Italian in chat. It is a separate, publishable repo (GPL/Apache compatible, no proprietary bits).
- **No Google services** (no GMS, no microG, no Play libraries). Plain AndroidX is fine if it stays small.

More decisions (user, 2026-11-02):
- **Look**: modern and light. The light theme is the default; dark and "follow the system" are options.
- **Super-optimised for the old hardware**, in RAM and speed. Kotlin + framework Views with Canvas-drawn controls, no Compose. The viewfinder Activity uses no AndroidX at all.
- **Clean screen**: only shooting controls sit next to the viewfinder; everything else goes in a separate settings screen.
- **The microSD is the only default destination.** Without a card the app warns and blocks shooting. Internal storage is an opt-in setting, marked "not recommended".
- Settings also include: format SD, geotagging (GPS only while the viewfinder is open), file numbering, artist/copyright EXIF, sounds, and grid/level/histogram.
- **Exposure meter** in M; compensation scale in the other modes.
- **Image pipeline**: the ISP's JPEG, plus **RAW (DNG) on/off**, which keeps a real DNG next to the JPEG. The "RAW-first" plan (our own JPEG developed on the device, HEIC/Ultra HDR from a background job) was tried and dropped on 2026-11-04: docs/RAW-PIPELINE.md.
- **Formats**: JPEG and DNG.
- **The DNG stays raw** (user, 2026-11-02): no distortion opcode in the pixels. At the wide end it carries Lightroom settings (XMP crs) that the user can remove: a crop that hides the image circle's dark corners, and the manual Distortion slider set to approximate the ISP's correction.
- **The DNG must open in Lightroom and any other raw editor**, not just LibRaw. That means DNG 1.4 tags, a real ColorMatrix/ForwardMatrix with CalibrationIlluminant, DefaultCrop, and the lens opcodes. Validate with Adobe's `dng_validate` and open the files in Lightroom/ACR, RawTherapee and darktable.
- **Upload plugins** later (Immich, FTP/SFTP, WebDAV), behind an `UploadTarget` interface with a JobScheduler network-constrained queue.
- **Gallery: GC Gallery**, a separate APK in the same Gradle project (`:gallery`, started 2026-11-04). The camera↔gallery contract (EXIF thumbnail, REVIEW intent, XMP `gc:` fields) is in docs/GALLERY-APP.md §3.

## 2. Platform constraints

| | |
|---|---|
| OS | LineageOS 21 (Android 14, API 34), userdebug, from the LineageOS-UL legacy base |
| ABI | 32-bit only: `armeabi-v7a` (Exynos 4412, 4x Cortex-A9 1.4GHz, NEON) |
| RAM | about 755MB visible, Go/low-RAM profile (`ro.config.low_ram=true`), zram. **RAM is the main risk**: keep the app's heap and native buffers small, and avoid large bitmaps and caches. |
| GPU | Mali-400 MP4: GLES 2.0 only, no Vulkan, no GLES 3 |
| Display | 4.8" 720x1280 LCD, run in **fixed landscape** (1280x720). Check the density with `adb shell wm density`. |
| Storage | about 3.3GB free on /data. A RAW frame is 20MB; a DNG is 32MB uncompressed. |
| Sensors | LSM330 accelerometer + gyroscope, AK8963C compass (sensors HAL 1.0), already rotated into the landscape frame. No light or proximity sensor. |
| Network | Wi-Fi on 2.4 GHz (5 GHz association still fails); the 3G modem boots, data untested |
| GPS | BCM4752 (GPS + GLONASS) through the GNSS HAL; the stack runs, a first fix was not seen yet. No assistance data, so a cold fix takes minutes. Geotagging must not depend on it; a fallback is matching the phone's GPS track by timestamp in the external tool. |

**Camera API: use `android.hardware.Camera` (Camera1).** The HAL is a HAL1 device. Camera2 would only see it as a LEGACY device, without our vendor parameters (PASM, RAW layout, flash pop-up, ISP exposure). Every M9MO feature is exposed through `Camera.Parameters` strings (`get()`/`set()` with the keys below). Camera1 is deprecated but fully present on Android 14.

## 3. The camera HAL contract

The HAL is `device/samsung/gd1/camera/` in the LineageOS tree (WSL `~/android/lineage`), `exynos_camera.c`, M9MO preset. Camera id 0, back-facing, orientation 0.

### 3.1 Standard parameters (from the M9MO preset)

| Key | Values |
|---|---|
| preview-size | 960x720 (default), 1280x720, 640x480 |
| preview-format | yuv420sp (NV21) |
| preview-fps-range | (15000,30000), (30000,30000); the HAL forces 30 fps at power-up |
| picture-size | 4608x3456 (default), 4608x3072, 4608x2592, 3648x2736, 3264x2448, 2592x1944, 2304x1728, 1984x1488, 1920x1080, 1024x768, 960x720 |
| picture-format | jpeg (the ISP encodes it) |
| jpeg-quality | 95 |
| zoom / max-zoom | 0..15; zoom-ratios 100,122,150,184,225,276,338,414,507,621,761,932,1142,1399,1714,2100 (21x optical, roughly geometric) |
| smooth-zoom | not supported |
| focus-mode | auto, infinity, macro (no continuous AF, no focus areas) |
| flash-mode | off, auto, on (xenon pop-up, no torch) |
| exposure-compensation | -6..+6, step 1/3 EV |
| whitebalance | auto, incandescent, fluorescent, daylight, cloudy-daylight |
| iso | auto, ISO100, ISO200, ISO400, ISO800, ISO1600, ISO3200 |
| image-stabilization | on, off: the optical stabilizer, in its still mode (IMAGE_STABILIZER + IS_OIS_MODE, since 2026-11-03). Before that it sent ANTI_SHAKE, which on the M9MO only switches the AE program to Samsung's anti-shake one and left the OIS off. Movie mode while recording is still to do. |
| recording-size | 1280x720, 640x480 (video is untested) |
| AE/AWB lock, face detection, scene modes | not supported. `max-num-detected-faces-hw` is 0 and `startFaceDetection()` fails. |

### 3.2 Our vendor parameters

**Exposure modes** (gd1 ead43a0). Set the values; the HAL sends them before the mode, because the mode switch writes the stored values.

| Key | Values |
|---|---|
| pasm-mode | `auto` (the ISP's default program, untouched), `program`, `aperture`, `shutter`, `manual`; list in `pasm-mode-values` |
| shutter-speed | one of `shutter-speed-values`: 16,12,8,6,4,3,2,1.5,1,0.7,0.5,1/3,1/4,1/6,1/8,1/15,1/20,1/30,1/45,1/60,1/90,1/125,1/180,1/250,1/350,1/500,1/750,1/1000,1/1500,1/2000 (the ISP's own steps) |
| aperture | f-number x10 (28 = f/2.8); `aperture-values` 28..80. The lens is f/2.8 wide to about f/5.9 tele, and the ISP clamps to what the current zoom allows. Read back what it used (exif-av). |
| iso | as above; in `manual`, auto is replaced by ISO100 |

Verified through the readback: A f/5.6 gave AV 473 (f/5.6); S 1/250 gave TV 797; M 1/30 f/2.8 ISO200 gave exactly that.

**What the ISP used for the last shot** (set after every shot). The ISP JPEG carries **no** exposure EXIF, so the app must write these into the JPEG and into the DNG.

| Key | Meaning |
|---|---|
| exif-iso | ISO (standard steps) |
| exif-tv | APEX Tv x100, rounded to the standard steps: time = 2^(-tv/100) s. Snap it to the nearest `shutter-speed-values` entry (TV 797 is 1/250). |
| exif-av | APEX Av x100: f = 2^(av/200) (297 = f/2.8) |
| exif-bv, exif-ebv | brightness and exposure bias, APEX x100 |
| exif-flash | the ISP's EXIF flash value. Not reliable: it read 9 ("fired") with the flash down and the mode on. |
| exif-fl | focal length in mm x1000 (4100 = 4.1 mm wide) |
| exif-fl-35mm | 35mm-equivalent focal length in mm x100 (2300 = 23 mm wide) |

**Pop-up flash**: `flash-popup` is `up` or `down`, read from the ISP at most every 500 ms during `getParameters()`. The HAL never changes `flash-mode` by itself: if the flash is down and the mode is on/auto, the shot is simply taken without flash (verified). The app decides what to show (e.g. "raise the flash"). `m9mo-strobe-charge` is a raw ISP byte, not decoded yet (it read 0 so far).

**Continuous zoom** (verified 2026-11-02 with the lever). The app sends a zoom action on key down and `stop` on key up:

| Key | Values |
|---|---|
| zoom-action | `tele-fast`, `tele-slow`, `wide-fast`, `wide-slow` start the lens; `stop` stops it. List in `zoom-action-values`. |
| zoom | While the lens moves and for 1 s after `stop`, `getParameters()` reports the live position (0..15), read from the ISP at most every 100 ms. In that window a `zoom` sent back by the app is ignored, because it is only its last reading. |

After a reopen the HAL re-sends the stored `zoom` once the preview runs. The lens only zooms in monitor mode, so a zoom set before `startPreview()` is applied then.

**Metering** (verified: it changes the exposure): `metering` = `matrix` (default), `center` or `spot`, list in `metering-values`.

**Live exposure during the preview**, refreshed in `getParameters()` at most every 250 ms. From the ISP's AE registers (Q16 APEX), scaled to APEX x100 like exif-*:

| Key | Meaning |
|---|---|
| live-bv | Scene brightness, Av + Tv − Sv. It matched the shot's exif-bv exactly in P. This is the value for an exposure meter. |
| live-tv, live-av | The preview's own exposure. live-tv is limited by the 30 fps preview, so it can be shorter than the shot's TV. |
| live-iso | 3.125 × 2^Sv |
| live-lv | The ISP's own LV, an integer (about EV at ISO 100) |
| live-shot-tv, live-shot-av | The exposure the ISP plans for the shot, from SMART_READ1 (decoding below). It keeps tracking in the dark, where live-tv reads 0. Verified 2026-11-03: 1 s, f/2.8 matched the shot's EXIF. |
| live-shot-iso | The shot's planned auto ISO, plain ISO in whole stops (100–3200), coarser than live-iso |
| m9mo-live-raw | The raw registers "tv,av,sv,lv,smart1,smart2,warn", for debugging |

**Lamps** (2026-11-03):

| Key | Meaning |
|---|---|
| af-lamp | `on` (default, as in Samsung's app) or `off`: the AF assist lamp setting. The ISP lights it by itself during AF in low light. |
| timer-led | `off`, `2`, `5`, `10`: the ISP's own self-timer blink (slow, then fast) for a countdown of that many seconds. Set it when the countdown starts and set `off` at the end, on cancel and on pause, as Samsung's app did. Ported from Samsung's GC100 driver (LENS 0x2D). Samsung also sent TIMER_MODE (an OIS self-timer flag) with it. The HAL does not: with the OIS off, that flag made the next RAW shot lose its raw frame. The kernel powers the lamp for the countdown (otherwise it is powered only during AF). Verified 2026-11-03: it blinks through a 10 s countdown, and a RAW shot right after works. |

**Look, corrections, flash and white balance** (2026-11-03). None of the options below has a default value. The HAL sends one only once the app sets it, so until then the ISP keeps its own default (unknown: compare on/off to find out). Once set, it is re-sent at every ISP power-up. Each has a `<key>-values` list.

| Key | Meaning |
|---|---|
| sharpness, contrast, saturation | `-2`..`2`: the ISP's 5-level JPEG look controls (MON 0x20, 0x25, 0x0F) |
| wdr | `on`/`off`: the ISP's wide dynamic range (CAPPARM 0x2C). Mild effect. |
| lens-shading-correction | `on`/`off`: vignetting correction (PARM 0x07). ISP default **on**. Off darkens the edges by about 2%. |
| lens-distortion-correction | `on`/`off`: distortion correction (PARM 0x1B). ISP default **on**. Off gives the whole uncorrected image circle (barrel distortion, black corners, wider field), like the RAW. Only for an "uncorrected" option. |

**No flash EV.** The HAL had a `flash-ev` key (Samsung's flash step, CAPPARM 0x28, the same CID and 0..4 range as Samsung's HAL). It was removed on 2026-11-04: the ISP takes the value (kernel readback) but the flash exposure stays the same from -2 to +2, and Samsung's own app never sets it.

LSC and LDC are parameter-mode registers. The kernel switches the ISP to parameter mode and back around the write, which costs a short viewfinder stall, and only when the value changes (kernel 3952a933; before that they had no effect with the viewfinder open).
| whitebalance=kelvin + wb-kelvin | 3000..10000 K in 100 K steps (`wb-kelvin-min/max/step`, default 5500). The ISP takes K/100 (WB 0x92), as with Samsung's HAL. |

The HAL also gives the ISP the local hour and minute at every power-up (TIME_INFO; the register holds no date).

The parameter parser stores "5" as an int and "off" as a string. A key that takes both (timer-led) is read type-agnostically by the HAL, so either form works.

**In M the AE registers report the manual exposure, not the scene** (checked 2026-11-02: they stayed put with the camera on a lamp). LV still meters the scene, so live-bv comes from LV there:

| Key | Meaning |
|---|---|
| live-bv-source | `ae`: live-bv is Av + Tv − Sv, in 1/100 EV (every mode except M). `lv`: in M, live-bv = (LV − 5) × 100, in whole stops. LV is EV at ISO 100; it read 8 where P gave Bv 2.57. `none`: too dark (LV reads 255), and live-bv keeps its last value. |

Verified in M at 1/60 f/4 ISO200: the room meters −0.7, a lamp +2.3 (it blows out), a dark corner gives `none`. `m9mo-live-raw` also carries Samsung's PRO_MODE SMART_READ1/2 and the warning condition ("tv,av,sv,lv,smart1,smart2,warn").

**SMART_READ1/2 decoding** (2026-11-03): taken from the stock app's `CameraEngine$AutoParametersCallback.onAutoParameters`, deodexed with baksmali. The stock HAL's `smartThread` polls both registers and passes them to the app, which shows the exposure the ISP plans for the shot. The shutter, aperture and ISO fields are verified on the device; the HAL publishes them as live-shot-*.

| Bits | Meaning (stock app) |
|---|---|
| smart1 0–3 | Exposure compensation: value − 6, in the EV dial's steps |
| smart1 4 | Faces detected (0/1) |
| smart1 5–6 | Unknown 2-bit state |
| smart1 7 | Flash charging |
| smart1 8 | Flash standby |
| smart1 13–15 | Auto ISO: 0–5 = 100, 200, 400, 800, 1600, 3200 (anything else is shown as 100) |
| smart1 16–23 | Aperture as two BCD digits: f-number × 10 (e.g. 0x31 = f/3.1) |
| smart1 24–29 | Shutter: index = field + 1, in 1/3-stop steps from 16 s, t = 2^(4 − (index − 1)/3) s, i.e. 1 = 16 s, 13 = 1 s, 28 = 1/30. The stock app treats 1–13 (16 s to 1 s) as a long exposure. |
| smart1 30 | Unknown |
| smart1 31 | Flash will fire |
| smart2 0–1, 2–6 | Smart-auto scene and sub-scene |
| smart2 9–11 | Warning condition ("now_warn") |
| smart2 20–27 | LV × 10 when bit 27 is clear |
| smart2 31 | Free-memory flag (one shooting mode only) |

**Auto-focus area** (HAL gd1 3806e46):

| Key | Values |
|---|---|
| focus-areas | One area, `max-num-focus-areas`=1. `(l,t,r,b,w)` in −1000..1000 gives touch AF at the area's centre on the next `autoFocus()`, kept 40 preview pixels off the edges. `(0,0,0,0,0)` goes back to the centre. A touch area takes priority over af-area. |
| af-area | `center` (default), `multi`, `face`; list in `af-area-values`. In macro, multi keeps the macro range. |
| af-result-area | "l,t,r,b": the touch area while one is set, else "0,0,0,0". The ISP does not say where centre, multi or face AF locked. |
| face-count | Faces the ISP sees, only with af-area=face. There are no face positions in the driver. |
| af-result-raw | The ISP's last AF status word (0x1000 focusing, 0 failed, anything else success) |

- Verified 2026-11-02:
  - touch at all four corners (af-result-area echoed each area);
  - multi;
  - face (face-count 1 with a person, 0 without);
  - back to the centre.
- af-result-raw seen: 16 (0x10) for a centre or face success, and 80 (0x50) for multi, so the extra bits may say which multi windows locked: worth decoding.
- After the first corner tap the raw word read 0 (fail). That may just be the low-contrast corners of that scene; check touch AF on real subjects.
- `autoFocus()` fails while a `zoom-action` runs, and Camera1 throws `RuntimeException("autoFocus failed")`: stop the zoom first.
- Object tracking is not offered yet. The driver oopses if tracking starts before the preview format is set, and its rectangle readout is compiled out (`#if 0`); both need kernel fixes.

**Bracketing** (verified 2026-11-02, gd1 4cbe4ab and kernel a2659245):

| Key | Values |
|---|---|
| capture-mode | `single`, `ae-bracket` (3 exposures at 3 fps) or `hdr` (3 at 7.5 fps, about 0.4 s); list in `capture-mode-values` |
| bracket-ev | The step between frames, one of `bracket-ev-values` = `0.3,0.6,1,1.3,1.6,2`. It is a float: "1" and "1.0" both work. |
| bracket-frames | 3 |
| num-snaps-per-shutter | 3 in a bracket mode, 1 otherwise. CameraService uses it to keep the JPEG callback enabled for all frames. |
| bracket-exif | Every frame's exposure, in delivery order, as "iso,tv,av,bv,ebv|iso,tv,av,bv,ebv|iso,tv,av,bv,ebv" (APEX x100 like exif-*). The separator is `|`, because `;` separates the flattened parameters. Set before the first JPEG callback and kept until the next shot. **Use this one.** |
| bracket-index, bracket-frame-ev, exif-* | Also set before each frame's callback. They are unreliable: CameraService forwards the 3 callbacks about 25 ms apart, after the HAL has finished, so the app reads them too late. |

- **Frame order: −EV, 0, +EV.** Count the frames in delivery order.
- **What the ISP varies:**
  - P (and the other auto modes): shutter and ISO. At bracket-ev 1: ISO100/TV549/EBV−100, ISO160/TV491/0, ISO320/TV491/+99, all at AV297. At 2: TV649/ISO100, TV491/ISO160, TV432/ISO400.
  - M: the ISO only (100/200/400 around ISO200), with TV and AV fixed.
- `hdr` and `ae-bracket` gave the same exposures; HDR only takes them faster.
- A bracket `takePicture()` gives one shutter callback, then 3 JPEG callbacks, or only `CAMERA_MSG_ERROR` if any frame fails (all or nothing).
- `STREAM_PART2` waits for all three exposures, so the shot completes about 2 s later than a single one.
- The flash is forced off during a bracket and restored after.

**RAW frame layout** (gd1 7cdc1a4; measured, see `tools/m9mo-raw.py`):

| Key | Value |
|---|---|
| raw-size | 4736x3452 (row stride in pixels x rows; a 4160-byte tail follows the rows) |
| raw-format | raw10be: a continuous big-endian 10-bit stream, 4 pixels in 5 bytes, MSB first. Not MIPI RAW10. |
| raw-cfa | grbg |
| raw-active-area | 2,0,4666,3452 (left, top, right, bottom); columns 0-1 and 4666-4735 are zero (HAL 5413dfd; before it, 4672) |
| raw-black-level | 64. The real black depends on ISO: 63.5 at ISO 100 down to 60.5 at ISO 3200 for exposures up to 1/4 s, ~63.5 at 1 s (gccore `noise.h`) |
| raw-white-level | 1023 (assumed, not yet checked on a clipped highlight) |

### 3.3 Capture behaviour

- **The ISP powers up at `startPreview()`, not at `open()`.** It takes about 2 s, during which the lens extends. `release()` retracts the lens (about 1 s) and powers it down. Do not hold the camera open in the background: release it in `onPause()` or on standby. The kernel also retracts the lens on reboot and power-off with the camera open.
- **`takePicture()`** stops the preview, takes the 16MP shot in a separate FIMC session, and restarts nothing: the app calls `startPreview()` from its JPEG callback, as the Camera1 contract says. It takes about 2 s; about 3 s with RAW.
  - A second `takePicture()` during a capture returns an error (-EBUSY). Wait for the JPEG callback.
  - `cancelPicture` cannot abort an exposure.
  - Parameters set during a capture are stored and applied after it.
  - Callback order: shutter (at exposure), then raw (if requested), then jpeg. Both arrive after the HAL has cleaned up.
- **Auto-focus**: `autoFocus()` polls the ISP up to 4 s. Success means the ISP reported focus.
  - AF locks AE/AWB on the ISP. The HAL unlocks them when AF stops or is cancelled, so after `onAutoFocus` the exposure can move again.
  - A `takePicture()` during AF stops the AF first.
  - Half press → `autoFocus()` is the natural mapping.
- **Zoom**: `setZoom(0..15)` moves the optical zoom one step. A continuous lever drive needs the HAL work in §6.
- **Preview**: NV21, about 30 fps. There is some viewfinder lag (3 capture buffers); keep the preview path light, with no per-frame Java copies.
- **Orientation**: the sensor and the display are both landscape (orientation 0). Use the accelerometer for EXIF orientation and an on-screen level.
- **Callbacks and the HAL1 deadlock rule.** CameraService holds its client lock across every call into the HAL, and a HAL callback waits for that lock while its message type is enabled. If the HAL waits for one of its threads during such a call while that thread is inside a callback, the camera hangs for good. The app cannot cause this, but it explains what to avoid when the HAL is extended:
  - HAL threads must not wait for each other inside a framework call, or must make sure no enabled callback can be in flight.
  - The 2026-11-02 hang (OpenCamera froze on a shot) was the preview thread sending `CAMERA_MSG_PREVIEW_METADATA` (face data) on every frame. CameraService enables that message for every client and never disables it, so a `stopPreview()` that joined the preview thread could deadlock. The HAL now sends it only during face detection, which the M9MO does not have.
  - Preview frame callbacks (`setPreviewCallback`) are safe: CameraService disables them before `stopPreview()`. Keep using them sparingly anyway; they cost a binder copy per frame.
  - After such a hang only a reboot recovers: CameraService refuses a restarted provider (§5). The reboot retracts the lens.

### 3.4 RAW in the app

- **Per shot** (gd1 9c3575b): `raw-capture` = `off` (default) or `on`, listed in `raw-capture-values`. Set it before `takePicture()`.
  - With on, a single shot also delivers the raw frame through `CAMERA_MSG_RAW_IMAGE`, Camera1's `takePicture(shutter, rawCallback, jpegCallback)`, before the JPEG. The raw callback gets the full 20,440,000-byte frame.
  - With a bracket `capture-mode`, raw-capture is ignored. A 3-frame RAW burst from the bracket is not possible: the ISP keeps bracket frames only as JPEGs (TODO.md, "Burst merge").
  - Nothing is written to /data. The bring-up property `persist.vendor.camera.raw=1` still forces RAW on every shot, and only then does the HAL also keep `/data/vendor/camera/IMG_<time>.raw` + `.raw.txt`.
- **Getting the data through Camera1 (verified 2026-11-02, GC100_0010.dng)**:
  - A raw callback alone gets no data. Camera1's JNI asks the HAL for `CAMERA_MSG_RAW_IMAGE` only when the app has queued a buffer with the hidden `Camera.addRawImageCallbackBuffer(byte[])`. Otherwise it asks for `RAW_IMAGE_NOTIFY`: the HAL log then shows enable_msg_type 770 instead of 386.
  - The app calls that method by reflection.
  - The buffer must hold the whole frame including the 4160-byte tail (20,440,000 bytes); a smaller one is skipped ("Couldn't allocate byte array").
  - Each buffer is used once and must be queued again. The app reuses up to two 20 MB buffers and sets `largeHeap`, because the low-RAM profile's heap limit is 48 MB.
- **DNG**:
  - Reference implementation: `tools/m9mo-dng.py` (validated with exiftool, and developed correctly by LibRaw). Port its logic: unpack raw10be into 16-bit, then a TIFF/DNG IFD with CFA tags, BlackLevel, WhiteLevel, ActiveArea, an Exif sub-IFD with exposure/focal/date, and AsShotNeutral.
  - The colour matrix there is a **placeholder** (sensor treated as linear sRGB). The real profile will be fitted against the ISP JPEGs (TODO.md, "Colour profile without a ColorChecker"). Keep the matrix and neutral as data, not code.
- **The RAW is the whole lens image circle**:
  - it is wider than the JPEG, with strong barrel distortion and vignetting, which the ISP corrects and crops for its JPEG;
  - a DNG needs a DefaultCrop plus the `WarpRectilinear` and `FixVignetteRadial` opcodes per zoom position (still to be measured);
  - the in-app developer must correct them itself.
- **Memory**:
  - 20MB raw plus 33MB of 16-bit pixels plus the JPEG decode does not fit comfortably;
  - stream the DNG write in strips, never hold a full float image;
  - develop in strips (TODO.md "Image quality and RAW": librtprocess, libjpeg-turbo, Halide hdr-plus for bursts).

## 4. Physical keys

From `gpio-keys` (keylayout `device/samsung/gd1/keylayout/gpio-keys.kl`):

| Control | Linux scancode | Android keycode |
|---|---|---|
| Shutter half press | 0x210 (528) | KEYCODE_FOCUS |
| Shutter full press | 0x220 (544), while 528 is still held | KEYCODE_CAMERA |
| Zoom lever T, slow | 0x215 (533) | KEYCODE_ZOOM_IN |
| Zoom lever T, fast | 0x221 (545), nested inside 533 | KEYCODE_ZOOM_IN |
| Zoom lever W, slow | 0x216 (534) | KEYCODE_ZOOM_OUT |
| Zoom lever W, fast | 0x222 (546), nested inside 534 | KEYCODE_ZOOM_OUT |
| Power | 0x74 | KEYCODE_POWER |

- Tell slow from fast with `KeyEvent.getScanCode()`.
- The fast stage arrives about 30 ms after the slow one and is released before it.
- Handle the keys in the activity (`onKeyDown`/`onKeyUp`, return true) so the system does not act on them.
- LineageOS camera-button settings are enabled (`config_deviceHardwareKeys=32`). Check that "camera button launches camera" and similar settings do not fight the app.
- The camera keys are not wake sources (yet).

**Pop-up flash switch**: the kernel also reports it as input `SW` 0x0f (named `SW_PEN_INSERTED` in modern headers) and as `/sys/class/sec/sec_key/strobe_insert` (1 = up). Android drops `SW_*` events, and apps cannot read that sysfs node under SELinux enforcing, so use the `flash-popup` parameter.

## 5. Building and testing on the device

- The device is connected over USB. adb is `$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (not on PATH). **Always pass `-s <serial>`**: the user's phone is usually connected too.
- **`adb install` fails** on this 3.4 kernel ("splice EINVAL"). Install with:
  ```
  adb -s <serial> push app.apk /data/local/tmp/app.apk
  adb -s <serial> shell pm install -r /data/local/tmp/app.apk
  ```
- **Logs**:
  - `adb logcat -s exynos_camera` for the HAL. Each shot logs `exynos_camera_m9mo_exif: ISO .., TV .., AV ..`.
  - `CameraService` for the framework side.
  - Kernel: `dmesg | grep -i m9mo`.
- **Do not kill the camera provider to "reset" the camera.** Restarting `android.hardware.camera.provider@2.5-service` also restarts CameraService (in mediaserver), which then rejects every client until a reboot (TODO.md). Close the app normally instead.
- **Driving the camera from adb for tests**:
  - `adb shell input keyevent KEYCODE_CAMERA` shoots;
  - `KEYCODE_FOCUS` focuses;
  - `am start -n <pkg>/<activity>` starts the app.
- Bring-up overrides exist for OpenCamera: `setprop vendor.camera.test.{pasm,shutter,aperture,iso}`. The app must not rely on them, and they will be removed.
- Native code: NDK, `armeabi-v7a`, with NEON (`-mfpu=neon`). Benchmark the RAW pipeline on the device early (TODO.md: estimated 5–10 s per 16MP basic develop on the A9s).
- Before any change that writes to the device outside the app's own data, follow the anti-brick rules in `CLAUDE.md`. The app itself never touches partitions or the ISP firmware.

## 6. HAL work the app will need (not done yet)

Ask for these, or do them in the HAL (`device/samsung/gd1/camera/`), as the app reaches them:

1. ~~`raw-capture=on|off` per shot~~: done (§3.4).
2. ~~Continuous zoom from the lever~~: done, `zoom-action` (§3.2).
3. **Aperture limits per zoom position** in `aperture-values` (read back from the ISP). The live P/A/S readout is done (`live-*`, §3.2).
4. **The JPEG's EXIF**: either the HAL inserts EXIF (exposure from exif-*, orientation, date, GPS) into the ISP JPEG, or the app does it. Decide one place.
5. ISP image tuning: lower the ISP sharpening and noise reduction (M9MO sharpness/contrast controls) for better JPEGs, and expose them.
6. ~~Touch AF / focus areas~~: done (§3.2), with center/multi/face areas. Still open: object tracking (kernel fixes), face positions, and where multi-AF locked (no readout found; a `V4L2_CID_ISP_DEBUG_READ` dump of the FD category and LENS 0x20-0x2F may show them).
7. Video recording is untested on the M9MO.
8. **Fast HDR from the ISP's bracket mode** (the user wants it, 2026-11-02; phone-like, not one shot every few seconds).
   - m9mo.c already has `RUNNING_MODE_HDR`: `CAP_MODE_BRACKET_CAPTURE`, frame interval 0 (7.5 fps). The ISP exposes 3 frames back to back, about 0.4 s in all, and keeps them; `m9mo_wait_framesync` waits for the 3 frame syncs.
   - The EV step is `M9MO_AE_AUTO_BRACKET_EV` (presets 0.3–1.6 EV and up, `m9mo_set_bracket_aeb`). `RUNNING_MODE_AE_BRACKET` is the same at 3 fps with frame select.
   - The ISP does not merge: stock "Rich tone" merged in Samsung's userspace.
   - HAL: done as `capture-mode=hdr|ae-bracket` with `bracket-ev` (§3.2): 3 JPEG callbacks per `takePicture()`. Reading them takes about 1 s each, but the subject only has to hold still for the exposures.
   - Possible later: 5 frames (the ISP's frame count register is not explored), 3 RAW frames were tried on 2026-11-02 and do not work: the bracket's frames exist only as JPEGs (TODO.md, "Burst merge").
   - App (not started): exposure fusion (Mertens) in native code with NEON, at full or reduced resolution. Save the EV 0 JPEG at once and replace it when the merge is done. Align the frames first for hand-held shots: a small global shift search on downscaled luma is enough at 0.4 s. Weigh it against the RAW development result (docs/RAW-PIPELINE.md) before investing: the ISP's own frames are good.

## 7. Suggested milestones

1. Viewfinder (SurfaceView/TextureView, NV21 preview), shutter keys (half press AF, full press shoot), zoom lever via `setZoom`, flash mode + `flash-popup` indicator, JPEG saved to `DCIM/` via MediaStore with EXIF from exif-*.
2. P/A/S/M UI with dials and the live readout; OIS toggle; exposure compensation.
3. RAW: `raw-capture` in the HAL (§6.1), raw callback → DNG writer (port of `tools/m9mo-dng.py`), DNG + JPEG pair in the gallery.
4. ~~Background development job~~: dropped on 2026-11-04 (docs/RAW-PIPELINE.md).
5. HOME launcher role, standby/instant-on with the ISP power cycle, settable auto-standby.

## 8. Hardware reference

- **Lens**: 21x optical zoom, 4.1–86.1 mm (23–483 mm equivalent), f/2.8–5.9, with optical image stabilization.
- **Sensor**: 16MP 1/2.3" BSI CMOS. Native readout 4736x3452 including margins; 4608x3456 picture.
- **ISP**: Fujitsu M9MO, firmware FCGB04. It does AE/AF/AWB, the exposure programs, the zoom/focus/OIS motors and the JPEG encode. The kernel driver is `m9mo.c`. Never ask for its firmware update, factory or debug controls: the kernel refuses them on purpose.
- **Flash**: xenon, pop-up, ISP-fired.
- **Files in this project worth reading**: `tools/m9mo-raw.py` (RAW decoder), `tools/m9mo-dng.py` (DNG writer), `device-dump/raw-tests/` (sample RAW + ISP JPEG pairs, a dark frame), `reference/kernel-m9mo/` (driver source, all V4L2 controls).

### 8.1 Other models: the Galaxy Camera 2 (EK-GC200)

The user wants the app (and GC Gallery) to run on the GC200 later. It has the same lens, an M9MO-family ISP (kernel driver `m9mo_sf2`) and 2 GB of RAM, but no 3G and an extra zoom ring around the lens. Audit of 2026-11-04:

- **Already read from the HAL, nothing to do**:
  - the RAW layout (`raw-size`, `raw-active-area`, levels, CFA) and the RAW buffer size;
  - picture sizes, zoom steps (`max-zoom`, `zoom-ratios`, `zoom-action-values`), and the PASM/shutter/aperture/ISO/WB/flash/bracket value lists;
  - live and EXIF readouts.
- **Fixed in the audit**: the EXIF and DNG Model, the DNG UniqueCameraModel ("Samsung " + model), the file prefix and the default folder now come from `Build.MODEL` (`CameraModel`: EK-GC100 gives `GC100_0001.jpg` in `DCIM/GC100`).
- **Still GC100 constants**, to switch per model once a GC200 is on the bench:
  - keys: the scan codes of the lever's fast stage (545/546 in `ViewfinderActivity`). The GC200's zoom ring needs its own codes from its gpio-keys map, probably as relative steps;
  - lens: the EXIF lens strings and LensSpecification, and `BASE_35MM_MM` = 23 for the zoom bar's mm. It is the same lens, so these probably hold, but check `exif-fl` / `exif-fl-35mm` on a GC200;
  - gccore's lens and colour calibration: `lens.h` (optical centre, model radius, the ISP frame 4608x3456), `lens.cpp` (distortion/vignetting per focal length), `isp.cpp` (the ISP's tone curve and matrix, for `matchIsp`), `dng.h` ColorMatrix1, and the `tools/gc100-lcp.py` profile. These were measured on this GC100 and its sensor/ISP firmware. A GC200 needs its own RAW+JPEG pairs and a per-model table;
  - RAM: `MAX_RAW_BUFFERS = 2` is sized for 768 MB. On 2 GB a third RAW buffer would allow faster RAW bursts;
  - `rear_camfw` (ISP firmware in Settings → About) is the GC100 sysfs path; it shows "unknown" if missing.

## 9. The app: `camera-app/` ("GC Camera")

A standalone Gradle project in this repo, to be split into its own repo before publishing. The package is `com.simoneluconi.gccamera`.

**Build**: AGP 9.4.1, Gradle 9.8.1, minSdk/targetSdk 34, armeabi-v7a only, with the JDK from Android Studio.
```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleRelease
```
- The release build has R8 on and is signed with the debug key; it is the one to test on the device (1.8 MB).
- Install with push + `pm install -r` (§5).

**Layout of the code**:
- `camera/CameraController.kt`: Camera1 on its own HandlerThread. It owns open/close, AF, capture (focus priority), the zoom drive, the 500 ms parameter poll, and the preview-frame histogram (one buffer re-armed every 250 ms).
- `camera/M9mo.kt`: PASM modes, APEX conversions, `CameraCaps` (what the HAL offers), `ShotSettings`, `ShotInfo` (exif-*), `LiveStatus` (flash-popup, live-*).
- `storage/`: `StorageTarget` (microSD first, internal only by choice) and `PhotoWriter` (MediaStore + EXIF on a background thread).
- `sensors/OrientationSensor.kt` (level, EXIF orientation) and `location/GeoTagger.kt` (GPS only while the viewfinder is open).
- `ui/ViewfinderActivity.kt`: a plain framework Activity, plus custom Canvas views in `ui/widget/`.
- `settings/`: `Prefs` (shared with the settings screen through the androidx.preference default file) and `SettingsActivity` (AndroidX, loaded only there).
- `raw/NativeDng.kt` → `app/src/main/cpp/gcjni.cpp` → **`gccore/`**: the C++ image core, shared with the PC tools.
  - Today it holds the DNG writer (`gccore/src/dng.cpp`, a port of `tools/m9mo-dng.py`). It writes DNG 1.4 with a 256-px sRGB preview in IFD0 (for galleries) and the 16-bit CFA in a SubIFD.
  - Tags: ActiveArea, DefaultCrop (8 px inside), Black/WhiteLevel, ColorMatrix1 + CalibrationIlluminant1, AsShotNeutral (grey world), LensInfo, Orientation, and Exif (exposure, lens, dates).
  - It streams row by row, with no 16-bit full-frame buffer; JNI reads the 20 MB array without a copy.
  - Checked on 2026-11-02 against the sample in `device-dump/raw-tests/`: CFA data byte-identical to m9mo-dng.py's, `exiftool -validate` OK, and LibRaw (rawpy) develops it. Not yet opened in Lightroom.
  - Host build (WSL, no cmake there): `g++ -std=c++17 -O2 -Igccore/include gccore/src/{tiff,raw,dng,isp,lens}.cpp gccore/tools/gcdng.cpp -o gcdng`, then `gcdng IMG_x.raw` (reads the `.raw.txt` sidecar).
  - `gccore/src/jpeg.cpp` + `isp.cpp`: `matchIsp` reads the ISP's white balance from its JPEG (embedded in the DNG) for AsShotNeutral. On-device development was tried and removed on 2026-11-04 (docs/RAW-PIPELINE.md; the code is in git history).
- **Lens calibration** (2026-11-02, `gccore/src/lens.cpp`), from 15 RAW + ISP JPEG pairs across the zoom, with SIFT registration and a radial model:
  - At the centre the ISP JPEG is 1:1 with the RAW, with no rotation, centred at about (0.499, 0.504) of the active area.
  - The ISP corrects barrel distortion only at the wide end: k1 −0.27 at 4.1 mm, −0.18 at 5.0, −0.09 at 6.2, −0.025 at 9.2, and none from 16.5 mm on.
  - At 4.1 mm its frame uses about 84% × 85% of the active area; the rest is the image circle's dark corners. From 16.5 mm on the JPEG is the whole frame.
  - The DNG writer embeds `crs:HasCrop/Crop*` (the inscribed rectangle) and `crs:LensManualDistortionAmount` (a first guess of −k1 × 100: 27 at the wide end) when the focal length is below 16.5 mm.
  - Repeat with the analysis scripts: drive the app with `am start … --ei zoom N`, capture RAW, develop the DNG (RawTherapee/LibRaw), then register.
- **RAW in the app**: the panel's JPEG/RAW+JPEG button (or Settings → Image) saves `GC100_NNNN.dng` next to the JPEG.
  - It works when the HAL reports `raw-*`.
  - It sends `raw-capture=on|off` once the HAL has `raw-capture-values`. Until then RAW frames arrive only with `persist.vendor.camera.raw=1`.

**Working on the device (2026-11-02)**:
- preview, half-press AF and full-press capture, self-timer;
- P/A/S/M with drag/tap dials, plus the ISP's choice read back in grey;
- EV scale, histogram, grid, level, flash modes with a warning when the flash is down;
- JPEG to `DCIM/GC100/GC100_NNNN.jpg` on the microSD, with full EXIF (exposure, f-number, ISO, focal, 35mm focal, bias, program, metering, flash, orientation, date with offset, lens, artist/copyright, GPS);
- status bar with battery, clock, shots left, GPS and Wi-Fi;
- Fn menu with WB, metering, focus, drive, OIS and aspect;
- the settings screen.

**UI behaviour the user chose (2026-11-02)**:
- **There is no EV row.** The scale under the dials is the compensation control in P/A/S: drag it, tap a position, or double-tap to reset to 0. In M the same scale is the exposure meter. In Auto it is fixed at 0.
- **The pop-up flash is the switch.** Raising it sets the last used mode (auto or on, remembered; auto by default), and closing it sets off. The button only toggles auto and on, and with the flash down it just says to raise it.
- **Histogram off** (Settings → Display) gives its room to the shutter/aperture/ISO rows.
- **Settings** has tabs along the top (Storage, Image, Display, Sound, Location, System). Opening it only stops the preview and keeps the lens out for 30 s, so going back is instant. Leaving the app in any other way releases the camera at once.
- **Rotation, camera style**: the layout stays in landscape. The mode letters, the four panel buttons, the Fn menu, the countdown and the banners turn in place (animated). The level works in every hold. EXIF orientation comes from the accelerometer (clockwise hold → ORIENTATION_ROTATE_90).

**Added later on 2026-11-02, verified on the device**:
- **AF**:
  - A tap on the viewfinder is touch AF (`focus-areas`, a 10% square). The point stays for the half press; a double tap goes back to the centre.
  - Fn → AF area offers centre / multi / face (`af-area`). Face mode shows "Faces: N"; the ISP gives only the count.
  - The app stops any zoom-action before autoFocus(), because the HAL refuses AF while zooming.
- **Brackets**: Fn → Drive → AEB / HDR (`capture-mode`, `bracket-ev`).
  - 3 JPEGs per shot, in the order −EV, 0, +EV, saved as GC100_NNNN_1/2/3.
  - The app counts the frames itself and reads each frame's exposure from `bracket-exif`: the callbacks arrive back to back after the HAL has moved on.
  - In P the ISP varies TV and ISO; in M only ISO.
  - A failed bracket sends CAMERA_MSG_ERROR and the app goes back to the preview.
- **M meter**: from the ISP's LV in whole stops (`live-bv`, `live-bv-source` = ae | lv | none). With `none` (too dark) the needle is pinned at "< −3".
- **Zoom bar**: a text-only pill at rest. While the lever moves it grows into the bar with the position, then shrinks back after 2 s.
- **DNG for Lightroom**:
  - The ISP JPEG is embedded as the full-size preview (Windows and file browsers show that).
  - A lens profile `profiles/Samsung EK-GC100.lcp` (from `tools/gc100-lcp.py`) carries the measured distortion. Install it in `%APPDATA%\Adobe\CameraRaw\LensProfiles\1.0\`.
  - The DNG's XMP turns the profile on, and crops to the ISP frame after the profile's own rescale, using `gccore::ispFrameAfterProfile`.
  - Checked in Lightroom by the user and in RawTherapee: about 1–2 px from the ISP JPEG's geometry. Lightroom rejects a whole .lcp that has an all-zero entry, so the tele entries use a negligible k1.
- **RAW over Camera1** needs `Camera.addRawImageCallbackBuffer` (§3.4).

**Waiting for the HAL** (the app already reads these keys and switches over as soon as they appear):
- `zoom-action` for a smooth lever zoom. Until then the app steps `setZoom`, which stutters;
- `metering`;
- `live-tv/av/bv/iso` for the true meter in M and a live P/A/S readout. Until then the meter is estimated from preview luma and marked "≈";
- `raw-capture` for the RAW phase.
