# Micro-HDMI output (EK-GC100 and EK-GC200)

The TV shows the photo being viewed in GC Gallery, never a copy of the screen. With GC Gallery closed, it shows a short notice that HDMI works only there for now.

## 1. How it works

Android's display stack has no external display on the Exynos 4412: the hwcomposer has none, and hardware/samsung builds libhdmi only for the 4210. Composing a second display through SurfaceFlinger would also cost the Mali-400 work on every frame. So the TV is driven outside Android's display stack:

- **`hdmid`** (device tree `hdmid/`, vendor) owns the output. It sleeps on the kernel's uevent socket and on its control socket, and does nothing in between.
  - On plug-in (the `hdmi` switch, HPD) it reads the sink's EDID over DDC (`/dev/i2c-5`, address 0x50), picks the mode and starts s5p-tvout (`/dev/video16`) with mixer graphic layer 0 (`/dev/graphics/fb10`).
  - On unplug it frees everything, which stops the output.
  - While the camera's own screen is off the output is off too: nobody is showing anything, and the output keeps the memory bus at up to 400 MHz (1080p60/50).
- **GC Gallery** (`tv/TvOut`) connects while the viewer is visible (onStart to onStop). It draws the photo already scaled for the TV's mode and sends it over `/dev/socket/hdmid`. hdmid swaps red and blue for the mixer (B, G, R, A) as it copies the picture in, 256 KB at a time: a colour matrix in the gallery's drawing made Skia's software pipeline several times slower.
  - First from the viewer's own screen decode, at once.
  - When the TV has more pixels than that decode (1080p), a sharper one follows once the photo has been still for 300 ms: the JPEG (or a DNG's embedded JPEG) decoded at 1/2 in bands of 256 rows, so the half-size image never sits in memory whole.
  - Swiping sends only the photo the pager settles on.
  - **Zoom**: zoomed in, the TV shows the part of the photo on the camera's screen, filling the TV.
    - While a zoom or pan moves, the gallery sends half-size frames (960x540 at 1080p) drawn from the viewer's bitmap, always the latest one wanted, and the mixer doubles them. That gives about 10-25 frames a second.
    - Once the gesture settles, a full-size frame follows, and 300 ms later the sharp one: that region of the JPEG decoded at the TV's size, also in bands.
    - Back at fit, the TV shows the whole photo again.
- **The camera is not touched.** When GC Camera opens, the gallery is in onStop: the TV keeps only hdmid's small notice layer, and the daemon sleeps.

The TV output needs physically contiguous memory, taken from the ION exynos contig heap. That heap is a fixed 16 MB reserve (`CONFIG_ION_EXYNOS_CONTIGHEAP_SIZE`), separate from the camera's FIMC memory.
- While the gallery shows photos, hdmid holds two full-screen buffers, allocated together into the empty heap: 2 x 8.3 MB at 1080p, which fits the 16 MB only that way. Freeing and reallocating buffers of changing sizes fragmented the heap, and the second buffer then failed.
- The layer always covers the whole screen, and each picture is copied into its middle, with transparent bars (the mixer's black background shows through). A new picture goes into the buffer not on screen and replaces the old one at a vsync (the mixer's address register is shadowed): nothing blanks or flickers. Resizing the layer for every picture blanked it each time.
- If only one buffer fits, hdmid rewrites that one with the layer blanked meanwhile.
- Half-size frames (the zoom in motion) use a half-size layer with the mixer's 2x scale (`S5PTVFB_SCALING`). The driver loses the scale whenever it enables the layer: `s5p_mixer_ctrl_set_reg` sets the scale bits, then rewrites the size register. So hdmid enables the layer only when it was off, and sets the scale again right after.
- The buffers are mapped write-combined (`ION_EXYNOS_NONCACHE_MASK`). A cached mapping let the CPU's writes sit in its caches while the mixer read memory: runs of 8 or 16 stale pixels (one or two 32-byte cache lines) that were corrected as the lines were evicted.

### Kernel

Both defconfigs (`lineageos_gd1_defconfig`, `lineageos_sf2wifi_defconfig`) need the first two:

- `CONFIG_HDMI_CONTROLLED_BY_EXT_IC=y`: the GC1 board (and the GC200, which builds as GC1 + SF2) has an external HDMI IC (level shifter) enabled by `GPIO_HDMI_EN` (GPL0(4)). Without this option the pin was never raised and DDC read only zeros, so there was no EDID. Samsung's stock GC1 defconfig has it.
- `CONFIG_HDMI_TX_STRENGTH=y`: Samsung's TX amplitude tuning for the GC1 (`gc1_hdmi_tx_val` in mach-midas.c), also from the stock defconfig.
- 720x480 4:3 (CTA VIC 2) now sends limited-range RGB like every other mode. The driver used to send it full range, as if it were 640x480 VGA, which a TV shows with crushed blacks and clipped whites.

## 2. Modes

hdmid drives every mode the s5p-tvout driver has (`hdmid/hdmi_modes.h`). "Auto" (the default) picks the first mode in this order that the sink lists in its EDID, by VIC or by a detailed or standard timing:

| Mode | Size | Refresh | Notes |
|---|---|---|---|
| 1080p60 | 1920x1080 | 60 Hz | bus locked at 400 MHz while on |
| 1080p59 | 1920x1080 | 59.94 Hz | bus locked at 400 MHz |
| 1080p50 | 1920x1080 | 50 Hz | bus locked at 400 MHz |
| 1080p30 | 1920x1080 | 30 Hz | same pixel clock as 720p60 |
| 1080i60, 1080i59, 1080i50 | 1920x1080 | 60, 59.94, 50 fields/s | interlaced |
| 720p60, 720p59, 720p50 | 1280x720 | 60, 59.94, 50 Hz | |
| 576p50, 576p50-4:3 | 720x576 | 50 Hz | non-square pixels |
| 480p60, 480p59, 480p60-4:3 | 720x480 | 60, 59.94 Hz | non-square pixels |

- Without a readable EDID it uses 720p60, which every HD sink takes.
- A sink with an EDID but without the HDMI vendor block is DVI: output 3, with no InfoFrames and no audio.
- The 480p and 576p modes have non-square pixels. GC Gallery scales the photo by the mode's display aspect ratio, and hdmid narrows or widens its notice the same way.
- RGB is limited range (16-235) in every mode, as CTA-861 sets for these formats and as Linux's "Broadcast RGB: automatic" does.
- **Choosing a mode**: the TV button in GC Gallery's viewer (shown only while a TV is connected) lists Auto and every mode, marking the ones the TV does not list. The choice is `persist.vendor.hdmi.mode` (`auto` or a mode name), so `setprop persist.vendor.hdmi.mode 1080p30` works from adb too, taking effect on the next plug-in.
- Status: `vendor.hdmi.state` (on/off), `vendor.hdmi.mode`, `vendor.hdmi.sink` (the EDID's monitor name).

## 3. Control protocol (`/dev/socket/hdmid`)

A stream socket. Every message is a 32-byte little-endian header, `u32 magic` (0x44484347, "GCHD"), `u32 type`, `u32 len`, `u32 a[5]`, followed by `len` bytes.

| Type | Direction | Arguments | Payload |
|---|---|---|---|
| 1 HELLO | gallery → hdmid | | none; hdmid answers with STATE (optional: hdmid sends STATE on connect anyway) |
| 2 SHOW | gallery → hdmid | a0 width, a1 height, a2 flags (1 = half size, doubled by the mixer) | width x height x 4 bytes, R, G, B, A (Android's ARGB_8888 in memory), rows top to bottom |
| 3 CLEAR | gallery → hdmid | | none; back to the notice |
| 4 SET_MODE | gallery → hdmid | | the mode name or `auto` |
| 16 STATE | hdmid → gallery | | text, one `key=value` per line |

- A picture is at most the mode's size (half of it with flag 1) and is shown centred on black.
- STATE keys: `connected` (0/1), `mode`, `width`, `height`, `dar` (16:9 or 4:3), `auto` (0/1), `dvi` (0/1), `sink`, and `modes`, every mode as `name:1` when the sink lists it or `name:0` when it does not.
- hdmid sends STATE on connect, on HELLO, on plug-in and unplug, and after SET_MODE. After a new STATE the gallery sends its photo again.
- **Who may connect**: hdmid reads the peer's uid (SO_PEERCRED) and serves GC Gallery (its uid from `/data/system/packages.list`), root, system and shell. Anyone else is disconnected.
- One client at a time: a new connection replaces the old one. When the gallery disconnects, the TV goes back to the notice.

## 4. Testing with a capture card

The modes were checked with an Elgato Cam Link 4K, which reports the incoming resolution and frame rate and passes the pixels through. The tests use `tools/hdmi-test.sh` with a root adb shell on the camera; the script stops hdmid while it runs and starts it again at the end.

- `tools/hdmi-test.sh check`: `gd1_hdmitest` (device tree `bringup-tools/`, built with `m` on its output path because it is not installable) shows a test pattern in each mode, and `tools/hdmi-check.py` checks one captured frame. It checks the size, the 1-pixel border on every edge (no cropping), the corner colours (orientation), the colour bars, the 16-step grey ramp (range and monotonicity) and a ring that must be round on the display (the pixel aspect ratio).
- `tools/hdmi-test.sh stability`: 40 s per mode. `tools/hdmi-monitor.py` compares every frame with the first ones; on a static picture they must all be identical.
  - Read the frames from a pipe: writing raw frames to disk at 100-250 MB/s while capturing disturbed the capture itself.
- `tools/hdmi-test.sh edid`: the sink's EDID, read over DDC. The Cam Link 4K lists VICs 1-5, 16, 17, 19, 20, 31-34, 60-62 and 93-95 (4K, which this hardware cannot send).

Results (GC100, 2026-11-13, kernel #101):

- **check**: all 15 modes pass at their own size and rate (the Cam Link reports 60.0002, 59.9402, 50, 29.97, 30 and 25 fps as expected). The same on the GC200, where stability was checked in 1080p60, 720p60 and 480p 4:3 (0 changed frames).
- **stability**: 0 changed frames in all 15 modes, about 30,000 frames in all.
- **GC Gallery** at 1080p60, a 16 MP 4:3 shot (1440x1080 on the TV):
  - from the screen decode: drawn in 90-440 ms, sent in 35-180 ms;
  - the sharp decode: 1.5-2.5 s later;
  - no seams at the band edges (the row-to-row difference there is within the picture's own).
  - At 480p a 4:3 photo is 540x480 pixels, 1.333 on the 16:9 screen.
  - Zoomed in to about 300%: the visible 2010x1130 region on the TV at 1920x1080. Half-size frames during the movement every 40-115 ms; full size about 280 ms after it stops, sharp (1/1) after about 1 s.
- **The camera is not slowed**: GC Camera RAW+JPEG shots with the TV on (hdmid's notice at 1080p60) took 4.34-4.39 s to the JPEG and 5.29-5.34 s back to live view, against 4.37-4.41 s and 5.36-5.46 s with the output off. With the camera open, the contig heap held only hdmid's 1.9 MB notice (16.6 MB, the two full-screen photo buffers, while the gallery is on the TV).
- **Screen off and on**: the power button turns the output off (no signal, bus lock released); waking turns it on, and the viewer reconnects and sends its photo again.
  - hdmid follows `debug.tracing.screen_state`, which DisplayPowerController sets on every display state change. It waits with `__system_property_wait`, so there is no polling.
  - The kernel's own hook (s5p-tvout's framebuffer notifier) did not stop the output when the panel blanked.

## 5. Open

- GC Camera's live view on the TV (docs/TODO.md).
- Full-range RGB for sinks that allow it (EDID QS bit): a kernel change.
- HDMI audio is not wired.
- Hot-plug while running was tested on 2026-11-08 with the old placeholder daemon; repeat it with hdmid on a TV.
