// SPDX-License-Identifier: GPL-3.0-or-later
#include "gccore/develop2.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <thread>
#include <vector>

#include "gccore/demosaic.h"

namespace gccore {

namespace {

constexpr float kPi = 3.14159265358979f;

/** Runs fn(y) for y in [0, n), in chunks of rows shared out on [threads] threads. */
template <typename F>
void parallelRows(int n, int threads, F fn) {
    constexpr int kChunk = 16;
    std::atomic<int> next{0};
    auto worker = [&] {
        for (int y0 = next.fetch_add(kChunk); y0 < n; y0 = next.fetch_add(kChunk)) {
            for (int y = y0; y < std::min(n, y0 + kChunk); y++) fn(y);
        }
    };
    std::vector<std::thread> pool;
    for (int k = 1; k < std::max(1, threads); k++) pool.emplace_back(worker);
    worker();
    for (auto& t : pool) t.join();
}

std::vector<float> gaussKernel(double sigma) {
    const int r = std::max(1, static_cast<int>(std::ceil(3 * sigma)));
    std::vector<float> k(2 * r + 1);
    double sum = 0;
    for (int i = -r; i <= r; i++) sum += k[i + r] = static_cast<float>(std::exp(-0.5 * i * i / (sigma * sigma)));
    for (auto& v : k) v = static_cast<float>(v / sum);
    return k;
}

/** Separable convolution of a w x h plane with [k] (odd length), edges clamped. */
void blur(const float* in, float* out, float* tmp, int w, int h, const std::vector<float>& k, int threads) {
    const int r = static_cast<int>(k.size()) / 2;
    parallelRows(h, threads, [&](int y) {
        const float* s = in + static_cast<size_t>(y) * w;
        float* d = tmp + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int i = -r; i <= r; i++) acc += k[i + r] * s[std::clamp(x + i, 0, w - 1)];
            d[x] = acc;
        }
    });
    parallelRows(h, threads, [&](int y) {
        float* d = out + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) d[x] = 0;
        for (int i = -r; i <= r; i++) {
            const float* s = tmp + static_cast<size_t>(std::clamp(y + i, 0, h - 1)) * w;
            const float kw = k[i + r];
            for (int x = 0; x < w; x++) d[x] += kw * s[x];
        }
    });
}

/** Malvar-He-Cutler on a float plane (the first pipeline's demosaic, for comparison). */
void malvar(int w, int h, const float* cfa, const int pat[4], float* const out[3], int threads) {
    auto at = [&](int x, int y) {
        // Mirrored by two keeps the CFA phase
        x = x < 0 ? -x : (x >= w ? 2 * (w - 1) - x : x);
        y = y < 0 ? -y : (y >= h ? 2 * (h - 1) - y : y);
        return cfa[static_cast<size_t>(y) * w + x];
    };
    parallelRows(h, threads, [&](int y) {
        for (int x = 0; x < w; x++) {
            const int c = pat[(y & 1) * 2 + (x & 1)];
            const float C = at(x, y);
            float rgb[3];
            rgb[c] = C;
            if (c == 1) {
                const float diag = at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1);
                const float hz = (10 * C + 8 * (at(x - 1, y) + at(x + 1, y)) - 2 * (at(x - 2, y) + at(x + 2, y)) +
                                  (at(x, y - 2) + at(x, y + 2)) - 2 * diag) / 16;
                const float vt = (10 * C + 8 * (at(x, y - 1) + at(x, y + 1)) - 2 * (at(x, y - 2) + at(x, y + 2)) +
                                  (at(x - 2, y) + at(x + 2, y)) - 2 * diag) / 16;
                const int rowColour = pat[(y & 1) * 2 + ((x + 1) & 1)];  // the other colour on this row
                rgb[rowColour] = hz;
                rgb[2 - rowColour] = vt;
            } else {
                const float cross2 = at(x, y - 2) + at(x, y + 2) + at(x - 2, y) + at(x + 2, y);
                rgb[1] = (8 * C + 4 * (at(x, y - 1) + at(x, y + 1) + at(x - 1, y) + at(x + 1, y)) - 2 * cross2) / 16;
                rgb[2 - c] = (12 * C + 4 * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) - 3 * cross2) / 16;
            }
            const size_t o = static_cast<size_t>(y) * w + x;
            for (int k = 0; k < 3; k++) out[k][o] = std::max(0.0f, rgb[k]);
        }
    });
}

