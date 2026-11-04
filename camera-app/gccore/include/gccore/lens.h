// The GC100 lens, measured against the ISP's own JPEGs (docs/CAMERA-APP.md §9, calibration).
#pragma once

namespace gccore {

/** What the ISP does to its JPEG at one focal length, in the RAW active area's terms. */
struct LensCorrection {
    double focalMm = 0;
    /**
     * Radial distortion the ISP removes: a JPEG point at radius r (1 = its half diagonal)
     * comes from r * (1 + k1 r^2 + k2 r^4) in the RAW. k1 < 0 is barrel.
     */
    double k1 = 0, k2 = 0;
    /** The largest rectangle of the RAW inside the ISP frame, as fractions of the active area. */
    double cropLeft = 0, cropTop = 0, cropRight = 1, cropBottom = 1;

    bool distorted() const { return k1 < -0.005; }
};

/**
 * The correction at [focalMm], interpolated between the calibrated focal lengths. For the
 * in-app development; Lightroom gets the same numbers as a lens profile (tools/gc100-lcp.py).
 */
LensCorrection lensAt(double focalMm);

/** Shared with tools/gc100-lcp.py: the model's centre and radius unit. */
constexpr double kOpticalCentreX = 2331.0;  // in the RAW active area
constexpr double kOpticalCentreY = 1739.0;
constexpr double kModelRadiusPx = 2880.0;   // the ISP frame's half diagonal, 1:1 with the RAW

/** The ISP JPEG's frame, 1:1 with the RAW at the centre. */
constexpr int kIspWidth = 4608;
constexpr int kIspHeight = 3456;

/** Where point (ox, oy) of the ISP frame comes from in the RAW active area. */
inline void ispToActive(const LensCorrection& lens, double ox, double oy, double& sx, double& sy) {
    const double u = (ox - kIspWidth / 2.0) / kModelRadiusPx;
    const double v = (oy - kIspHeight / 2.0) / kModelRadiusPx;
    const double r2 = u * u + v * v;
    const double f = 1 + lens.k1 * r2 + lens.k2 * r2 * r2;
    sx = kOpticalCentreX + kModelRadiusPx * u * f;
    sy = kOpticalCentreY + kModelRadiusPx * v * f;
}

/**
 * Where the ISP's frame lands once Lightroom (or RawTherapee) applies the lens profile, as
 * fractions {left, top, right, bottom} of an image of [width] x [height] pixels centred like
 * the active area. Those renderers scale the corrected image so the midpoint of its short side
 * stays on the edge (measured: 0.905 at 4.1 mm), which brings the dark corners back in; this is
 * the crop that hides them again.
 */
void ispFrameAfterProfile(const LensCorrection& lens, double width, double height, double centreX,
                          double centreY, double out[4]);

}  // namespace gccore
