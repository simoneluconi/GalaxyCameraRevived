// SPDX-License-Identifier: GPL-3.0-or-later
// The GC100 sensor's noise and black level per ISO, measured 2026-11-04 (docs/RAW-PIPELINE.md).
#pragma once

namespace gccore {

/**
 * Poisson-Gaussian noise of a raw pixel at [iso]: variance = a × signal + b, in 10-bit counts
 * above black. b is the read noise from dark-frame pairs (1/30 s); a, the shot-noise gain, is
 * fitted on the flat parts of ordinary frames (about 0.17 at ISO 100, proportional to ISO).
 */
void noiseModel(double iso, double& a, double& b);

/**
 * The black level at [iso] for exposures up to about 1/4 s: it sinks from 63.5 at ISO 100 to
 * about 60.5 at ISO 3200 (green; the HAL's raw-black-level says 64 for all). Longer exposures
 * gain dark current back (1 s: about 63.5 at every ISO), so long ones keep the HAL's value.
 */
double blackLevel(double iso, double exposureSeconds);

}  // namespace gccore