/**
 * Richardson-Lucy on linear luminance with a Gaussian PSF, then the luminance gain applied to
 * R, G and B alike (no colour change). Clamped to a 4x gain either way against noise blow-ups.
 */
void captureSharpen(float* const rgb[3], int w, int h, double sigma, int iterations, int threads) {
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> y(n), u(n), est(n), tmp(n);
    parallelRows(h, threads, [&](int row) {
        for (size_t i = static_cast<size_t>(row) * w, e = i + w; i < e; i++) {
            y[i] = std::max(1e-5f, 0.2126f * rgb[0][i] + 0.7152f * rgb[1][i] + 0.0722f * rgb[2][i]);
            u[i] = y[i];
        }
    });
    const std::vector<float> k = gaussKernel(sigma);
    for (int it = 0; it < iterations; it++) {
        blur(u.data(), est.data(), tmp.data(), w, h, k, threads);
        parallelRows(h, threads, [&](int row) {
            for (size_t i = static_cast<size_t>(row) * w, e = i + w; i < e; i++) est[i] = y[i] / std::max(1e-5f, est[i]);
        });
        blur(est.data(), est.data(), tmp.data(), w, h, k, threads);
        parallelRows(h, threads, [&](int row) {
            for (size_t i = static_cast<size_t>(row) * w, e = i + w; i < e; i++) u[i] *= est[i];
        });
    }
    parallelRows(h, threads, [&](int row) {
        for (size_t i = static_cast<size_t>(row) * w, e = i + w; i < e; i++) {
            const float g = std::clamp(u[i] / y[i], 0.25f, 4.0f);
            for (int c = 0; c < 3; c++) rgb[c][i] *= g;
        }
    });
}

/** One à-trous B3-spline smoothing of [in] at hole spacing [step], edges clamped. */
void atrousSmooth(const float* in, float* out, float* tmp, int w, int h, int step, int threads) {
    constexpr float k[5] = {1 / 16.f, 4 / 16.f, 6 / 16.f, 4 / 16.f, 1 / 16.f};
    parallelRows(h, threads, [&](int y) {
        const float* s = in + static_cast<size_t>(y) * w;
        float* d = tmp + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int i = -2; i <= 2; i++) acc += k[i + 2] * s[std::clamp(x + i * step, 0, w - 1)];
            d[x] = acc;
        }
    });
    parallelRows(h, threads, [&](int y) {
        float* d = out + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) d[x] = 0;
        for (int i = -2; i <= 2; i++) {
            const float* s = tmp + static_cast<size_t>(std::clamp(y + i * step, 0, h - 1)) * w;
            for (int x = 0; x < w; x++) d[x] += k[i + 2] * s[x];
        }
    });
}

/** Robust noise of a detail plane: the median absolute value / 0.6745, from a sparse sample. */
float madSigma(const float* d, size_t n) {
    std::vector<float> s;
    s.reserve(n / 7 + 1);
    for (size_t i = 0; i < n; i += 7) s.push_back(std::fabs(d[i]));
    std::nth_element(s.begin(), s.begin() + s.size() / 2, s.end());
    return s[s.size() / 2] / 0.6745f;
}

/**
 * Wavelet shrinkage of one plane with unit-ish noise: 5 à-trous levels, each soft-thresholded
 * at strength[l] × its noise. The noise is measured on levels 0 and 1 (nearly all noise there)
 * and carried to the coarser levels with the B3 white-noise ratios, which the image's own
 * structure would otherwise inflate.
 */
