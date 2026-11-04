// RAW development: demosaic, colour, tone, and (developCorrected) lens and sharpening.
#pragma once

#include <cstdint>
#include <functional>

#include "gccore/lens.h"
#include "gccore/raw.h"

namespace gccore {

struct DevelopParams {
    /** AsShotNeutral {r/g, 1, b/g}; the white-balance gains are its inverse. */
    double neutral[3] = {1, 1, 1};
    /**
     * White-balanced camera RGB → linear sRGB, row-major (rows sum to 1, so white stays white).
     * Fitted 2026-11-02 with the tone curve in the loop, on 10 RAW + ISP JPEG pairs: ΔE76
     * median 1.4 against the ISP's colours (tools: scratchpad curvefit.py).
     */
    double camToSrgb[9] = {1.8601, -0.7230, -0.1371, -0.5486, 1.7591, -0.2105, -0.1565, -0.6081, 1.7646};
    /**
     * Linear exposure gain; 0 = automatic (centre-weighted mean to mid grey). 1 matches the
     * ISP's JPEG with the tone curve below (measured: the ISP adds no gain of its own).
     */
    double exposure = 0;
    /** Worker threads; the A9 has 4 cores. */
    int threads = 4;
    /** Output rows per strip (memory: rows × width × 3 bytes per thread). */
    int stripRows = 64;
};

/**
 * Receives the output in order, [rows] rows of [width] interleaved sRGB pixels starting at
 * row [y]. Called on the calling thread.
 */
using RgbSink = std::function<bool(int y, int rows, const uint8_t* rgb)>;

/**
 * Develops the active area of [raw] to 8-bit sRGB, strip by strip (bounded memory: one
 * strip buffer per thread, never the full image). Returns false if the sink stops it.
 */
bool develop(const RawFrame& raw, const DevelopParams& params, const RgbSink& sink);

/** The automatic exposure gain develop() uses when params.exposure is 0. */
double autoExposure(const RawFrame& raw, const double neutral[3]);

/** The tone curve's inverse: an 8-bit output value → linear (1.0 = raw white). */
double toneInverse(double v8);

struct CorrectedParams {
    /** Distortion to remove (lensAt(focal)). */
    LensCorrection lens;
    /**
     * Light fall-off 1 + v r^2 to even out, r over the model radius: the profile's value
     * (tools/gc100-lcp.py), set by eye for now.
     */
    double vignette = -0.375;
    /**
     * Unsharp mask on luminance after the tone curve (radius ~1 px): [sharpenAmount] × the
     * detail, cored below [sharpenThreshold] 8-bit levels (noise) and limited to
     * ±[sharpenLimit] levels (halos). 0 = off.
     */
    double sharpenAmount = 0.8;
    double sharpenThreshold = 2.0;
    int sharpenLimit = 28;
    /** Output rows per step. */
    int stripRows = 32;
};

/**
 * develop() plus the lens: the output is the ISP's kIspWidth x kIspHeight frame, distortion
 * removed and vignetting evened out, resampled (Catmull-Rom) from demosaiced rows kept in a
 * small ring buffer (a few MB: never the whole image), then sharpened.
 */
bool developCorrected(const RawFrame& raw, const DevelopParams& params, const CorrectedParams& lens,
                      const RgbSink& sink);

}  // namespace gccore
