#!/usr/bin/env python3
"""Inspect a raw frame dumped by the gd1 camera HAL (persist.vendor.camera.raw=1).

m9mo.c does not document the M9MO raw output, so this tool works it out from
the data:

  1. finds the row stride in bytes: the shift at which the bytes repeat best
     (neighbouring rows are alike; for a Bayer mosaic the same-colour row is
     two rows away, so both the stride and twice the stride score well);
  2. derives the bits per pixel from the stride and the expected width, and
     unpacks as 8-bit, MIPI RAW10, MIPI RAW12 or 16-bit little endian;
  3. writes a half-size grey preview (each 2x2 Bayer cell averaged) and a
     per-channel mean for each of the four Bayer positions, which shows the
     colour order (the two greens match, red and blue differ).

Found on the GC100 (FCGB04 firmware, 2026-11-01): 20440000 bytes per frame,
a continuous big-endian 10-bit stream (raw10be), 4736 pixels (5920 bytes) per
row, 3452 rows plus a 4160-byte tail; columns 0-1 and the last 64 are zero.
Bayer order GRBG, black level 64 (dark frame: 63.2-63.6 on all channels),
white level 1023. The frame is the whole lens image circle, without the
distortion and vignetting correction and crop that the ISP applies to its
JPEG.

Usage: m9mo-raw.py IMG_x.raw [--width 4736] [--stride N] [--format raw10be]
"""

import argparse
import os
import sys

import numpy as np
from PIL import Image


def find_stride(data, width):
    """Row stride candidates in bytes, best first, as (score, stride).

    Only plausible strides are tried: width x 8/10/12/16 bits plus up to 512
    bytes of padding, and the exact divisors for 3456..3520 rows. A stride
    scores by the mean byte difference to the row below or, for a Bayer
    mosaic, to the same-colour row two rows below.
    """
    # from the middle of the frame, away from any header or dark rows
    mid = len(data) // 2
    sample = data[mid : mid + (512 << 10)].astype(np.int16)
    cands = set()
    for bits in (8, 10, 12, 16):
        base = width * bits // 8
        cands.update(range(base, base + 513))
    for rows in range(3456, 3521):
        if len(data) % rows == 0:
            cands.add(len(data) // rows)

    def diff(s):
        return np.abs(sample[s:] - sample[:-s]).mean()

    return sorted((min(diff(s), diff(2 * s)), s) for s in cands)


def unpack(data, fmt, stride, width, height):
    rows = data[: stride * height].reshape(height, stride)
    if fmt == "raw8":
        return rows[:, :width].astype(np.uint16)
    if fmt == "raw10":
        # MIPI: 4 pixels in 5 bytes, the 5th byte holds the 2 low bits of each
        g = rows[:, : width * 5 // 4].reshape(height, -1, 5).astype(np.uint16)
        out = np.empty((height, g.shape[1], 4), np.uint16)
        for i in range(4):
            out[:, :, i] = (g[:, :, i] << 2) | ((g[:, :, 4] >> (2 * i)) & 3)
        return out.reshape(height, -1)[:, :width]
    if fmt == "raw10be":
        # A continuous big-endian 10-bit stream: 4 pixels in 5 bytes, MSB first
        g = rows[:, : width * 5 // 4].reshape(height, -1, 5).astype(np.uint16)
        out = np.empty((height, g.shape[1], 4), np.uint16)
        out[:, :, 0] = (g[:, :, 0] << 2) | (g[:, :, 1] >> 6)
        out[:, :, 1] = ((g[:, :, 1] & 0x3F) << 4) | (g[:, :, 2] >> 4)
        out[:, :, 2] = ((g[:, :, 2] & 0x0F) << 6) | (g[:, :, 3] >> 2)
        out[:, :, 3] = ((g[:, :, 3] & 0x03) << 8) | g[:, :, 4]
        return out.reshape(height, -1)[:, :width]
    if fmt == "raw12":
        # MIPI: 2 pixels in 3 bytes, the 3rd byte holds the 4 low bits of each
        g = rows[:, : width * 3 // 2].reshape(height, -1, 3).astype(np.uint16)
        out = np.empty((height, g.shape[1], 2), np.uint16)
        out[:, :, 0] = (g[:, :, 0] << 4) | (g[:, :, 2] & 0xF)
        out[:, :, 1] = (g[:, :, 1] << 4) | (g[:, :, 2] >> 4)
        return out.reshape(height, -1)[:, :width]
    if fmt == "raw16":
        return rows[:, : width * 2].copy().view("<u2")[:, :width]
    raise ValueError(fmt)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("file")
    ap.add_argument("--width", type=int, default=4736)
    # 10-bit black level from a dark frame (2026-11-01); the camera HAL
    # reports the current value as raw-black-level
    ap.add_argument("--black", type=float, default=64)
    ap.add_argument("--stride", type=int, help="row stride in bytes (default: detect)")
    ap.add_argument("--format", choices=["raw8", "raw10", "raw10be", "raw12", "raw16"],
                    help="pixel packing (default: from stride / width)")
    a = ap.parse_args()

    data = np.fromfile(a.file, np.uint8)
    size = len(data)
    print(f"{a.file}: {size} bytes, {size / (a.width * 3456):.4f} bytes/pixel at {a.width}x3456")
    print("first 64 bytes:", data[:64].tobytes().hex(" "))
    nz = np.flatnonzero(data[::-1])
    print(f"trailing zero bytes: {nz[0] if len(nz) else size}")

    stride = a.stride
    if stride is None:
        scores = find_stride(data, a.width)
        print("best strides (bytes, mean |diff|):",
              ", ".join(f"{s} ({d:.2f})" for d, s in scores[:6]))
        stride = scores[0][1]
    height = size // stride // 2 * 2
    bpp = stride * 8 / a.width
    print(f"stride {stride} bytes, {height} rows, {bpp:.2f} bits/pixel at width {a.width}")

    fmt = a.format
    if fmt is None:
        fmt = min([(8, "raw8"), (10, "raw10"), (12, "raw12"), (16, "raw16")],
                  key=lambda t: abs(t[0] - bpp))[1]
        if fmt == "raw10":
            # MIPI or a plain bit stream: the right one makes same-colour
            # neighbours (two pixels apart) alike
            def rough(f):
                v = unpack(data, f, stride, a.width, 16)[8:].astype(np.float64)
                return np.abs(v[:, 2:] - v[:, :-2]).mean() / (v.std() + 1e-9)
            fmt = min(["raw10", "raw10be"], key=rough)
    img = unpack(data, fmt, stride, a.width, height)
    bits = {"raw8": 8, "raw10": 10, "raw10be": 10, "raw12": 12, "raw16": 16}[fmt]
    print(f"format {fmt}: min {img.min()} max {img.max()} mean {img.mean():.1f} (of {(1 << bits) - 1})")

    h2, w2 = height // 2 * 2, a.width // 2 * 2
    cells = img[:h2, :w2].reshape(h2 // 2, 2, w2 // 2, 2).astype(np.float64)
    for (y, x) in [(0, 0), (0, 1), (1, 0), (1, 1)]:
        print(f"  Bayer position ({y},{x}): mean {cells[:, y, :, x].mean():.1f}")

    grey = np.clip(cells.mean(axis=(1, 3)) - a.black, 0, None)
    grey = np.clip(grey / max(np.percentile(grey, 99.5), 1), 0, 1) ** (1 / 2.2) * 255
    out = os.path.splitext(a.file)[0] + f"-{fmt}-preview.png"
    Image.fromarray(grey.astype(np.uint8)).save(out)
    print("preview:", out)


if __name__ == "__main__":
    sys.exit(main())