void shrinkPlane(float* p, int w, int h, const float strength[5], int threads) {
    constexpr int kLevels = 5;
    // Noise of a unit white-noise image in each B3 à-trous level, relative to level 1
    constexpr float kRatio[kLevels] = {3.94f, 1.0f, 0.43f, 0.21f, 0.10f};
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> cur(p, p + n), next(n), tmp(n), out(n, 0.0f), detail(n);
    float sigma1 = 0;
    for (int l = 0; l < kLevels; l++) {
        atrousSmooth(cur.data(), next.data(), tmp.data(), w, h, 1 << l, threads);
        parallelRows(h, threads, [&](int y) {
            for (size_t i = static_cast<size_t>(y) * w, e = i + w; i < e; i++) detail[i] = cur[i] - next[i];
        });
        float sigma;
        if (l == 0) {
            sigma = madSigma(detail.data(), n);
        } else if (l == 1) {
            sigma = sigma1 = madSigma(detail.data(), n);
        } else {
            sigma = sigma1 * kRatio[l];
        }
        const float t = strength[l] * sigma;
        parallelRows(h, threads, [&](int y) {
            for (size_t i = static_cast<size_t>(y) * w, e = i + w; i < e; i++) {
                const float d = detail[i];
                const float a = std::fabs(d) - t;
                out[i] += a > 0 ? std::copysign(a, d) : 0.0f;
            }
        });
        std::swap(cur, next);
    }
    parallelRows(h, threads, [&](int y) {
        for (size_t i = static_cast<size_t>(y) * w, e = i + w; i < e; i++) p[i] = out[i] + cur[i];
    });
}

/**
 * Noise reduction on demosaiced, white-balanced camera RGB in 0..1 ([gain] per channel: the
 * white balance over the 10-bit range, so the raw model's a and b carry over).
 */
void denoise(float* const rgb[3], int w, int h, const float gain[3], double a, double b, double luma, double chroma,
             int chromaDrop, int threads) {
    const size_t n = static_cast<size_t>(w) * h;
    // Per channel: x in 0..1 is raw counts × gain, so var(x) = ac x + bc
    float ac[3], s2[3];
    for (int c = 0; c < 3; c++) {
        ac[c] = static_cast<float>(a * gain[c]);
        s2[c] = static_cast<float>(b * gain[c] * gain[c]) / (ac[c] * ac[c]);  // read noise in the scaled domain
    }
    // Generalised Anscombe per channel, then an orthonormal luma/chroma basis (noise stays ~1)
    parallelRows(h, threads, [&](int y) {
        for (size_t i = static_cast<size_t>(y) * w, e = i + w; i < e; i++) {
            float z[3];
            for (int c = 0; c < 3; c++) z[c] = 2.0f * std::sqrt(std::max(rgb[c][i] / ac[c] + 0.375f + s2[c], 0.0f));
            rgb[0][i] = (z[0] + z[1] + z[2]) * 0.57735027f;
            rgb[1][i] = (z[0] - z[2]) * 0.70710678f;
            rgb[2][i] = (z[0] - 2 * z[1] + z[2]) * 0.40824829f;
        }
    });
    const float l = static_cast<float>(luma), c = static_cast<float>(chroma);
    const float lumaStrength[5] = {l * 1.0f, l * 0.9f, l * 0.6f, l * 0.3f, l * 0.15f};
    float chromaStrength[5] = {c, c, c * 0.9f, c * 0.7f, c * 0.5f};
    // A dropped level: a threshold no coefficient reaches
    for (int k = 0; k < std::min(chromaDrop, 5); k++) chromaStrength[k] = 1e9f;
    if (l > 0) shrinkPlane(rgb[0], w, h, lumaStrength, threads);
    if (c > 0 || chromaDrop > 0) {
        shrinkPlane(rgb[1], w, h, chromaStrength, threads);
        shrinkPlane(rgb[2], w, h, chromaStrength, threads);
    }
    // Back to the three channels, then the inverse transform: Mäkitalo & Foi's closed-form
    // approximation of the exact unbiased inverse, the algebraic one where z is too small for it
    const float k1 = 0.25f * std::sqrt(1.5f), k3 = 0.625f * std::sqrt(1.5f);
    parallelRows(h, threads, [&](int y) {
        for (size_t i = static_cast<size_t>(y) * w, e = i + w; i < e; i++) {
            const float Y = rgb[0][i], U = rgb[1][i], V = rgb[2][i];
            const float z[3] = {Y * 0.57735027f + U * 0.70710678f + V * 0.40824829f,
                                Y * 0.57735027f - V * 0.81649658f,
                                Y * 0.57735027f - U * 0.70710678f + V * 0.40824829f};
            for (int ch = 0; ch < 3; ch++) {
                const float zz = std::max(z[ch], 0.0f);
                float v;
                if (zz > 2.5f) {
                    v = 0.25f * zz * zz + k1 / zz - 1.375f / (zz * zz) + k3 / (zz * zz * zz) - 0.125f - s2[ch];
                } else {
                    v = 0.25f * zz * zz - 0.375f - s2[ch];
                }
                rgb[ch][i] = std::max(0.0f, v * ac[ch]);
            }
        }
    });
    (void)n;
}

