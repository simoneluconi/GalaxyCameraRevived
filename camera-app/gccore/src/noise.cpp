// SPDX-License-Identifier: GPL-3.0-or-later
#include "gccore/noise.h"

#include <algorithm>
#include <cmath>

namespace gccore {

namespace {

// Dark-frame pairs at 1/30 s (scratchpad darknoise.py): green read noise and black, per ISO
constexpr double kIso[] = {100, 200, 400, 800, 1600, 3200};
constexpr double kReadSigma[] = {0.65, 0.85, 1.57, 3.12, 6.00, 11.85};
constexpr double kBlack[] = {63.5, 63.4, 63.2, 62.8, 62.0, 60.5};
constexpr int kN = sizeof(kIso) / sizeof(kIso[0]);
// Shot-noise gain at ISO 100 (noisefit.py: 0.16-0.19 on two scenes, three channels)
constexpr double kShotGain100 = 0.17;

/** Linear interpolation of [v] over log2(ISO), clamped to the measured range. */
double interp(const double* v, double iso) {
    const double l = std::log2(std::clamp(iso, kIso[0], kIso[kN - 1]) / 100.0);
    const int i = std::min(kN - 2, static_cast<int>(l));
    const double t = l - i;
    return v[i] + (v[i + 1] - v[i]) * t;
}

}  // namespace

void noiseModel(double iso, double& a, double& b) {
    a = kShotGain100 * std::max(iso, 50.0) / 100.0;
    const double s = interp(kReadSigma, iso);
    b = s * s;
}

double blackLevel(double iso, double exposureSeconds) {
    if (exposureSeconds > 0.25) return 64.0;
    return interp(kBlack, iso);
}

}  // namespace gccore
