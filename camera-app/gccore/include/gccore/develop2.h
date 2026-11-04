// SPDX-License-Identifier: GPL-3.0-or-later
// The second-generation RAW development (docs/RAW-PIPELINE.md): RCD, capture sharpening,
// a Lanczos lens warp and halo-clamped output sharpening. Full float planes for now, so it
// is a PC prototype (about 450 MB for 16 MP); strips come once it beats the ISP.
#pragma once

#include "gccore/develop.h"

namespace gccore {

struct Develop2Params {
    /** White balance, colour matrix, exposure (1 = the ISP's), threads. */
    DevelopParams base;
    /** Distortion to remove; the output is the ISP's kIspWidth x kIspHeight frame. */
    LensCorrection lens;
    double vignette = -0.375;

    /** Demosaic: true = RCD, false = Malvar-He-Cutler (the first pipeline's). */
    bool rcd = true;

    /**
     * The raw noise at the shot's ISO (noiseModel(): variance = a × counts + b) and the black
     * level to subtract (blackLevel(); 0 = the frame's raw-black-level). a = 0: no noise reduction.
     */
    double noiseA = 0, noiseB = 0;
    double black = 0;
    /**
     * Noise reduction after the demosaic: a generalised Anscombe transform (noise 1 at every
     * brightness), luma/chroma split, à-trous wavelets soft-thresholded per level at
     * [nrLuma] / [nrChroma] times the level's noise. 0 = that part off.
     */
    double nrLuma = 1.0;
    double nrChroma = 2.0;
    /**
     * Chroma wavelet levels dropped outright (0..5): the fine colour detail goes and edges take
     * their sharpness from luma alone. This lens blurs red and blue more than green (axial
     * chromatic aberration), so thin dark lines turn green with violet fringes otherwise; the
     * ISP drops colour below about 4 px too.
     */
    int chromaDropLevels = 2;

    /**
     * Capture sharpening: Richardson-Lucy deconvolution of linear luminance with a Gaussian
     * PSF of [rlSigma] px, [rlIterations] steps, on the sensor grid (before the warp). 0 = off.
     */
    double rlSigma = 0;
    int rlIterations = 10;

    /**
     * Resampling for the lens warp: 3 = Lanczos-3 with the result clamped to the four nearest
     * samples' range (no ringing); 2 = Catmull-Rom, unclamped (the first pipeline's).
     */
    int warpLobes = 3;

    /**
     * Output sharpening, on gamma luma after the warp: [sharpenAmount] × (luma − Gaussian of
     * [sharpenSigma] px), cored below [sharpenThreshold] 8-bit levels, then held within the
     * 3x3 neighbourhood's range ± [overshoot] × that range (a little overshoot reads as
     * crispness, more is a halo). 0 amount = off.
     */
    double sharpenAmount = 0.8;
    double sharpenSigma = 1.0;
    double sharpenThreshold = 2.0;
    double overshoot = 0.1;

    /**
     * Detail gains per à-trous level of gamma luma after the warp (~1, 2, 4, 8, 16 px), 1 =
     * unchanged. The ISP's look, measured against ours (scratchpad bandgain.py): ×0.56 at 1 px,
     * ×1.37 at 2 px, ×1.27 at 4 px, ×1.09 at 8 px; its crispness lives at 2–8 px, not in the
     * finest level. Held like the unsharp mask, with [detailOvershoot] (< 0: unclamped).
     */
    double detail[5] = {1, 1, 1, 1, 1};
    double detailOvershoot = 0.5;
};

/** Develops [raw] into the lens-corrected ISP frame, handing rows to [sink] in order. */
bool develop2(const RawFrame& raw, const Develop2Params& params, const RgbSink& sink);

}  // namespace gccore