inline float lanczos3(float x) {
    if (x == 0) return 1;
    if (x <= -3 || x >= 3) return 0;
    const float px = kPi * x;
    return 3 * std::sin(px) * std::sin(px / 3) / (px * px);
}

inline void catmullRom(float t, float w[4]) {
    const float t2 = t * t, t3 = t2 * t;
    w[0] = 0.5f * (-t3 + 2 * t2 - t);
    w[1] = 0.5f * (3 * t3 - 5 * t2 + 2);
    w[2] = 0.5f * (-3 * t3 + 4 * t2 + t);
    w[3] = 0.5f * (t3 - t2);
}

}  // namespace

bool develop2(const RawFrame& raw, const Develop2Params& p, const RgbSink& sink) {
    if (!raw.valid()) return false;
    const int threads = std::max(1, p.base.threads);
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const size_t an = static_cast<size_t>(aw) * ah;

    // 1. The active area as linear, white-balanced CFA values; 1 = where every channel clips
    int pat[4];
    for (int i = 0; i < 4; i++) pat[i] = raw.cfa[(((i >> 1) + raw.top) & 1) * 2 + (((i & 1) + raw.left) & 1)];
    float wb[3];
    for (int c = 0; c < 3; c++) wb[c] = static_cast<float>(1.0 / std::max(p.base.neutral[c], 1e-6));
    const float black = static_cast<float>(p.black > 0 ? p.black : raw.black);
    const float range = static_cast<float>(raw.white - raw.black);
    std::vector<float> cfa(an);
    parallelRows(ah, threads, [&](int y) {
        std::vector<uint16_t> row(raw.width);
        raw.unpackRow(raw.top + y, row.data());
        for (int x = 0; x < aw; x++) {
            const int c = pat[(y & 1) * 2 + (x & 1)];
            cfa[static_cast<size_t>(y) * aw + x] = std::min(1.0f, std::max(0.0f, row[raw.left + x] - black) / range * wb[c]);
        }
    });

    // 2. Demosaic
    std::vector<float> planes(3 * an);
    float* const rgb[3] = {planes.data(), planes.data() + an, planes.data() + 2 * an};
    if (p.rcd) {
        rcdDemosaic(aw, ah, cfa.data(), pat, rgb[0], rgb[1], rgb[2], threads);
    } else {
        malvar(aw, ah, cfa.data(), pat, rgb, threads);
    }
    std::vector<float>().swap(cfa);

    // 2b. Noise reduction, while the noise still follows the raw model
    if (p.noiseA > 0 && (p.nrLuma > 0 || p.nrChroma > 0)) {
        float gain[3];
        for (int c = 0; c < 3; c++) gain[c] = wb[c] / range;
        denoise(rgb, aw, ah, gain, p.noiseA, p.noiseB, p.nrLuma, p.nrChroma, p.chromaDropLevels, threads);
    }

    // 3. Camera RGB → linear sRGB, with the exposure
    const double exposure = p.base.exposure > 0 ? p.base.exposure : autoExposure(raw, p.base.neutral);
    float m[9];
    for (int i = 0; i < 9; i++) m[i] = static_cast<float>(p.base.camToSrgb[i] * exposure);
    parallelRows(ah, threads, [&](int y) {
        for (size_t i = static_cast<size_t>(y) * aw, e = i + aw; i < e; i++) {
            const float r = rgb[0][i], g = rgb[1][i], b = rgb[2][i];
            for (int k = 0; k < 3; k++) rgb[k][i] = std::max(0.0f, m[k * 3] * r + m[k * 3 + 1] * g + m[k * 3 + 2] * b);
        }
    });

    // 4. Capture sharpening on the sensor grid
    if (p.rlSigma > 0 && p.rlIterations > 0) captureSharpen(rgb, aw, ah, p.rlSigma, p.rlIterations, threads);

    // 5. Lens warp into the ISP frame, vignetting evened out, tone curve
    const int W = kIspWidth;
    const int H = kIspHeight;
    const size_t on = static_cast<size_t>(W) * H;
    std::vector<float> outPlanes(3 * on);
    float* const o[3] = {outPlanes.data(), outPlanes.data() + on, outPlanes.data() + 2 * on};
    constexpr int kLutPerUnit = 4096;
    constexpr int kLutSize = 4 * kLutPerUnit;
    std::vector<float> lut(kLutSize + 1);
    for (int i = 0; i <= kLutSize; i++) lut[i] = static_cast<float>(toneCurve(static_cast<double>(i) / kLutPerUnit));
    const float vign = static_cast<float>(p.vignette);
    const float N = static_cast<float>(kModelRadiusPx);
    parallelRows(H, threads, [&](int oy) {
        for (int ox = 0; ox < W; ox++) {
            double dsx, dsy;
            ispToActive(p.lens, ox + 0.5, oy + 0.5, dsx, dsy);
            // The model maps pixel centres (i + 0.5); plane sample i sits at index i
            const float sx = std::clamp(static_cast<float>(dsx - 0.5), 0.0f, aw - 1.001f);
            const float sy = std::clamp(static_cast<float>(dsy - 0.5), 0.0f, ah - 1.001f);
            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            float acc[3] = {0, 0, 0};
            if (p.warpLobes >= 3) {
                float wx[6], wy[6], sw = 0, sh = 0;
                for (int i = 0; i < 6; i++) {
                    sw += wx[i] = lanczos3(sx - (x0 - 2 + i));
                    sh += wy[i] = lanczos3(sy - (y0 - 2 + i));
                }
                for (int j = 0; j < 6; j++) {
                    const size_t rowOff = static_cast<size_t>(std::clamp(y0 - 2 + j, 0, ah - 1)) * aw;
                    float row[3] = {0, 0, 0};
                    for (int i = 0; i < 6; i++) {
                        const size_t s = rowOff + std::clamp(x0 - 2 + i, 0, aw - 1);
                        for (int c = 0; c < 3; c++) row[c] += wx[i] * rgb[c][s];
                    }
                    for (int c = 0; c < 3; c++) acc[c] += wy[j] * row[c];
                }
                // Held within the four nearest samples: Lanczos' ringing never leaves them
                for (int c = 0; c < 3; c++) {
                    acc[c] /= sw * sh;
                    const size_t s00 = static_cast<size_t>(y0) * aw + x0;
                    const float a = rgb[c][s00], b = rgb[c][s00 + 1], cc = rgb[c][s00 + aw], d = rgb[c][s00 + aw + 1];
                    acc[c] = std::clamp(acc[c], std::min({a, b, cc, d}), std::max({a, b, cc, d}));
                }
            } else {
                float wx[4], wy[4];
                catmullRom(sx - x0, wx);
                catmullRom(sy - y0, wy);
                for (int j = 0; j < 4; j++) {
                    const size_t rowOff = static_cast<size_t>(std::clamp(y0 - 1 + j, 0, ah - 1)) * aw;
                    for (int i = 0; i < 4; i++) {
                        const size_t s = rowOff + std::clamp(x0 - 1 + i, 0, aw - 1);
                        for (int c = 0; c < 3; c++) acc[c] += wy[j] * wx[i] * rgb[c][s];
                    }
                }
            }
            const float rx = (sx - static_cast<float>(kOpticalCentreX)) / N;
            const float ry = (sy - static_cast<float>(kOpticalCentreY)) / N;
            const float gain = 1.0f / std::max(0.2f, 1.0f + vign * (rx * rx + ry * ry));
            const size_t oi = static_cast<size_t>(oy) * W + ox;
            for (int c = 0; c < 3; c++) {
                const float v = std::clamp(acc[c] * gain * kLutPerUnit, 0.0f, static_cast<float>(kLutSize));
                o[c][oi] = lut[static_cast<int>(v)];
            }
        }
    });
    std::vector<float>().swap(planes);

    // 6. Output sharpening on gamma luma, halo-clamped
    if (p.sharpenAmount > 0) {
        std::vector<float> luma(on), soft(on), tmp(on);
        parallelRows(H, threads, [&](int y) {
            for (size_t i = static_cast<size_t>(y) * W, e = i + W; i < e; i++) {
                luma[i] = 0.299f * o[0][i] + 0.587f * o[1][i] + 0.114f * o[2][i];
            }
        });
        blur(luma.data(), soft.data(), tmp.data(), W, H, gaussKernel(p.sharpenSigma), threads);
        const float amount = static_cast<float>(p.sharpenAmount);
        const float thresh = static_cast<float>(std::max(1e-3, p.sharpenThreshold));
        const float over = static_cast<float>(p.overshoot);
        parallelRows(H, threads, [&](int y) {
            for (int x = 0; x < W; x++) {
                const size_t i = static_cast<size_t>(y) * W + x;
                float d = luma[i] - soft[i];
                if (std::fabs(d) < thresh) d *= std::fabs(d) / thresh;  // soft coring: noise stays
                float mn = luma[i], mx = luma[i];
                for (int dy = -1; dy <= 1; dy++) {
                    const size_t r = static_cast<size_t>(std::clamp(y + dy, 0, H - 1)) * W;
                    for (int dx = -1; dx <= 1; dx++) {
                        const float v = luma[r + std::clamp(x + dx, 0, W - 1)];
                        mn = std::min(mn, v);
                        mx = std::max(mx, v);
                    }
                }
                const float room = over * (mx - mn);
                const float sharp = std::clamp(luma[i] + amount * d, mn - room, mx + room);
                tmp[i] = sharp - luma[i];
            }
        });
        parallelRows(H, threads, [&](int y) {
            for (size_t i = static_cast<size_t>(y) * W, e = i + W; i < e; i++) {
                for (int c = 0; c < 3; c++) o[c][i] += tmp[i];
            }
        });
    }

    // 7. Interleaved 8-bit rows to the sink
    constexpr int kSinkRows = 64;
    std::vector<uint8_t> buf(static_cast<size_t>(kSinkRows) * W * 3);
    for (int y0 = 0; y0 < H; y0 += kSinkRows) {
        const int rows = std::min(kSinkRows, H - y0);
        for (int j = 0; j < rows; j++) {
            const size_t base = static_cast<size_t>(y0 + j) * W;
            uint8_t* d = buf.data() + static_cast<size_t>(j) * W * 3;
            for (int x = 0; x < W; x++) {
                for (int c = 0; c < 3; c++) d[x * 3 + c] = static_cast<uint8_t>(std::lround(std::clamp(o[c][base + x], 0.0f, 255.0f)));
            }
        }
        if (!sink(y0, rows, buf.data())) return false;
    }
    return true;
}

}  // namespace gccore
