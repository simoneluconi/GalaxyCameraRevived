#!/usr/bin/env python3
"""Fit the GC100's colour matrix against the ISP's own JPEGs (no ColorChecker).

For each GC Camera DNG + ISP JPEG pair of the same shot it samples flat patches of the JPEG,
undoes the ISP's tone curve, finds the same place in the RAW through the lens model, and fits

    isp_linear_srgb = exposure * M * (camera_rgb / neutral)

with one 3x3 M (rows sum to 1, so white stays white) shared by all pairs, and a neutral and an
exposure per pair. It reports the colour error (CIE76 dE) of the matrices gccore uses now and of
the fitted one, and the DNG ColorMatrix (XYZ -> camera) the fit implies for the pairs' light.

The numbers it mirrors are gccore's (camera-app/gccore): the tone curve knots in src/isp.cpp,
camToSrgb in include/gccore/isp.h, the lens table in src/lens.cpp, the geometry in
include/gccore/lens.h and ColorMatrix1 in include/gccore/dng.h. Keep them in step.

With --compare OUT.jpg it also renders some pairs the way a raw editor develops the DNG (the
ColorMatrix, a Bradford adaptation to the shot's neutral, then the ISP tone curve so that only
the colour differs), next to the ISP JPEG, with the current and the fitted ColorMatrix.

Usage: gc100-colour-fit.py PAIR_DIR [--names GC100_0279 ...] [--skip GC100_0289 ...]
                           [--compare OUT.jpg --show GC100_0281 ...]
Needs numpy, scipy, Pillow, rawpy.
"""
import argparse
import glob
import os
import sys

import numpy as np
import rawpy
from PIL import Image, ImageDraw
from scipy.ndimage import map_coordinates
from scipy.optimize import least_squares

# gccore/src/isp.cpp: the ISP tone curve (linear, 1.0 = raw white after WB -> 8-bit)
CURVE_X = np.array([0.00200, 0.00323, 0.00520, 0.00839, 0.01354, 0.02183,
                    0.03521, 0.05680, 0.09161, 0.14776, 0.23832, 0.38439])
CURVE_Y = np.array([3.22, 4.69, 6.98, 11.08, 18.70, 26.36, 39.08, 64.03, 93.23, 126.53, 162.63, 199.75])
# gccore/include/gccore/isp.h: white-balanced camera RGB -> the ISP's linear sRGB
CAM_TO_SRGB = np.array([1.8601, -0.7230, -0.1371, -0.5486, 1.7591, -0.2105,
                        -0.1565, -0.6081, 1.7646]).reshape(3, 3)
# gccore/include/gccore/dng.h: ColorMatrix1, XYZ(D65) -> camera
COLOR_MATRIX1 = np.array([1.2027, -0.3131, -0.1016, 0.1819, 0.7463, 0.0742,
                          0.1216, 0.0409, 0.2983]).reshape(3, 3)
# gccore/src/lens.cpp and lens.h
LENS = [(4.1, -0.269, 0.117), (5.0, -0.177, 0.050), (6.2, -0.090, 0.008), (9.2, -0.025, 0.010), (16.5, 0, 0)]
CENTRE_X, CENTRE_Y, MODEL_R = 2331.0, 1739.0, 2880.0
ISP_W, ISP_H = 4608, 3456

# linear sRGB (D65) <-> XYZ
SRGB_TO_XYZ = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
XYZ_TO_SRGB = np.linalg.inv(SRGB_TO_XYZ)
D65 = SRGB_TO_XYZ @ np.ones(3)


BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])


def tone_curve(x):
    """gccore toneCurve(): linear -> 8-bit ISP value."""
    x = np.asarray(x, float)
    out = np.empty_like(x)
    lo = x < CURVE_X[0]
    out[lo] = CURVE_Y[0] * np.maximum(x[lo], 0) / CURVE_X[0]
    hi = x >= CURVE_X[-1]
    slope = (CURVE_Y[-1] - CURVE_Y[-2]) / np.log(CURVE_X[-1] / CURVE_X[-2]) / CURVE_X[-1]
    head = 255.0 - CURVE_Y[-1]
    out[hi] = 255.0 - head * np.exp(-slope * (x[hi] - CURVE_X[-1]) / head)
    mid = ~(lo | hi)
    k = np.searchsorted(CURVE_X, x[mid], side='right') - 1
    t = np.log(x[mid] / CURVE_X[k]) / np.log(CURVE_X[k + 1] / CURVE_X[k])
    out[mid] = CURVE_Y[k] + (CURVE_Y[k + 1] - CURVE_Y[k]) * t
    return out


