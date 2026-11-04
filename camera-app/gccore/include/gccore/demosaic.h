// SPDX-License-Identifier: GPL-3.0-or-later
// Demosaicing for the second-generation pipeline (docs/RAW-PIPELINE.md).
#pragma once

namespace gccore {

/**
 * RCD (ratio corrected demosaicing, Luis Sanz Rodríguez; RawTherapee's tiled version) of a
 * [width] x [height] Bayer plane. [cfa] holds linear values in 0..1, black-subtracted and
 * preferably white-balanced (RCD compares channel ratios). [pattern] is the 2x2 CFA,
 * row-major, 0 = R, 1 = G, 2 = B. The output planes are [width] x [height] each; the 9-px
 * border RCD cannot reach is filled by a bilinear fallback. Tiles run on [threads] threads.
 */
void rcdDemosaic(int width, int height, const float* cfa, const int pattern[4], float* red, float* green,
                 float* blue, int threads);

}  // namespace gccore
