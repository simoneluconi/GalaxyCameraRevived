// Streaming JPEG encoder (libjpeg-turbo) and the RAW → JPEG pipeline built on it.
#pragma once

#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>

#include "gccore/develop.h"
#include "gccore/raw.h"

namespace gccore {

/** Writes a baseline JPEG row by row; never holds the whole image. */
class JpegWriter {
public:
    JpegWriter();
    ~JpegWriter();

    /** Starts a [width] x [height] sRGB JPEG at [quality] (1..100) into [out]. */
    bool begin(FILE* out, int width, int height, int quality, std::string* error);
    /** [rows] rows of interleaved RGB, width pixels each. */
    bool writeRows(const uint8_t* rgb, int rows);
    bool finish();
    /** libjpeg's message for the last failure. */
    const char* error() const;

private:
    struct Impl;
    std::unique_ptr<Impl> d_;
};

/**
 * The white balance and exposure the ISP chose for this shot, read back from its own JPEG
 * ([jpeg], [size] bytes, the ISP frame at [focalMm]). Flat mid-tone patches of a 1/8-scale
 * decode are inverted through our tone curve and colour matrix and compared with the same
 * places in [raw]; the per-channel gains are their medians. The ISP's auto white balance is
 * far better than a grey-world guess. Sets params.neutral and params.exposure, or returns
 * false and leaves them alone when the JPEG does not decode, is not the full 4:3 frame (a 3:2
 * or 16:9 crop does not map yet), or has too few usable patches.
 */
bool matchIsp(const RawFrame& raw, const uint8_t* jpeg, size_t size, double focalMm, DevelopParams& params);

/**
 * "Advanced" processing of one shot: develops [raw] with lens correction (the calibrated
 * distortion at [focalMm], the vignetting, and the ISP's own 4608x3456 framing) and encodes
 * it to [out]. EXIF is the caller's (copied from the ISP JPEG it replaces).
 */
bool developToJpeg(const RawFrame& raw, const DevelopParams& params, double focalMm, int quality, FILE* out,
                   std::string* error);

}  // namespace gccore
