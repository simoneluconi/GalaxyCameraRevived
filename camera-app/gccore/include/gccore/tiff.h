// Minimal little-endian TIFF IFD builder for DNG writing.
#pragma once

#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace gccore {

enum TiffType : uint16_t {
    kByte = 1,
    kAscii = 2,
    kShort = 3,
    kLong = 4,
    kRational = 5,
    kUndefined = 7,
    kSRational = 10,
};

/** One IFD: tag -> (type, count, value bytes). Entries are written sorted by tag. */
class Ifd {
public:
    void bytes(uint16_t tag, const std::vector<uint8_t>& v, TiffType type = kByte);
    void ascii(uint16_t tag, const std::string& s);
    void shorts(uint16_t tag, const std::vector<uint16_t>& v);
    void longs(uint16_t tag, const std::vector<uint32_t>& v);
    void rationals(uint16_t tag, const std::vector<double>& v, uint32_t den = 10000);
    void rationalsExact(uint16_t tag, const std::vector<std::pair<uint32_t, uint32_t>>& v);
    void srationals(uint16_t tag, const std::vector<double>& v, int32_t den = 10000);

    void shortVal(uint16_t tag, uint16_t v) { shorts(tag, {v}); }
    void longVal(uint16_t tag, uint32_t v) { longs(tag, {v}); }

    /** Bytes this IFD takes in the file, including out-of-line values. */
    size_t size() const;

    /** The IFD as placed at file offset [offset], pointing to [next] (0 = last). */
    std::vector<uint8_t> pack(uint32_t offset, uint32_t next = 0) const;

private:
    struct Entry {
        uint16_t type;
        uint32_t count;
        std::vector<uint8_t> data;
    };
    std::map<uint16_t, Entry> entries_;
};

void putU16(std::vector<uint8_t>& b, uint16_t v);
void putU32(std::vector<uint8_t>& b, uint32_t v);

}  // namespace gccore
