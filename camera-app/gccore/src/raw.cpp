#include "gccore/raw.h"

#include <cctype>

namespace gccore {

bool RawFrame::valid() const {
    return data != nullptr && width > 0 && height > 0 && width % 4 == 0 &&
           size >= rowBytes() * static_cast<size_t>(height) && left >= 0 && top >= 0 &&
           right <= width && bottom <= height && left < right && top < bottom && white > black;
}

void RawFrame::unpackRow(int y, uint16_t* out) const {
    // 4 pixels in 5 bytes, MSB first; every row starts on a byte boundary (5920 bytes)
    const uint8_t* g = data + rowBytes() * static_cast<size_t>(y);
    for (int x = 0; x < width; x += 4, g += 5) {
        out[x] = static_cast<uint16_t>((g[0] << 2) | (g[1] >> 6));
        out[x + 1] = static_cast<uint16_t>(((g[1] & 0x3f) << 4) | (g[2] >> 4));
        out[x + 2] = static_cast<uint16_t>(((g[2] & 0x0f) << 6) | (g[3] >> 2));
        out[x + 3] = static_cast<uint16_t>(((g[3] & 0x03) << 8) | g[4]);
    }
}

uint16_t RawFrame::at(int x, int y) const {
    const uint8_t* g = data + rowBytes() * static_cast<size_t>(y) + (x / 4) * 5;
    switch (x & 3) {
        case 0: return static_cast<uint16_t>((g[0] << 2) | (g[1] >> 6));
        case 1: return static_cast<uint16_t>(((g[1] & 0x3f) << 4) | (g[2] >> 4));
        case 2: return static_cast<uint16_t>(((g[2] & 0x0f) << 6) | (g[3] >> 2));
        default: return static_cast<uint16_t>(((g[3] & 0x03) << 8) | g[4]);
    }
}

bool parseCfa(const char* s, uint8_t out[4]) {
    for (int i = 0; i < 4; i++) {
        switch (std::tolower(static_cast<unsigned char>(s[i]))) {
            case 'r': out[i] = 0; break;
            case 'g': out[i] = 1; break;
            case 'b': out[i] = 2; break;
            default: return false;
        }
    }
    return s[4] == '\0';
}

}  // namespace gccore
