#include "gccore/develop.h"

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

namespace gccore {

namespace {

// Tone LUT over linear [0, kLutRange): 4096 steps per unit keeps shadows below one 8-bit level
constexpr int kLutPerUnit = 4096;
constexpr double kLutRange = 4.0;
constexpr int kLutSize = static_cast<int>(kLutPerUnit * kLutRange);

/**
 * The ISP's tone curve, measured on 10 RAW + JPEG pairs (linear, 1.0 = raw white after white
 * balance → 8-bit), up to the upper mid tones. Above them the ISP flattens and clips at about
 * 0.75; we roll off softly to the sensor's clip instead, which keeps the highlight gradation.
 */
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

/** Linear → 8-bit sRGB, every kLutPerUnit-th of a unit (toneCurve()). */
std::vector<uint8_t> makeToneLut() {
    std::vector<uint8_t> lut(kLutSize + 1);
    for (int i = 0; i <= kLutSize; i++) {
        lut[i] = static_cast<uint8_t>(std::lround(std::clamp(toneCurve(static_cast<double>(i) / kLutPerUnit), 0.0, 255.0)));
    }
    return lut;
}

/** Frame row [y] mirrored into range, keeping the CFA phase (−1 → 1, H → H−2). */
int mirrorRow(int y, int h) {
    if (y < 0) return -y;
    if (y >= h) return 2 * (h - 1) - y;
    return y;
}

struct Strip {
    int width = 0;  // frame width
    // Black-subtracted rows with 2 mirrored pixels on each side
    std::vector<int32_t> rows;
    std::vector<uint16_t> unpacked;
    std::vector<uint8_t> out;

