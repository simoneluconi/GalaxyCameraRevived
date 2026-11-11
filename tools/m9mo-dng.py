#!/usr/bin/env python3
"""Convert a Galaxy Camera (EK-GC100 or EK-GC200) M9MO raw frame to DNG.

The camera HAL saves each RAW shot (persist.vendor.camera.raw=1) as
/data/vendor/camera/IMG_<time>.raw, plus IMG_<time>.raw.txt with the frame
layout (raw-*) and the exposure the ISP used (exif-*). This writes an
uncompressed 16-bit CFA DNG that darktable, RawTherapee and Lightroom open.

Formats: raw10be (GC100: 4736x3452 plus a 4160-byte tail) and raw12be
(GC200: 4736x3494, no tail), both continuous big-endian bit streams.

Known gaps, see docs/TODO.md ("Image quality and RAW"):
  - ColorMatrix1 is a placeholder that treats the sensor as linear sRGB;
    it needs a real profile (dcamprof from a ColorChecker shot);
  - AsShotNeutral is a gray-world estimate, not the ISP's white balance;
  - no lens correction yet: the frame is the whole uncorrected image
    circle (strong barrel distortion and vignetting);
  - exposure time and aperture are the ISP's APEX values, rounded to the
    standard steps;
  - the ISP's EXIF flash value is not written: it read 9 ("fired") with the
    pop-up flash down and the mode "on", so it seems to follow the mode.

Usage: m9mo-dng.py IMG_x.raw [-o out.dng] [--black N] [--white N]
"""

import argparse
import datetime
import math
import os
import struct
import sys
from fractions import Fraction

import numpy as np

# Defaults: the layout measured on 2026-11-01 (tools/m9mo-raw.py), used when
# the .txt sidecar is missing or older. Columns 4666-4735 are zero too
# (checked 2026-11-04), so the active area ends at 4666 (exclusive).
DEFAULTS = {
    "raw-size": "4736x3452",
    "raw-format": "raw10be",
    "raw-cfa": "grbg",
    "raw-active-area": "2,0,4666,3452",
    "raw-black-level": "64",
    "raw-white-level": "1023",
}

CFA_CODES = {"r": 0, "g": 1, "b": 2}

# The M9MO's shutter speeds (m9mo_set_shutter_speed(), the HAL's
# shutter-speed-values), in seconds
SHUTTER_STEPS = [Fraction(s) for s in (
    "16", "12", "8", "6", "4", "3", "2", "3/2", "1", "7/10", "1/2", "1/3", "1/4",
    "1/6", "1/8", "1/15", "1/20", "1/30", "1/45", "1/60", "1/90", "1/125", "1/180",
    "1/250", "1/350", "1/500", "1/750", "1/1000", "1/1500", "1/2000")]

# XYZ (D65) -> linear sRGB, used as the placeholder ColorMatrix1
XYZ_TO_SRGB = [
    [3.2406, -1.5372, -0.4986],
    [-0.9689, 1.8758, 0.0415],
    [0.0557, -0.2040, 1.0570],
]


def read_sidecar(raw_path):
    meta = dict(DEFAULTS)
    txt = raw_path + ".txt"
    if os.path.exists(txt):
        with open(txt) as f:
            for line in f:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    meta[k] = v
    else:
        print(f"warning: no {txt}, using the default layout and no exposure data")
    return meta


