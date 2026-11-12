#include "gccore/jpeg.h"

#include <algorithm>
#include <cmath>
#include <csetjmp>
#include <cstdlib>
#include <vector>

#include <jpeglib.h>

#include "gccore/lens.h"

namespace gccore {

namespace {

/** libjpeg error manager that longjmps back instead of exiting the process. */
struct JpegError {
    jpeg_error_mgr mgr;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];

    static void onError(j_common_ptr c) {
        auto* e = reinterpret_cast<JpegError*>(c->err);
        (*c->err->format_message)(c, e->message);
        longjmp(e->jump, 1);
    }

    /** Warnings stay quiet: the ISP pads its JPEGs, which libjpeg reports as "extraneous bytes". */
    static void onMessage(j_common_ptr, int) {}

    void install(jpeg_error_mgr*& slot) {
        slot = jpeg_std_error(&mgr);
        mgr.error_exit = onError;
        mgr.emit_message = onMessage;
    }
};

/** Decodes [jpeg] at 1/8 scale into [rgb]; false on any libjpeg error. */
bool decodeEighth(const uint8_t* jpeg, size_t size, std::vector<uint8_t>& rgb, int& w, int& h) {
    jpeg_decompress_struct cinfo{};
    JpegError err{};
    err.install(cinfo.err);
    if (setjmp(err.jump)) {
        jpeg_destroy_decompress(&cinfo);
        return false;
    }
    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, jpeg, static_cast<unsigned long>(size));
    jpeg_read_header(&cinfo, TRUE);
    cinfo.scale_num = 1;
    cinfo.scale_denom = 8;
    cinfo.out_color_space = JCS_RGB;
    cinfo.dct_method = JDCT_IFAST;
    jpeg_start_decompress(&cinfo);
    w = static_cast<int>(cinfo.output_width);
    h = static_cast<int>(cinfo.output_height);
    rgb.resize(static_cast<size_t>(w) * h * 3);
    while (cinfo.output_scanline < cinfo.output_height) {
        JSAMPROW row = rgb.data() + static_cast<size_t>(cinfo.output_scanline) * w * 3;
        jpeg_read_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);
    return true;
}

double median(std::vector<double>& v) {
    std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
    return v[v.size() / 2];
}

}  // namespace

