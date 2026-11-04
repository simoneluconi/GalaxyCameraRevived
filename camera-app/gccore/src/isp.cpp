#include "gccore/isp.h"

#include <algorithm>
#include <cmath>

namespace gccore {

namespace {

/** The measured knots (linear, 1.0 = raw white after white balance → 8-bit). */
constexpr double kCurveX[] = {0.00200, 0.00323, 0.00520, 0.00839, 0.01354, 0.02183,
                              0.03521, 0.05680, 0.09161, 0.14776, 0.23832, 0.38439};
constexpr double kCurveY[] = {3.22, 4.69, 6.98, 11.08, 18.70, 26.36, 39.08, 64.03, 93.23, 126.53, 162.63, 199.75};
constexpr int kCurveN = sizeof(kCurveX) / sizeof(kCurveX[0]);

constexpr double kCurveXk = kCurveX[kCurveN - 1];
constexpr double kCurveYk = kCurveY[kCurveN - 1];
constexpr double kShoulderHead = 255.0 - kCurveYk;

/** Slope (8-bit levels per linear unit) at the last knot, so the shoulder joins smoothly. */
double shoulderSlope() {
    return (kCurveYk - kCurveY[kCurveN - 2]) / std::log(kCurveXk / kCurveX[kCurveN - 2]) / kCurveXk;
}

}  // namespace

double toneCurve(double x) {
    if (x < kCurveX[0]) return kCurveY[0] * std::max(0.0, x) / kCurveX[0];
    if (x >= kCurveXk) return 255.0 - kShoulderHead * std::exp(-shoulderSlope() * (x - kCurveXk) / kShoulderHead);
    int k = 0;
    while (x >= kCurveX[k + 1]) k++;
    const double t = std::log(x / kCurveX[k]) / std::log(kCurveX[k + 1] / kCurveX[k]);
    return kCurveY[k] + (kCurveY[k + 1] - kCurveY[k]) * t;
}

double toneInverse(double v8) {
    if (v8 <= kCurveY[0]) return std::max(0.0, v8) * kCurveX[0] / kCurveY[0];
    if (v8 >= kCurveYk) {
        const double rest = std::max(255.0 - v8, 0.05);
        return kCurveXk - kShoulderHead / shoulderSlope() * std::log(rest / kShoulderHead);
    }
    int k = 0;
    while (v8 >= kCurveY[k + 1]) k++;
    const double t = (v8 - kCurveY[k]) / (kCurveY[k + 1] - kCurveY[k]);
    return kCurveX[k] * std::pow(kCurveX[k + 1] / kCurveX[k], t);
}

}  // namespace gccore
