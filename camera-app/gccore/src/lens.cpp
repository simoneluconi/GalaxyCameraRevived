#include "gccore/lens.h"

#include <algorithm>
#include <cmath>
#include <iterator>

namespace gccore {

namespace {

// Measured 2026-11-02 (15 RAW + ISP JPEG pairs, SIFT registration with a radial model).
// From 16.5 mm up the ISP applies no distortion correction and the RAW has no dark corners,
// so the table ends there with "nothing to do".
// TODO: steps 7.5, 11.3 and 13.9 mm were not captured (interpolated); the wide end fits only
// reach r ~0.75, so k2 is uncertain; vignetting is not measured yet.
const LensCorrection kTable[] = {
    {4.1, -0.269, +0.117, 0.0814, 0.0800, 0.9169, 0.9276},
    {5.0, -0.177, +0.050, 0.0687, 0.0670, 0.9296, 0.9405},
    {6.2, -0.090, +0.008, 0.0472, 0.0452, 0.9512, 0.9624},
    {9.2, -0.025, +0.010, 0.0137, 0.0114, 0.9846, 0.9963},
    {16.5, 0, 0, 0, 0, 1, 1},
};

double mix(double a, double b, double t) { return a + (b - a) * t; }

}  // namespace

OpticalCentre opticalCentreOf(const std::string& model) {
    if (model == "EK-GC200") return {2321.0, 1694.0};
    return {};
}

LensCorrection lensAt(double focalMm) {
    const LensCorrection* first = std::begin(kTable);
    const LensCorrection* last = std::end(kTable) - 1;
    if (focalMm <= first->focalMm) return *first;
    if (focalMm >= last->focalMm) {
        LensCorrection none;
        none.focalMm = focalMm;
        return none;
    }
    for (const LensCorrection* p = first; p < last; p++) {
        const LensCorrection& a = p[0];
        const LensCorrection& b = p[1];
        if (focalMm > b.focalMm) continue;
        const double t = (focalMm - a.focalMm) / (b.focalMm - a.focalMm);
        LensCorrection r;
        r.focalMm = focalMm;
        r.k1 = mix(a.k1, b.k1, t);
        r.k2 = mix(a.k2, b.k2, t);
        r.cropLeft = mix(a.cropLeft, b.cropLeft, t);
        r.cropTop = mix(a.cropTop, b.cropTop, t);
        r.cropRight = mix(a.cropRight, b.cropRight, t);
        r.cropBottom = mix(a.cropBottom, b.cropBottom, t);
        return r;
    }
    return *last;
}

void ispFrameAfterProfile(const LensCorrection& lens, double width, double height, double cx, double cy,
                          double out[4]) {
    // Corrected radius r_o whose source is the short side's midpoint r_s:
    // r_o (1 + k1 r_o^2 + k2 r_o^4) = r_s, by Newton from r_s
    const double rs = std::min(width, height) / 2 / kModelRadiusPx;
    double ro = rs;
    for (int i = 0; i < 20; i++) {
        const double r2 = ro * ro;
        const double f = ro * (1 + lens.k1 * r2 + lens.k2 * r2 * r2) - rs;
        const double df = 1 + 3 * lens.k1 * r2 + 5 * lens.k2 * r2 * r2;
        if (std::fabs(df) < 1e-9) break;
        ro -= f / df;
    }
    const double scale = ro > 0 ? std::clamp(rs / ro, 0.5, 1.0) : 1.0;
    // The ISP frame is 4608 x 3456 at 1:1 with the RAW, centred on the optical centre
    const double hw = 4608.0 / 2 * scale;
    const double hh = 3456.0 / 2 * scale;
    out[0] = std::clamp((cx - hw) / width, 0.0, 1.0);
    out[1] = std::clamp((cy - hh) / height, 0.0, 1.0);
    out[2] = std::clamp((cx + hw) / width, 0.0, 1.0);
    out[3] = std::clamp((cy + hh) / height, 0.0, 1.0);
}

}  // namespace gccore
