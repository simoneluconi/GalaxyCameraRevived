// The ISP's own colour and tone, as measured against its JPEGs: what matchIsp() inverts to
// read the white balance the ISP chose for a shot (the DNG's AsShotNeutral).
#pragma once

namespace gccore {

struct IspParams {
    /** AsShotNeutral {r/g, 1, b/g}; the white-balance gains are its inverse. */
    double neutral[3] = {1, 1, 1};
    /**
     * White-balanced camera RGB → the ISP's linear sRGB, row-major (rows sum to 1, so white
     * stays white). Fitted 2026-11-02 with the tone curve in the loop on 10 RAW + ISP JPEG
     * pairs: ΔE76 median 1.4 against the ISP's colours.
     */
    double camToSrgb[9] = {1.8601, -0.7230, -0.1371, -0.5486, 1.7591, -0.2105, -0.1565, -0.6081, 1.7646};
    /** The ISP's linear gain over the raw (measured: about 1, it adds none of its own). */
    double exposure = 1;
};

/**
 * The ISP's tone curve: linear (1.0 = raw white after white balance) → 8-bit sRGB, as a real
 * value. Measured on 10 RAW + JPEG pairs up to the upper mid tones, then a soft shoulder.
 */
double toneCurve(double linear);

/** The tone curve's inverse: an 8-bit value of the ISP's JPEG → linear (1.0 = raw white). */
double toneInverse(double v8);

}  // namespace gccore