bool matchIsp(const RawFrame& raw, const uint8_t* jpeg, size_t size, double focalMm, const OpticalCentre& centre,
              IspParams& params) {
    if (!raw.valid() || !jpeg || size == 0) return false;
    std::vector<uint8_t> img;
    int dw = 0, dh = 0;
    if (!decodeEighth(jpeg, size, img, dw, dh) || dw < 16 || dh < 16) return false;
    // Only the full 4:3 frame maps onto the RAW this way; a 3:2 or 16:9 picture is a crop
    if (std::abs(dw * kIspHeight - dh * kIspWidth) > 2 * kIspWidth) return false;
    const double scale = static_cast<double>(kIspWidth) / dw;
    const LensCorrection lens = lensAt(focalMm);
    const double range = raw.white - raw.black;
    const double* M = params.camToSrgb;
    // Inverse colour matrix: linear sRGB → white-balanced camera RGB
    const double det = M[0] * (M[4] * M[8] - M[5] * M[7]) - M[1] * (M[3] * M[8] - M[5] * M[6]) +
                       M[2] * (M[3] * M[7] - M[4] * M[6]);
    if (std::fabs(det) < 1e-9) return false;
    const double inv[9] = {(M[4] * M[8] - M[5] * M[7]) / det, (M[2] * M[7] - M[1] * M[8]) / det,
                           (M[1] * M[5] - M[2] * M[4]) / det, (M[5] * M[6] - M[3] * M[8]) / det,
                           (M[0] * M[8] - M[2] * M[6]) / det, (M[2] * M[3] - M[0] * M[5]) / det,
                           (M[3] * M[7] - M[4] * M[6]) / det, (M[1] * M[6] - M[0] * M[7]) / det,
                           (M[0] * M[4] - M[1] * M[3]) / det};
    double toLinear[256];
    for (int v = 0; v < 256; v++) toLinear[v] = toneInverse(v);

    std::vector<double> rg, bg, gg;
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    constexpr int kBox = 8;  // raw pixels per side (4x4 CFA quads)
    for (int jy = 1; jy < dh - 1; jy += 2) {
        for (int jx = 1; jx < dw - 1; jx += 2) {
            // A flat 3x3 patch (24 ISP pixels a side), in the measured part of the curve
            int lo[3] = {255, 255, 255}, hi[3] = {0, 0, 0}, sum[3] = {0, 0, 0};
            for (int dy = -1; dy <= 1; dy++) {
                const uint8_t* p = img.data() + (static_cast<size_t>(jy + dy) * dw + jx - 1) * 3;
                for (int dx = 0; dx < 3; dx++) {
                    for (int c = 0; c < 3; c++) {
                        lo[c] = std::min<int>(lo[c], p[dx * 3 + c]);
                        hi[c] = std::max<int>(hi[c], p[dx * 3 + c]);
                        sum[c] += p[dx * 3 + c];
                    }
                }
            }
            if (hi[0] - lo[0] > 8 || hi[1] - lo[1] > 8 || hi[2] - lo[2] > 8) continue;
            if (std::min({lo[0], lo[1], lo[2]}) < 20 || std::max({hi[0], hi[1], hi[2]}) > 195) continue;
            double lin[3];
            for (int c = 0; c < 3; c++) lin[c] = toLinear[(sum[c] + 4) / 9];
            double isp[3];
            for (int r = 0; r < 3; r++) isp[r] = inv[r * 3] * lin[0] + inv[r * 3 + 1] * lin[1] + inv[r * 3 + 2] * lin[2];
            if (isp[0] <= 0.002 || isp[1] <= 0.002 || isp[2] <= 0.002) continue;

            // The same place in the RAW: per-colour means over a small even-aligned box
            double sx, sy;
            ispToActive(lens, centre, (jx + 0.5) * scale, (jy + 0.5) * scale, sx, sy);
            const int bx = (static_cast<int>(sx) - kBox / 2) & ~1;
            const int by = (static_cast<int>(sy) - kBox / 2) & ~1;
            if (bx < 0 || by < 0 || bx + kBox > aw || by + kBox > ah) continue;
            double cam[3] = {0, 0, 0};
            int n[3] = {0, 0, 0};
            bool clipped = false;
            for (int y = 0; y < kBox; y++) {
                for (int x = 0; x < kBox; x++) {
                    const double v = raw.at(raw.left + bx + x, raw.top + by + y) - raw.black;
                    if (v > 0.9 * range) clipped = true;
                    const int c = raw.cfa[((by + y) & 1) * 2 + ((bx + x) & 1)];
                    cam[c] += v;
                    n[c]++;
                }
            }
            if (clipped) continue;
            for (int c = 0; c < 3; c++) cam[c] = cam[c] / std::max(n[c], 1) / range;
            if (cam[0] <= 0.001 || cam[1] <= 0.001 || cam[2] <= 0.001) continue;
            // isp = exposure × cam / neutral, channel by channel
            const double g[3] = {isp[0] / cam[0], isp[1] / cam[1], isp[2] / cam[2]};
            rg.push_back(g[0] / g[1]);
            bg.push_back(g[2] / g[1]);
            gg.push_back(g[1]);
        }
    }
    if (rg.size() < 40) return false;
    const double neutralR = 1.0 / median(rg);
    const double neutralB = 1.0 / median(bg);
    const double exposure = median(gg);
    if (!(neutralR > 0.2 && neutralR < 3 && neutralB > 0.2 && neutralB < 3 && exposure > 0.25 && exposure < 4)) {
        return false;
    }
    params.neutral[0] = neutralR;
    params.neutral[1] = 1.0;
    params.neutral[2] = neutralB;
    params.exposure = exposure;
    return true;
}

}  // namespace gccore
