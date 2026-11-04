// The M9MO raw frame: a continuous big-endian 10-bit stream (docs/CAMERA-APP.md §3.2).
#pragma once

#include <cstddef>
#include <cstdint>

namespace gccore {

/** CFA colours as DNG codes them: 0 red, 1 green, 2 blue. */
struct RawFrame {
    const uint8_t* data = nullptr;
    size_t size = 0;
    /** Row stride in pixels, and rows (raw-size). */
    int width = 4736;
    int height = 3452;
    /** raw-active-area: left, top, right, bottom. */
    int left = 2, top = 0, right = 4672, bottom = 3452;
    double black = 64;
    double white = 1023;
    /** raw-cfa, row-major 2x2 (grbg → 1,0,2,1). */
    uint8_t cfa[4] = {1, 0, 2, 1};

    size_t rowBytes() const { return static_cast<size_t>(width) * 10 / 8; }
    bool valid() const;

    /** Unpacks row [y] into [out] (width pixels). */
    void unpackRow(int y, uint16_t* out) const;

    /** One pixel, unpacked on the fly (slow; for sparse sampling only). */
    uint16_t at(int x, int y) const;
};

/** Parses "grbg"-style CFA strings. Returns false on anything else. */
bool parseCfa(const char* s, uint8_t out[4]);

}  // namespace gccore