def tone_inverse(v8):
    """gccore toneInverse(): 8-bit ISP value -> linear."""
    v8 = np.asarray(v8, float)
    out = np.empty_like(v8)
    lo = v8 <= CURVE_Y[0]
    out[lo] = np.maximum(v8[lo], 0) * CURVE_X[0] / CURVE_Y[0]
    hi = v8 >= CURVE_Y[-1]
    slope = (CURVE_Y[-1] - CURVE_Y[-2]) / np.log(CURVE_X[-1] / CURVE_X[-2]) / CURVE_X[-1]
    head = 255.0 - CURVE_Y[-1]
    rest = np.maximum(255.0 - v8[hi], 0.05)
    out[hi] = CURVE_X[-1] - head / slope * np.log(rest / head)
    mid = ~(lo | hi)
    k = np.searchsorted(CURVE_Y, v8[mid], side='right') - 1
    t = (v8[mid] - CURVE_Y[k]) / (CURVE_Y[k + 1] - CURVE_Y[k])
    out[mid] = CURVE_X[k] * (CURVE_X[k + 1] / CURVE_X[k]) ** t
    return out


def lens_at(focal):
    if focal <= LENS[0][0]:
        return LENS[0][1:]
    if focal >= LENS[-1][0]:
        return 0.0, 0.0
    for (fa, a1, a2), (fb, b1, b2) in zip(LENS, LENS[1:]):
        if focal <= fb:
            t = (focal - fa) / (fb - fa)
            return a1 + (b1 - a1) * t, a2 + (b2 - a2) * t


def focal_of(jpeg):
    exif = Image.open(jpeg).getexif().get_ifd(0x8769)
    return float(exif.get(0x920A, 4.1))


