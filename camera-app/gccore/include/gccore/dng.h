// DNG writer for M9MO raw frames (port and extension of tools/m9mo-dng.py).
#pragma once

#include <cstdint>
#include <cstdio>
#include <string>

#include "gccore/raw.h"

namespace gccore {

/** What the DNG says about the shot. Zero / empty means "unknown, leave the tag out". */
struct DngMeta {
    // Exposure, as the ISP used it (exif-*)
    int iso = 0;
    double exposureSeconds = 0;
    double fNumber = 0;
    double focalMm = 0;
    int focal35mm = 0;
    double biasEv = 0;
    /** EXIF ExposureProgram: 1 manual, 2 program, 3 aperture, 4 shutter. */
    int exposureProgram = 0;

    /** "YYYY:MM:DD HH:MM:SS" and "+01:00". */
    std::string dateTime;
    std::string offsetTime;
    /** EXIF/TIFF orientation 1..8. */
    int orientation = 1;
    std::string software = "GC Camera";
    /** TIFF Model; UniqueCameraModel is "Samsung " + model. */
    std::string model = "EK-GC100";
    std::string artist;
    std::string copyright;

    /** The geotag, the same as the JPEG's GPS IFD; left out when [hasGps] is false. */
    bool hasGps = false;
    double latitude = 0;
    double longitude = 0;
    bool hasAltitude = false;
    /** Metres above sea level (negative below). */
    double altitude = 0;
    /** When the fix was taken, ms since the epoch (UTC). */
    int64_t gpsTimeMs = 0;
    /** Horizontal accuracy in metres; 0 = unknown. */
    double gpsAccuracy = 0;

    /**
     * XYZ(D65) → camera matrix (ColorMatrix1), row-major. Fitted 2026-11-02 against the ISP's
     * own JPEGs on a colourful scene (4 RAW + JPEG pairs, indoor light): colour error ΔE 17 → 6
     * on saturated patches, and it held on a second scene it was not fitted on.
     * TODO: a second illuminant (daylight / tungsten) for a dual-illuminant profile.
     */
    double colorMatrix[9] = {1.2027, -0.3131, -0.1016, 0.1819, 0.7463, 0.0742, 0.1216, 0.0409, 0.2983};
    /** EXIF LightSource code of [colorMatrix]: 21 = D65. */
    int calibrationIlluminant = 21;
    /** BaselineExposure: renderers open the DNG as bright as the ISP's JPEG (+0.55 EV). */
    double baselineExposure = 0.55;
    /** AsShotNeutral; all zero = estimate it from the frame (grey world). */
    double neutral[3] = {0, 0, 0};

    /**
     * Embed Lightroom settings (XMP) that turn on the GC100 lens profile and constrain the crop
     * to it: the photo opens corrected like the camera's JPEG, and both stay removable. The
     * profile itself (tools/gc100-lcp.py) must be installed in Lightroom / Camera Raw.
     */
    bool lensSettings = true;

    /**
     * The app's XMP properties, as rdf:Description namespace declarations and attributes
     * (lines like `   xmp:Rating="0"`); the app's JPEG carries the same ones. Empty: just
     * xmp:Rating 0.
     */
    std::string xmpProperties;

    /**
     * A full-size JPEG preview to embed (the ISP's own JPEG of the same shot), so viewers
     * that do not develop RAW (Windows Photos, file managers) still show the photo sharp.
     * Optional; the 256-px RGB thumbnail is always written.
     */
    const uint8_t* previewJpeg = nullptr;
    size_t previewJpegSize = 0;
};

/** Width and height from a baseline/progressive JPEG's SOF marker; false if not found. */
bool jpegSize(const uint8_t* data, size_t size, int* width, int* height);

/** Grey-world white balance from the centre of the active area: {r/g, 1, b/g}. */
void estimateNeutral(const RawFrame& raw, double out[3]);

/**
 * Writes a DNG 1.4: a small RGB preview in IFD0 and the 16-bit CFA image in a SubIFD,
 * streamed row by row (no full-size buffer). Returns false and sets [error] on failure.
 */
bool writeDng(const RawFrame& raw, const DngMeta& meta, FILE* out, std::string* error);

}  // namespace gccore