def unpack_raw10be(data, width, height):
    stride = width * 10 // 8
    g = data[: stride * height].reshape(height, stride // 5, 5).astype(np.uint16)
    out = np.empty((height, stride // 5, 4), np.uint16)
    out[:, :, 0] = (g[:, :, 0] << 2) | (g[:, :, 1] >> 6)
    out[:, :, 1] = ((g[:, :, 1] & 0x3F) << 4) | (g[:, :, 2] >> 4)
    out[:, :, 2] = ((g[:, :, 2] & 0x0F) << 6) | (g[:, :, 3] >> 2)
    out[:, :, 3] = ((g[:, :, 3] & 0x03) << 8) | g[:, :, 4]
    return out.reshape(height, width)


def unpack_raw12be(data, width, height):
    """2 pixels in 3 bytes, MSB first (the GC200's ISP, measured 2026-11-11)."""
    stride = width * 12 // 8
    g = data[: stride * height].reshape(height, stride // 3, 3).astype(np.uint16)
    out = np.empty((height, stride // 3, 2), np.uint16)
    out[:, :, 0] = (g[:, :, 0] << 4) | (g[:, :, 1] >> 4)
    out[:, :, 1] = ((g[:, :, 1] & 0x0F) << 8) | g[:, :, 2]
    return out.reshape(height, width)


UNPACK = {"raw10be": unpack_raw10be, "raw12be": unpack_raw12be}
# The camera each format comes from, for the Model tags
MODEL = {"raw10be": "EK-GC100", "raw12be": "EK-GC200"}


def rational(x, den=10000):
    """A float as an (num, den) rational."""
    return (int(round(x * den)), den)


class Ifd:
    """A TIFF IFD: tag -> (type, count, packed value bytes)."""

    TYPES = {"BYTE": (1, "B"), "ASCII": (2, "s"), "SHORT": (3, "H"), "LONG": (4, "I"),
             "RATIONAL": (5, "II"), "SRATIONAL": (10, "ii"), "UNDEFINED": (7, "B")}

    def __init__(self):
        self.entries = {}

    def add(self, tag, kind, values):
        code, fmt = self.TYPES[kind]
        if kind == "ASCII":
            raw = values.encode("ascii") + b"\0"
            self.entries[tag] = (code, len(raw), raw)
            return
        if not isinstance(values, (list, tuple)) or (kind in ("RATIONAL", "SRATIONAL")
                                                     and isinstance(values[0], int)):
            values = [values]
        raw = b""
        for v in values:
            raw += struct.pack("<" + fmt, *(v if isinstance(v, tuple) else (v,)))
        self.entries[tag] = (code, len(values), raw)

    def size(self):
        n = 2 + 12 * len(self.entries) + 4
        extra = sum(len(r) + (len(r) & 1) for _, _, r in self.entries.values() if len(r) > 4)
        return n + extra

    def pack(self, offset, next_ifd=0):
        """The IFD bytes when placed at file offset `offset`."""
        tags = sorted(self.entries)
        head = struct.pack("<H", len(tags))
        data = b""
        data_off = offset + 2 + 12 * len(tags) + 4
        for tag in tags:
            code, count, raw = self.entries[tag]
            if len(raw) <= 4:
                head += struct.pack("<HHI", tag, code, count) + raw.ljust(4, b"\0")
            else:
                head += struct.pack("<HHII", tag, code, count, data_off + len(data))
                data += raw + (b"\0" if len(raw) & 1 else b"")
        return head + struct.pack("<I", next_ifd) + data


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("raw")
    ap.add_argument("-o", "--output")
    ap.add_argument("--black", type=float, help="override raw-black-level")
    ap.add_argument("--white", type=float, help="override raw-white-level")
    a = ap.parse_args()

    meta = read_sidecar(a.raw)
    fmt = meta["raw-format"]
    if fmt not in UNPACK:
        sys.exit(f"unsupported raw-format {fmt}")
    model = MODEL[fmt]
    width, height = (int(x) for x in meta["raw-size"].split("x"))
    left, top, right, bottom = (int(x) for x in meta["raw-active-area"].split(","))
    black = a.black if a.black is not None else float(meta["raw-black-level"])
    white = a.white if a.white is not None else float(meta["raw-white-level"])
    cfa = [CFA_CODES[c] for c in meta["raw-cfa"].lower()]

    img = UNPACK[fmt](np.fromfile(a.raw, np.uint8), width, height)

    # Gray-world white balance on the central part of the active area, clear
    # of the dark corners of the uncorrected image circle
    act = img[top:bottom, left:right].astype(np.float64) - black
    h, w = act.shape
    # even bounds keep the CFA phase
    c = act[h // 8 * 2: 3 * h // 8 * 2, w // 8 * 2: 3 * w // 8 * 2]
    ch = {}
    for i, (y, x) in enumerate([(0, 0), (0, 1), (1, 0), (1, 1)]):
        ch.setdefault(cfa[i], []).append(np.clip(c[y::2, x::2], 0, None).mean())
    r, g, b = (np.mean(ch[k]) for k in (0, 1, 2))
    if min(r, g, b) <= 0:
        # A black frame (lens cap): no white balance to estimate
        neutral = [1.0, 1.0, 1.0]
    else:
        neutral = [r / g, 1.0, b / g]

    # Exposure from the ISP (APEX x100)
    iso = int(meta.get("exif-iso", 0))
    tv = int(meta.get("exif-tv", 0))
    av = int(meta.get("exif-av", 0))
    ebv = int(meta.get("exif-ebv", 0))

    now = datetime.datetime.fromtimestamp(os.path.getmtime(a.raw)).strftime("%Y:%m:%d %H:%M:%S")
    base = os.path.basename(a.raw)
    if base.startswith("IMG_") and len(base) >= 19:
        try:
            now = datetime.datetime.strptime(base[4:19], "%Y%m%d_%H%M%S").strftime("%Y:%m:%d %H:%M:%S")
        except ValueError:
            pass

    exif = Ifd()
    exif.add(36864, "UNDEFINED", list(b"0230"))  # ExifVersion
    if tv:
        # The nominal ISP step nearest to 2^-TV (TV 797 is 1/250, not 1/251)
        t = 2.0 ** (-tv / 100.0)
        frac = min(SHUTTER_STEPS, key=lambda s: abs(math.log(float(s) / t)))
        exif.add(33434, "RATIONAL", (frac.numerator, frac.denominator))
    if av:
        exif.add(33437, "RATIONAL", rational(2.0 ** (av / 200.0), 10))
    if iso:
        exif.add(34855, "SHORT", iso)
    exif.add(36867, "ASCII", now)
    exif.add(37380, "SRATIONAL", (ebv, 100))
    # The ISP reports FL in mm x1000 and FL_35mm in mm x100 (4100 and 2300
    # at the wide end, which is 4.1 mm / 23 mm equivalent)
    fl = int(meta.get("exif-fl", 0))
    fl35 = int(meta.get("exif-fl-35mm", 0))
    if fl:
        exif.add(37386, "RATIONAL", (fl, 1000))
    if fl35:
        exif.add(41989, "SHORT", int(round(fl35 / 100)))

    ifd = Ifd()
    ifd.add(254, "LONG", 0)
    ifd.add(256, "LONG", width)
    ifd.add(257, "LONG", height)
    ifd.add(258, "SHORT", 16)
    ifd.add(259, "SHORT", 1)
    ifd.add(262, "SHORT", 32803)
    ifd.add(271, "ASCII", "Samsung")
    ifd.add(272, "ASCII", model)
    ifd.add(273, "LONG", 0)  # strip offset, patched below
    ifd.add(274, "SHORT", 1)
    ifd.add(277, "SHORT", 1)
    ifd.add(278, "LONG", height)
    ifd.add(279, "LONG", width * height * 2)
    ifd.add(284, "SHORT", 1)
    ifd.add(305, "ASCII", "GalaxyCameraRevived m9mo-dng.py")
    ifd.add(306, "ASCII", now)
    ifd.add(33421, "SHORT", [2, 2])
    ifd.add(33422, "BYTE", cfa)
    ifd.add(34665, "LONG", 0)  # Exif IFD offset, patched below
    ifd.add(50706, "BYTE", [1, 4, 0, 0])
    ifd.add(50707, "BYTE", [1, 1, 0, 0])
    ifd.add(50708, "ASCII", "Samsung " + model)
    ifd.add(50710, "BYTE", [0, 1, 2])
    ifd.add(50711, "SHORT", 1)
    ifd.add(50713, "SHORT", [1, 1])
    ifd.add(50714, "RATIONAL", rational(black, 100))
    ifd.add(50717, "LONG", int(white))
    ifd.add(50721, "SRATIONAL", [rational(v) for row in XYZ_TO_SRGB for v in row])
    ifd.add(50728, "RATIONAL", [rational(v) for v in neutral])
    ifd.add(50778, "SHORT", 21)  # CalibrationIlluminant1: D65
    ifd.add(50829, "LONG", [top, left, bottom, right])

    # Layout: header, IFD0, Exif IFD, image data
    ifd0_off = 8
    exif_off = ifd0_off + ifd.size()
    data_off = exif_off + exif.size()
    data_off += data_off & 1
    ifd.add(273, "LONG", data_off)
    ifd.add(34665, "LONG", exif_off)

    out = a.output or os.path.splitext(a.raw)[0] + ".dng"
    with open(out, "wb") as f:
        f.write(b"II*\0" + struct.pack("<I", ifd0_off))
        f.write(ifd.pack(ifd0_off))
        f.write(exif.pack(exif_off))
        f.write(b"\0" * (data_off - f.tell()))
        f.write(img.astype("<u2").tobytes())

    fnum = 2.0 ** (av / 200.0) if av else 0
    print(f"{out}: {width}x{height} {meta['raw-cfa'].upper()}, black {black:g}, white {white:g}, "
          f"neutral {neutral[0]:.3f},1,{neutral[2]:.3f}")
    print(f"  ISO {iso}, 1/{2 ** (tv / 100):.0f} s (TV {tv}), f/{fnum:.1f} (AV {av}), "
          f"EV bias {ebv / 100:+.2f}, {fl / 1000:g} mm ({fl35 / 100:g} mm equivalent)")


if __name__ == "__main__":
    main()
