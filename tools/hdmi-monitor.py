#!/usr/bin/env python3
"""Per-second count of changed frames in a static HDMI picture, read live from ffmpeg.

Usage: ffmpeg ... -pixel_format yuyv422 ... -f rawvideo - | hdmi-monitor.py <w> <h> <seconds> <fps>

The reference is the per-pixel median of the first 30 frames; a frame is bad
when any sample differs from it by more than 6. Prints one line per second:
wall-clock time, bad frames, worst count of differing samples. With a static
picture on a clean link every frame is identical.

Read from a pipe, not a file: writing hundreds of MB/s to disk while
capturing corrupted the capture itself (docs/HDMI.md). Needs numpy.
"""
import sys
import time

import numpy as np

W, H, SECS, FPS = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4])
size = W * H * 2
inp = sys.stdin.buffer


def frame():
    buf = inp.read(size)
    if len(buf) < size:
        sys.exit(0)
    return np.frombuffer(buf, np.uint8).astype(np.int16)


ref = np.median(np.stack([frame() for _ in range(30)]), axis=0).astype(np.int16)
per = int(round(FPS))
for s in range(SECS):
    bad, worst = 0, 0
    for _ in range(per):
        d = int((np.abs(frame() - ref) > 6).sum())
        if d:
            bad += 1
            worst = max(worst, d)
    print(f"{time.time():.1f} bad {bad:3d}/{per} worst {worst}", flush=True)
