// Reading the ISP's JPEG (libjpeg-turbo) for what the DNG needs from it.
#pragma once

#include <cstddef>
#include <cstdint>

#include "gccore/isp.h"
#include "gccore/lens.h"
#include "gccore/raw.h"

namespace gccore {

/**
 * The white balance and exposure the ISP chose for this shot, read back from its own JPEG
 * ([jpeg], [size] bytes, the ISP frame at [focalMm]). Flat mid-tone patches of a 1/8-scale
 * decode are inverted through the ISP's tone curve and colour matrix and compared with the
 * same places in [raw] (mapped with the lens around [centre]); the per-channel gains are their
 * medians. The ISP's auto white balance
 * is far better than a grey-world guess. Sets params.neutral and params.exposure, or returns
 * false and leaves them alone when the JPEG does not decode, is not the full 4:3 frame (a 3:2
 * or 16:9 crop does not map yet), or has too few usable patches.
 */
bool matchIsp(const RawFrame& raw, const uint8_t* jpeg, size_t size, double focalMm, const OpticalCentre& centre,
              IspParams& params);

}  // namespace gccore
