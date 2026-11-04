#include "gccore/tiff.h"

#include <cmath>

namespace gccore {

void putU16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(v & 0xff);
    b.push_back(v >> 8);
}

void putU32(std::vector<uint8_t>& b, uint32_t v) {
    for (int i = 0; i < 4; i++) b.push_back((v >> (8 * i)) & 0xff);
}

void Ifd::bytes(uint16_t tag, const std::vector<uint8_t>& v, TiffType type) {
    entries_[tag] = {type, static_cast<uint32_t>(v.size()), v};
}

void Ifd::ascii(uint16_t tag, const std::string& s) {
    std::vector<uint8_t> d(s.begin(), s.end());
    d.push_back(0);
    entries_[tag] = {kAscii, static_cast<uint32_t>(d.size()), d};
}

void Ifd::shorts(uint16_t tag, const std::vector<uint16_t>& v) {
    std::vector<uint8_t> d;
    for (uint16_t x : v) putU16(d, x);
    entries_[tag] = {kShort, static_cast<uint32_t>(v.size()), d};
}

void Ifd::longs(uint16_t tag, const std::vector<uint32_t>& v) {
    std::vector<uint8_t> d;
    for (uint32_t x : v) putU32(d, x);
    entries_[tag] = {kLong, static_cast<uint32_t>(v.size()), d};
}

void Ifd::rationals(uint16_t tag, const std::vector<double>& v, uint32_t den) {
    std::vector<uint8_t> d;
    for (double x : v) {
        putU32(d, static_cast<uint32_t>(std::lround(x * den)));
        putU32(d, den);
    }
    entries_[tag] = {kRational, static_cast<uint32_t>(v.size()), d};
}

void Ifd::rationalsExact(uint16_t tag, const std::vector<std::pair<uint32_t, uint32_t>>& v) {
    std::vector<uint8_t> d;
    for (auto& r : v) {
        putU32(d, r.first);
        putU32(d, r.second);
    }
    entries_[tag] = {kRational, static_cast<uint32_t>(v.size()), d};
}

void Ifd::srationals(uint16_t tag, const std::vector<double>& v, int32_t den) {
    std::vector<uint8_t> d;
    for (double x : v) {
        putU32(d, static_cast<uint32_t>(static_cast<int32_t>(std::lround(x * den))));
        putU32(d, static_cast<uint32_t>(den));
    }
    entries_[tag] = {kSRational, static_cast<uint32_t>(v.size()), d};
}

size_t Ifd::size() const {
    size_t n = 2 + 12 * entries_.size() + 4;
    for (auto& [tag, e] : entries_) {
        if (e.data.size() > 4) n += e.data.size() + (e.data.size() & 1);
    }
    return n;
}

std::vector<uint8_t> Ifd::pack(uint32_t offset, uint32_t next) const {
    std::vector<uint8_t> head;
    std::vector<uint8_t> data;
    putU16(head, static_cast<uint16_t>(entries_.size()));
    uint32_t dataOff = offset + 2 + 12 * static_cast<uint32_t>(entries_.size()) + 4;
    for (auto& [tag, e] : entries_) {
        putU16(head, tag);
        putU16(head, e.type);
        putU32(head, e.count);
        if (e.data.size() <= 4) {
            head.insert(head.end(), e.data.begin(), e.data.end());
            for (size_t i = e.data.size(); i < 4; i++) head.push_back(0);
        } else {
            putU32(head, dataOff + static_cast<uint32_t>(data.size()));
            data.insert(data.end(), e.data.begin(), e.data.end());
            // Values start on a word boundary
            if (e.data.size() & 1) data.push_back(0);
        }
    }
    putU32(head, next);
    head.insert(head.end(), data.begin(), data.end());
    return head;
}

}  // namespace gccore
