#!/usr/bin/env python3
"""Checks one capture-card frame of the gd1_hdmitest pattern (docs/HDMI.md).

Usage: hdmi-check.py <frame.png> <w> <h> <dar_w> <dar_h>
Prints one line per check and a final PASS/FAIL; the exit status follows.
Needs numpy and Pillow.
"""
import sys

import numpy as np
from PIL import Image

png, W, H, DW, DH = sys.argv[1], *map(int, sys.argv[2:6])
img = np.asarray(Image.open(png).convert("RGB")).astype(int)
ok = True


def check(name, cond, detail):
    global ok
    ok &= bool(cond)
    print(f"  {'ok ' if cond else 'BAD'} {name}: {detail}")


def mean(x0, y0, x1, y1):
    return img[y0:y1, x0:x1].reshape(-1, 3).mean(axis=0)


def close(c, ref, tol=40):
    return all(abs(a - b) <= tol for a, b in zip(c, ref))


h, w = img.shape[:2]
check("size", (w, h) == (W, H), f"{w}x{h}, expected {W}x{H}")
if (w, h) != (W, H):
    print("FAIL")
    sys.exit(1)

# Border: rows and columns 0 and last are white (no cropping, no overscan)
for name, px in (("top", img[0, 8:-8]), ("bottom", img[-1, 8:-8]),
                 ("left", img[8:-8, 0]), ("right", img[8:-8, -1])):
    m = px.mean(axis=0)
    check(f"border {name}", m.min() > 150, "rgb " + " ".join(f"{v:.0f}" for v in m))

# Corner squares: orientation
sq = H // 27
c = sq // 2 + 4
for name, (x, y), ref in (("TL white", (c, c), (255, 255, 255)), ("TR red", (W - c, c), (255, 0, 0)),
                          ("BL green", (c, H - c), (0, 255, 0)), ("BR blue", (W - c, H - c), (0, 0, 255))):
    m = mean(x - 2, y - 2, x + 2, y + 2)
    check(f"corner {name}", close(m, ref, 60), "rgb " + " ".join(f"{v:.0f}" for v in m))

# Colour bars
bars = [(255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0), (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0)]
margin = W // 16
bw = (W - 2 * margin) // 8
y = H * 22 // 100 + H * 15 // 100
bad = []
for i, ref in enumerate(bars):
    x = margin + i * bw + bw // 2
    m = mean(x - 3, y - 3, x + 3, y + 3)
    if not close(m, ref, 60):
        bad.append(f"{i}:{m.round().astype(int).tolist()}")
check("colour bars", not bad, "all 8 match" if not bad else " ".join(bad))

# Grey ramp: 16 steps of 17. The GC100 sends limited-range RGB (16-235) in CTA
# modes, which a capture card may pass through as full range: accept either.
bw = (W - 2 * margin) // 16
y = H * 56 // 100 + H * 5 // 100
steps = []
for i in range(16):
    x = margin + i * bw + bw // 2
    steps.append(mean(x - 2, y - 2, x + 2, y + 2).mean())
steps = np.array(steps)
full = np.arange(16) * 17
limited = 16 + full * 219 / 255
rng, ref = ("limited", limited) if steps[0] > 8 else ("full", full)
err = np.abs(steps - ref)
mono = all(b > a for a, b in zip(steps, steps[1:]))
check("grey ramp", mono and err.max() <= 8,
      f"{rng} range, black {steps[0]:.0f} white {steps[-1]:.0f} max err {err.max():.1f} monotonic {mono}")

# Ring: round on the display once the pixel aspect ratio is applied
cx, cy = W // 2, int(H * 0.82)
ry = H * 0.12
yellow = (img[:, :, 0] > 150) & (img[:, :, 1] > 120) & (img[:, :, 2] < 120)
region = yellow[int(cy - ry * 1.3):int(cy + ry * 1.3) + 1, :]
ys, xs = np.nonzero(region)
if len(xs):
    wpx = xs.max() - xs.min() + 1
    hpx = ys.max() - ys.min() + 1
    par = DW * H / (DH * W)
    ratio = wpx * par / hpx
    check("ring round on display", abs(ratio - 1) < 0.04, f"{wpx}x{hpx} px, display aspect {ratio:.3f}")
else:
    check("ring round on display", False, "ring not found")

print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