def samples(dng, jpeg):
    """Matched patches: (ISP linear sRGB, camera RGB / range), like gccore matchIsp()."""
    k1, k2 = lens_at(focal_of(jpeg))
    im = Image.open(jpeg)
    im.draft('RGB', (ISP_W // 8, ISP_H // 8))
    img = np.asarray(im.convert('RGB'), dtype=np.int32)
    dh, dw, _ = img.shape
    if (dw, dh) != (ISP_W // 8, ISP_H // 8):
        return None
    scale = ISP_W / dw
    with rawpy.imread(dng) as r:
        raw = r.raw_image_visible.astype(np.float64)
        pattern = r.raw_pattern
        black = float(r.black_level_per_channel[0])
        white = float(r.white_level)
    colour = np.array([0, 1, 2, 1])[pattern]  # raw_pattern indices R,G,B,G -> 0,1,2
    rng = white - black
    ah, aw = raw.shape
    box = 8
    isp, cam = [], []
    for jy in range(1, dh - 1, 2):
        for jx in range(1, dw - 1, 2):
            p = img[jy - 1:jy + 2, jx - 1:jx + 2].reshape(-1, 3)
            lo, hi = p.min(0), p.max(0)
            if (hi - lo).max() > 8 or lo.min() < 20 or hi.max() > 195:
                continue
            mean8 = (p.sum(0) + 4) // 9
            lin = tone_inverse(mean8)
            u = ((jx + 0.5) * scale - ISP_W / 2) / MODEL_R
            v = ((jy + 0.5) * scale - ISP_H / 2) / MODEL_R
            r2 = u * u + v * v
            f = 1 + k1 * r2 + k2 * r2 * r2
            sx, sy = CENTRE_X + MODEL_R * u * f, CENTRE_Y + MODEL_R * v * f
            bx, by = (int(sx) - box // 2) & ~1, (int(sy) - box // 2) & ~1
            if bx < 0 or by < 0 or bx + box > aw or by + box > ah:
                continue
            blk = raw[by:by + box, bx:bx + box] - black
            if blk.max() > 0.9 * rng:
                continue
            c = np.zeros(3)
            for cy in range(2):
                for cx in range(2):
                    c[colour[cy, cx]] += blk[cy::2, cx::2].mean()
            c[1] /= 2
            c /= rng
            if c.min() <= 0.001:
                continue
            isp.append(lin)
            cam.append(c)
    return np.array(isp), np.array(cam)


def lab(lin):
    """CIE Lab (D65 white at linear 1.0) of linear sRGB rows."""
    xyz = lin @ SRGB_TO_XYZ.T / D65
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(np.maximum(xyz, 1e-12)), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.stack([116 * f[:, 1] - 16, 500 * (f[:, 0] - f[:, 1]), 200 * (f[:, 1] - f[:, 2])], 1)


def m_of(off):
    """3x3 with rows summing to 1 from its six off-diagonal entries."""
    m = np.zeros((3, 3))
    m[0, 1], m[0, 2], m[1, 0], m[1, 2], m[2, 0], m[2, 1] = off
    for i in range(3):
        m[i, i] = 1 - m[i].sum()
    return m


def off_of(m):
    return np.array([m[0, 1], m[0, 2], m[1, 0], m[1, 2], m[2, 0], m[2, 1]])


def predict(m, nrb, e, cam):
    n = np.array([nrb[0], 1.0, nrb[1]])
    return e * (cam / n) @ m.T


def fit(data, m0, fix_m, weights=None):
    """Per-pair neutral and exposure, plus M unless fix_m. Returns M, per-pair params, dE.
    weights: per pair, per patch residual weights (None = all 1)."""
    npair = len(data)
    x0 = []
    for isp, cam in data:
        g = isp / cam
        x0 += [np.log(np.median(g[:, 1] / g[:, 0])), np.log(np.median(g[:, 1] / g[:, 2])), np.log(np.median(g[:, 1]))]
    x0 = np.array(x0)
    if not fix_m:
        x0 = np.concatenate([off_of(m0), x0])

    def unpack(x):
        m = m0 if fix_m else m_of(x[:6])
        per = (x if fix_m else x[6:]).reshape(npair, 3)
        return m, per

    def resid(x):
        m, per = unpack(x)
        out = []
        for i, ((isp, cam), (lr, lb, le)) in enumerate(zip(data, per)):
            d = lab(predict(m, np.exp([lr, lb]), np.exp(le), cam)) - lab(isp)
            if weights is not None:
                d = d * weights[i][:, None]
            out.append(d.ravel())
        return np.concatenate(out)

    sol = least_squares(resid, x0, loss='soft_l1', f_scale=3.0)
    m, per = unpack(sol.x)
    de = []
    for (isp, cam), (lr, lb, le) in zip(data, per):
        d = lab(predict(m, np.exp([lr, lb]), np.exp(le), cam)) - lab(isp)
        de.append(np.sqrt((d ** 2).sum(1)))
    return m, np.exp(per), de


def colour_matrix(m, neutral):
    """DNG ColorMatrix (XYZ -> camera) for a light whose camera neutral is [neutral], scaled
    like Adobe's (the largest camera channel of the light's white is 1)."""
    cm = np.diag(neutral) @ np.linalg.inv(m) @ XYZ_TO_SRGB
    return cm / (cm @ D65).max()


def dng_transform(cm, neutral):
    """camera RGB (0..1 of the raw range) -> linear sRGB, as a single-illuminant DNG develops:
    XYZ = CM^-1 cam, Bradford from the shot's white (CM^-1 neutral) to D65, scaled so that the
    neutral comes out at 1.0 like the ISP model."""
    inv = np.linalg.inv(cm)
    white = inv @ neutral
    cat = np.linalg.inv(BRADFORD) @ np.diag((BRADFORD @ D65) / (BRADFORD @ white)) @ BRADFORD
    t = XYZ_TO_SRGB @ cat @ inv
    return t / (t @ neutral)[1]


def dng_error(data, cm, neutrals):
    """dE76 of the DNG development against the ISP's colours, one exposure scale per pair."""
    de = []
    for (isp, cam), n in zip(data, neutrals):
        lin = cam @ dng_transform(cm, n).T
        lin *= np.median(isp[:, 1] / np.maximum(lin[:, 1], 1e-6))
        d = lab(lin) - lab(isp)
        de.append(np.sqrt((d ** 2).sum(1)))
    return de


def render(dng, jpeg, cms, out_w):
    """The ISP JPEG and the DNG developed with each ColorMatrix, on the JPEG's frame."""
    k1, k2 = lens_at(focal_of(jpeg))
    out_h = out_w * ISP_H // ISP_W
    with rawpy.imread(dng) as r:
        neutral = 1 / np.array(r.camera_whitebalance[:3])  # LibRaw gives the inverse of AsShotNeutral
        neutral /= neutral[1]
        cam = r.postprocess(half_size=True, output_color=rawpy.ColorSpace.raw, gamma=(1, 1),
                            no_auto_bright=True, user_wb=[1, 1, 1, 1], output_bps=16).astype(np.float64) / 65535
    # Each output pixel's source in the half-size RAW, through the lens model
    oy, ox = np.mgrid[0:out_h, 0:out_w]
    u = ((ox + 0.5) * ISP_W / out_w - ISP_W / 2) / MODEL_R
    v = ((oy + 0.5) * ISP_H / out_h - ISP_H / 2) / MODEL_R
    r2 = u * u + v * v
    f = 1 + k1 * r2 + k2 * r2 * r2
    sx, sy = (CENTRE_X + MODEL_R * u * f) / 2, (CENTRE_Y + MODEL_R * v * f) / 2
    camw = np.stack([map_coordinates(cam[..., c], [sy, sx], order=1) for c in range(3)], -1)
    panels = [np.asarray(Image.open(jpeg).convert('RGB').resize((out_w, out_h), Image.LANCZOS))]
    # Clipped highlights go to white, as raw editors do: clip after the white balance
    camw = np.minimum(camw / neutral, 1.0) * neutral
    for cm in cms:
        lin = camw @ dng_transform(cm, neutral).T
        panels.append(np.clip(tone_curve(lin) + 0.5, 0, 255).astype(np.uint8))
    return panels


def report(name, de, chroma):
    allde = np.concatenate(de)
    sat = np.concatenate([d[c > 20] for d, c in zip(de, chroma)])
    print(f'{name:28s} dE76 median {np.median(allde):5.2f}  p90 {np.percentile(allde, 90):5.2f}  '
          f'saturated (C*>20, n={sat.size}) median {np.median(sat) if sat.size else float("nan"):5.2f}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--names', nargs='*')
    ap.add_argument('--skip', nargs='*', default=[])
    ap.add_argument('--compare')
    ap.add_argument('--show', nargs='*', default=[])
    a = ap.parse_args()
    names = a.names or sorted(os.path.basename(p)[:-4] for p in glob.glob(os.path.join(a.dir, '*.dng')))
    data, used = [], []
    for n in names:
        if n in a.skip:
            continue
        dng, jpg = os.path.join(a.dir, n + '.dng'), os.path.join(a.dir, n + '.jpg')
        if not (os.path.exists(dng) and os.path.exists(jpg)):
            continue
        s = samples(dng, jpg)
        if s is None or len(s[0]) < 40:
            print(f'{n}: skipped ({0 if s is None else len(s[0])} patches)')
            continue
        print(f'{n}: {len(s[0])} patches')
        data.append(s)
        used.append(n)
    if not data:
        sys.exit('no usable pairs')
    chroma = [np.hypot(*lab(isp)[:, 1:].T) for isp, _ in data]
    np.set_printoptions(precision=4, suppress=True)

    # The ISP model with gccore's matrix: its per-pair neutrals are what matchIsp() writes
    # as AsShotNeutral
    _, per, de = fit(data, CAM_TO_SRGB, True)
    report('ISP model, camToSrgb', de, chroma)
    neutrals = [np.array([nr, 1.0, nb]) for nr, nb, _ in per]
    for n, nn in zip(used, neutrals):
        print(f'  {n}: AsShotNeutral {nn[0]:.4f} 1 {nn[2]:.4f}')
    light = np.median(np.array(neutrals), 0)
    print('median neutral of these pairs (the light):', light)

    m_fit, _, de = fit(data, CAM_TO_SRGB, False)
    report('ISP model, fitted M', de, chroma)
    weights = [1 + c / 20 for c in chroma]
    m_sat, _, de = fit(data, m_fit, False, weights)
    report('ISP model, fitted M (sat.)', de, chroma)

    # What a raw editor shows: each candidate as the DNG's ColorMatrix, same neutrals
    cands = {
        'ColorMatrix1 now (dng.h)': COLOR_MATRIX1,
        'from camToSrgb': colour_matrix(CAM_TO_SRGB, light),
        'from fitted M': colour_matrix(m_fit, light),
        'from fitted M (sat.)': colour_matrix(m_sat, light),
    }
    print('\nDNG development against the ISP JPEG:')
    for name, cm in cands.items():
        report(name, dng_error(data, cm, neutrals), chroma)
    for name, cm in list(cands.items())[1:]:
        print(f'{name}: ColorMatrix (XYZ -> camera, row-major)\n  ' + ', '.join(f'{v:.4f}' for v in cm.ravel()))

    if a.compare and a.show:
        best = cands[min(list(cands)[1:], key=lambda k: np.median(np.concatenate(
            [d[c > 20] for d, c in zip(dng_error(data, cands[k], neutrals), chroma)])))]
        rows = []
        for n in a.show:
            p = render(os.path.join(a.dir, n + '.dng'), os.path.join(a.dir, n + '.jpg'),
                       [COLOR_MATRIX1, best], 640)
            rows.append(np.concatenate(p, 1))
        sheet = Image.fromarray(np.concatenate(rows, 0))
        d = ImageDraw.Draw(sheet)
        for i, t in enumerate(['ISP JPEG (reference)', 'DNG, ColorMatrix now', 'DNG, new ColorMatrix']):
            d.text((i * 640 + 8, 6), t, fill='white', stroke_width=2, stroke_fill='black')
        sheet.save(a.compare, quality=90)
        print('comparison written to', a.compare)


if __name__ == '__main__':
    main()