    int32_t* row(int i) { return rows.data() + static_cast<size_t>(i) * (width + 4) + 2; }
};

struct Context {
    const RawFrame& raw;
    /** White-balance gains; every channel clips at [clip] after them (white highlights). */
    float wb[3];
    float clip;
    /** Colour matrix × exposure / range × LUT scale: WB'd raw counts → LUT index. */
    float m[9];
    std::vector<uint8_t> lut;
    int black;
};

Context makeContext(const RawFrame& raw, const DevelopParams& p) {
    const double exposure = p.exposure > 0 ? p.exposure : autoExposure(raw, p.neutral);
    const double range = raw.white - raw.black;
    Context ctx{raw, {}, static_cast<float>(range), {}, makeToneLut(), static_cast<int>(std::lround(raw.black))};
    for (int c = 0; c < 3; c++) ctx.wb[c] = static_cast<float>(1.0 / std::max(p.neutral[c], 1e-6));
    for (int i = 0; i < 9; i++) ctx.m[i] = static_cast<float>(p.camToSrgb[i] * exposure / range * kLutPerUnit);
    return ctx;
}

void loadRow(const Context& ctx, Strip& s, int slot, int frameY) {
    const RawFrame& raw = ctx.raw;
    raw.unpackRow(mirrorRow(frameY, raw.height), s.unpacked.data());
    int32_t* r = s.row(slot);
    for (int x = 0; x < raw.width; x++) r[x] = static_cast<int32_t>(s.unpacked[x]) - ctx.black;
    r[-1] = r[1];
    r[-2] = r[2];
    r[raw.width] = r[raw.width - 2];
    r[raw.width + 1] = r[raw.width - 3];
}

/**
 * Develops output rows [y0, y1) of the active area (Malvar-He-Cutler demosaic) into s.out as
 * 8-bit sRGB or, with [linear], into it as 16-bit linear RGB in LUT units (row stride aw*3),
 * for the lens-corrected path that resamples before the tone curve.
 */
void developStrip(const Context& ctx, Strip& s, int y0, int y1, uint16_t* linear = nullptr) {
    const RawFrame& raw = ctx.raw;
    const int aw = raw.right - raw.left;
    const int rows = y1 - y0;
    for (int i = 0; i < rows + 4; i++) loadRow(ctx, s, i, raw.top + y0 + i - 2);

    const float* m = ctx.m;
    const uint8_t* lut = ctx.lut.data();
    for (int j = 0; j < rows; j++) {
        const int32_t* m2 = s.row(j);
        const int32_t* m1 = s.row(j + 1);
        const int32_t* r0 = s.row(j + 2);
        const int32_t* p1 = s.row(j + 3);
        const int32_t* p2 = s.row(j + 4);
        const int cy = (y0 + j) & 1;
        uint8_t* o = linear ? nullptr : s.out.data() + static_cast<size_t>(j) * aw * 3;
        uint16_t* lo = linear ? linear + static_cast<size_t>(j) * aw * 3 : nullptr;
        for (int ax = 0; ax < aw; ax++) {
            const int x = raw.left + ax;
            const int c = raw.cfa[cy * 2 + (ax & 1)];
            const int32_t C = r0[x];
            int32_t R, G, B;
            if (c == 1) {
                const int32_t diag = m1[x - 1] + m1[x + 1] + p1[x - 1] + p1[x + 1];
                // Colour along the row (horizontal 4s) and along the column (vertical 4s)
                const int32_t h = (10 * C + 8 * (r0[x - 1] + r0[x + 1]) - 2 * (r0[x - 2] + r0[x + 2]) +
                                   (m2[x] + p2[x]) - 2 * diag) >> 4;
                const int32_t v = (10 * C + 8 * (m1[x] + p1[x]) - 2 * (m2[x] + p2[x]) +
                                   (r0[x - 2] + r0[x + 2]) - 2 * diag) >> 4;
                G = C;
                if (raw.cfa[cy * 2 + ((ax + 1) & 1)] == 0) {
                    R = h;
                    B = v;
                } else {
                    B = h;
                    R = v;
                }
            } else {
                const int32_t cross2 = m2[x] + p2[x] + r0[x - 2] + r0[x + 2];
                G = (8 * C + 4 * (m1[x] + p1[x] + r0[x - 1] + r0[x + 1]) - 2 * cross2) >> 4;
                const int32_t other = (12 * C + 4 * (m1[x - 1] + m1[x + 1] + p1[x - 1] + p1[x + 1]) - 3 * cross2) >> 4;
                if (c == 0) {
                    R = C;
                    B = other;
                } else {
                    B = C;
                    R = other;
                }
            }
            // White balance, then every channel clipped where the sensor clips: blown
            // highlights go white instead of magenta. Exposure and the matrix are folded into m.
            const float fr = std::min(static_cast<float>(std::max(R, 0)) * ctx.wb[0], ctx.clip);
            const float fg = std::min(static_cast<float>(std::max(G, 0)) * ctx.wb[1], ctx.clip);
            const float fb = std::min(static_cast<float>(std::max(B, 0)) * ctx.wb[2], ctx.clip);
            for (int k = 0; k < 3; k++) {
                float v = m[k * 3] * fr + m[k * 3 + 1] * fg + m[k * 3 + 2] * fb;
                int idx = static_cast<int>(v);
                if (lo) {
                    lo[ax * 3 + k] = static_cast<uint16_t>(std::clamp(idx, 0, 65535));
                } else {
                    o[ax * 3 + k] = lut[std::clamp(idx, 0, kLutSize)];
                }
            }
        }
    }
}

}  // namespace

double autoExposure(const RawFrame& raw, const double neutral[3]) {
    // Centre half of the active area, green only (neutral g is 1)
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    double sum = 0;
    long n = 0;
    // Even offsets from the frame origin, so i below walks the CFA as raw.cfa describes it
    for (int y = (raw.top + ah / 4) & ~1; y < raw.top + 3 * ah / 4; y += 16) {
        for (int x = (raw.left + aw / 4) & ~1; x < raw.left + 3 * aw / 4; x += 16) {
            for (int i = 0; i < 4; i++) {
                if (raw.cfa[i] != 1) continue;
                sum += std::max(0.0, raw.at(x + (i & 1), y + (i >> 1)) - raw.black);
                n++;
            }
        }
    }
    (void)neutral;  // green carries the exposure; kept for a later luminance-based meter
    const double mean = n ? sum / n / (raw.white - raw.black) : 0;
    return mean > 1e-6 ? std::clamp(0.18 / mean, 0.5, 8.0) : 1.0;
}

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

bool develop(const RawFrame& raw, const DevelopParams& p, const RgbSink& sink) {
    if (!raw.valid()) return false;
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const Context ctx = makeContext(raw, p);

    const int threads = std::max(1, p.threads);
    const int stripRows = std::max(8, p.stripRows & ~1);  // even, to keep the CFA phase per strip
    std::vector<Strip> strips(threads);
    for (auto& s : strips) {
        s.width = raw.width;
        s.rows.resize(static_cast<size_t>(stripRows + 4) * (raw.width + 4));
        s.unpacked.resize(raw.width);
        s.out.resize(static_cast<size_t>(stripRows) * aw * 3);
    }

    // Batches of `threads` strips developed in parallel, handed to the sink in order
    for (int y = 0; y < ah; y += stripRows * threads) {
        std::vector<std::thread> pool;
        int count = 0;
        for (int t = 0; t < threads; t++) {
            const int y0 = y + t * stripRows;
            if (y0 >= ah) break;
            const int y1 = std::min(ah, y0 + stripRows);
            count++;
            if (t == threads - 1 || y1 == ah) {
                developStrip(ctx, strips[t], y0, y1);  // the calling thread does one strip itself
            } else {
                pool.emplace_back(developStrip, std::cref(ctx), std::ref(strips[t]), y0, y1, static_cast<uint16_t*>(nullptr));
            }
        }
        for (auto& th : pool) th.join();
        for (int t = 0; t < count; t++) {
            const int y0 = y + t * stripRows;
            const int rows = std::min(ah, y0 + stripRows) - y0;
            if (!sink(y0, rows, strips[t].out.data())) return false;
        }
    }
    return true;
}

namespace {

/** Runs fn(i) for i in [0, n) on [threads] threads, the calling one included. */
template <typename F>
void parallelFor(int n, int threads, F fn) {
    const int t = std::clamp(threads, 1, std::max(1, n));
    std::vector<std::thread> pool;
    for (int k = 1; k < t; k++) {
        pool.emplace_back([&, k] {
            for (int i = k; i < n; i += t) fn(i, k);
        });
    }
    for (int i = 0; i < n; i += t) fn(i, 0);
    for (auto& th : pool) th.join();
}

constexpr int kDemosaicChunk = 16;  // source rows per demosaic task (even: CFA phase)
constexpr int kSharpenMargin = 2;   // rows the 5-tap blur reads above and below

/** Catmull-Rom weights for a sample at fraction [t] past the second of four taps. */
inline void cubicWeights(float t, float w[4]) {
    const float t2 = t * t, t3 = t2 * t;
    w[0] = 0.5f * (-t3 + 2 * t2 - t);
    w[1] = 0.5f * (3 * t3 - 5 * t2 + 2);
    w[2] = 0.5f * (-3 * t3 + 4 * t2 + t);
    w[3] = 0.5f * (t3 - t2);
}

}  // namespace

bool developCorrected(const RawFrame& raw, const DevelopParams& p, const CorrectedParams& cp, const RgbSink& sink) {
    if (!raw.valid()) return false;
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const Context ctx = makeContext(raw, p);

    const int W = kIspWidth;
    const int H = kIspHeight;
    const float vign = static_cast<float>(cp.vignette);
    const float N = static_cast<float>(kModelRadiusPx);
    // The source rows output rows [oy0, oy1) read: sampled along the first and last rows
    auto span = [&](int oy0, int oy1, int& lo, int& hi) {
        double mn = 1e9, mx = -1e9;
        for (int yy : {oy0, oy1 - 1}) {
            for (int i = 0; i <= 32; i++) {
                double sx, sy;
                ispToActive(cp.lens, (W - 1) * i / 32.0 + 0.5, yy + 0.5, sx, sy);
                mn = std::min(mn, sy);
                mx = std::max(mx, sy);
            }
        }
        // At least 6 rows, so the Catmull-Rom clamp range below is never empty
        lo = std::clamp(static_cast<int>(std::floor(mn)) - 3, 0, ah - 6) & ~1;
        hi = std::clamp(static_cast<int>(std::ceil(mx)) + 4, lo + 6, ah);
    };
    const int stripRows = std::max(1, cp.stripRows);
    // Each step resamples its rows plus the sharpening margin
    auto stepRows = [&](int oy0, int& a, int& b) {
        a = std::max(0, oy0 - kSharpenMargin);
        b = std::min(H, oy0 + stripRows + kSharpenMargin);
    };
    int cap = 0;
    for (int oy = 0; oy < H; oy += stripRows) {
        int a, b, lo, hi;
        stepRows(oy, a, b);
        span(a, b, lo, hi);
        cap = std::max(cap, hi - lo + kDemosaicChunk);
    }

    // Ring of demosaiced rows (16-bit linear, LUT units), indexed by active-area row % cap
    const size_t rowLen = static_cast<size_t>(aw) * 3;
    std::vector<uint16_t> ring(static_cast<size_t>(cap) * rowLen);
    auto ringRow = [&](int y) { return ring.data() + static_cast<size_t>(y % cap) * rowLen; };
    const int threads = std::max(1, p.threads);
    std::vector<Strip> strips(threads);
    std::vector<std::vector<uint16_t>> tmp(threads);
    for (int t = 0; t < threads; t++) {
        strips[t].width = raw.width;
        strips[t].rows.resize(static_cast<size_t>(kDemosaicChunk + 4) * (raw.width + 4));
        strips[t].unpacked.resize(raw.width);
        tmp[t].resize(kDemosaicChunk * rowLen);
    }
    const int bufRows = stripRows + 2 * kSharpenMargin;
    std::vector<uint8_t> toned(static_cast<size_t>(bufRows) * W * 3);  // resampled, tone-mapped
    std::vector<int16_t> luma(static_cast<size_t>(bufRows) * W);
    std::vector<int16_t> hblur(static_cast<size_t>(bufRows) * W);  // luma × 16, blurred along rows
    std::vector<uint8_t> out(static_cast<size_t>(stripRows) * W * 3);
    const uint8_t* lut = ctx.lut.data();
    const bool sharpen = cp.sharpenAmount > 0;
    const int amount = static_cast<int>(std::lround(cp.sharpenAmount * 256));
    const int thresh = std::max(1, static_cast<int>(std::lround(cp.sharpenThreshold * 256)));
    const int limit = cp.sharpenLimit;
    int haveLo = 0, haveHi = 0;

    for (int oy0 = 0; oy0 < H; oy0 += stripRows) {
        const int oy1 = std::min(H, oy0 + stripRows);
        int a, b, lo, hi;
        stepRows(oy0, a, b);
        span(a, b, lo, hi);
        // Rows below lo are no longer needed; demosaic what is missing up to hi
        const int start = std::max(lo, haveHi) & ~1;
        haveLo = std::max(haveLo, lo);
        if (start < hi) {
            const int chunks = (hi - start + kDemosaicChunk - 1) / kDemosaicChunk;
            parallelFor(chunks, threads, [&](int i, int t) {
                const int y0 = start + i * kDemosaicChunk;
                const int y1 = std::min(hi, y0 + kDemosaicChunk);
                developStrip(ctx, strips[t], y0, y1, tmp[t].data());
                for (int y = y0; y < y1; y++) {
                    std::copy_n(tmp[t].data() + static_cast<size_t>(y - y0) * rowLen, rowLen, ringRow(y));
                }
            });
            haveHi = hi;
        }
        // Resample rows [a, b): Catmull-Rom in the ring, vignetting, tone curve, luma
        parallelFor(b - a, threads, [&](int j, int) {
            const int oy = a + j;
            uint8_t* o = toned.data() + static_cast<size_t>(j) * W * 3;
            int16_t* yl = luma.data() + static_cast<size_t>(j) * W;
            for (int ox = 0; ox < W; ox++) {
                double dsx, dsy;
                ispToActive(cp.lens, ox + 0.5, oy + 0.5, dsx, dsy);
                // The model maps pixel centres (i + 0.5); the ring holds sample i at index i
                const float sx = std::clamp(static_cast<float>(dsx - 0.5), 1.0f, aw - 3.001f);
                const float sy = std::clamp(static_cast<float>(dsy - 0.5), haveLo + 1.0f, haveHi - 3.001f);
                const int x0 = static_cast<int>(sx);
                const int y0 = static_cast<int>(sy);
                float wx[4], wy[4];
                cubicWeights(sx - x0, wx);
                cubicWeights(sy - y0, wy);
                const float rx = (sx - static_cast<float>(kOpticalCentreX)) / N;
                const float ry = (sy - static_cast<float>(kOpticalCentreY)) / N;
                const float gain = 1.0f / std::max(0.2f, 1.0f + vign * (rx * rx + ry * ry));
                float acc[3] = {0, 0, 0};
                for (int r = 0; r < 4; r++) {
                    const uint16_t* s = ringRow(y0 - 1 + r) + (x0 - 1) * 3;
                    float row[3] = {0, 0, 0};
                    for (int q = 0; q < 4; q++) {
                        row[0] += wx[q] * s[q * 3];
                        row[1] += wx[q] * s[q * 3 + 1];
                        row[2] += wx[q] * s[q * 3 + 2];
                    }
                    acc[0] += wy[r] * row[0];
                    acc[1] += wy[r] * row[1];
                    acc[2] += wy[r] * row[2];
                }
                for (int k = 0; k < 3; k++) {
                    o[ox * 3 + k] = lut[std::clamp(static_cast<int>(acc[k] * gain), 0, kLutSize)];
                }
                yl[ox] = static_cast<int16_t>((77 * o[ox * 3] + 150 * o[ox * 3 + 1] + 29 * o[ox * 3 + 2]) >> 8);
            }
            // Horizontal 1-4-6-4-1 blur of the luma (edges clamped)
            int16_t* hb = hblur.data() + static_cast<size_t>(j) * W;
            for (int ox = 0; ox < W; ox++) {
                const int l2 = yl[std::max(ox - 2, 0)], l1 = yl[std::max(ox - 1, 0)];
                const int r1 = yl[std::min(ox + 1, W - 1)], r2 = yl[std::min(ox + 2, W - 1)];
                hb[ox] = static_cast<int16_t>(l2 + 4 * l1 + 6 * yl[ox] + 4 * r1 + r2);
            }
        });
        // Sharpen rows [oy0, oy1): detail = luma − blur, cored and limited, added to R, G, B
        const int rows = oy1 - oy0;
        parallelFor(rows, threads, [&](int j, int) {
            const int oy = oy0 + j;
            const int jr = oy - a;  // this row in the step buffers
            const uint8_t* src = toned.data() + static_cast<size_t>(jr) * W * 3;
            uint8_t* o = out.data() + static_cast<size_t>(j) * W * 3;
            if (!sharpen) {
                std::copy_n(src, static_cast<size_t>(W) * 3, o);
                return;
            }
            const int16_t* hr[5];
            for (int k = 0; k < 5; k++) {
                const int y = std::clamp(oy - 2 + k, a, b - 1);
                hr[k] = hblur.data() + static_cast<size_t>(y - a) * W;
            }
            const int16_t* yl = luma.data() + static_cast<size_t>(jr) * W;
            for (int ox = 0; ox < W; ox++) {
                // blur × 256 = vertical 1-4-6-4-1 of the horizontal pass
                const int blur = hr[0][ox] + 4 * hr[1][ox] + 6 * hr[2][ox] + 4 * hr[3][ox] + hr[4][ox];
                const int d = yl[ox] * 256 - blur;  // detail × 256
                const int ad = std::abs(d);
                // Soft coring: below the threshold the gain ramps up from 0
                int64_t delta = static_cast<int64_t>(d) * amount;
                if (ad < thresh) delta = delta * ad / thresh;
                const int dv = std::clamp(static_cast<int>(delta >> 16), -limit, limit);
                for (int k = 0; k < 3; k++) o[ox * 3 + k] = static_cast<uint8_t>(std::clamp(src[ox * 3 + k] + dv, 0, 255));
            }
        });
        if (!sink(oy0, rows, out.data())) return false;
    }
    return true;
}

}  // namespace gccore
