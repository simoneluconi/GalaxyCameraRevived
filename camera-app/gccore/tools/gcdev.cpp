// gcdev: develops an M9MO .raw with gccore's pipeline and reports the time.
// Runs on the PC and, built with the NDK, on the camera itself (the benchmark).
//
// Usage: gcdev IMG_x.raw [out.ppm|out.jpg] [--threads N] [--repeat N] [--lens focal_mm]
//              [--isp isp.jpg] [--sharpen amount] [--quality Q]
// --lens develops the ISP frame with lens correction; --isp takes the white balance and
// exposure from the ISP's JPEG of the same shot (needs the JPEG build); a .jpg output encodes
// with libjpeg-turbo, anything else is written as PPM.

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "gccore/develop.h"
#include "gccore/dng.h"
#ifdef GCCORE_WITH_JPEG
#include "gccore/jpeg.h"
#endif

namespace {

std::vector<uint8_t> readFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

bool endsWith(const std::string& s, const char* suffix) {
    const size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s IMG_x.raw [out.ppm|out.jpg] [--threads N] [--repeat N] [--lens mm] "
                             "[--isp isp.jpg] [--sharpen amount] [--quality Q]\n", argv[0]);
        return 2;
    }
    std::string out, isp;
    int threads = 4;
    int repeat = 1;
    int quality = 95;
    double lensFocal = 0;  // > 0: the lens-corrected path at this focal length (mm)
    double sharpen = -1;
    for (int i = 2; i < argc; i++) {
        if (std::strcmp(argv[i], "--threads") == 0 && i + 1 < argc) {
            threads = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--repeat") == 0 && i + 1 < argc) {
            repeat = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--lens") == 0 && i + 1 < argc) {
            lensFocal = std::atof(argv[++i]);
        } else if (std::strcmp(argv[i], "--isp") == 0 && i + 1 < argc) {
            isp = argv[++i];
        } else if (std::strcmp(argv[i], "--sharpen") == 0 && i + 1 < argc) {
            sharpen = std::atof(argv[++i]);
        } else if (std::strcmp(argv[i], "--quality") == 0 && i + 1 < argc) {
            quality = std::atoi(argv[++i]);
        } else {
            out = argv[i];
        }
    }

    std::vector<uint8_t> data = readFile(argv[1]);
    gccore::RawFrame raw;  // the measured default layout (docs/CAMERA-APP.md §3.2)
    raw.data = data.data();
    raw.size = data.size();
    if (!raw.valid()) {
        std::fprintf(stderr, "not a 4736x3452 raw10be frame (%zu bytes)\n", data.size());
        return 1;
    }

    gccore::DevelopParams p;
    gccore::estimateNeutral(raw, p.neutral);
    p.threads = threads;
    gccore::CorrectedParams cp;
    if (lensFocal > 0) {
        cp.lens = gccore::lensAt(lensFocal);
        p.exposure = 1.0;
        if (sharpen >= 0) cp.sharpenAmount = sharpen;
    }
    if (!isp.empty()) {
#ifdef GCCORE_WITH_JPEG
        std::vector<uint8_t> j = readFile(isp.c_str());
        auto t0 = std::chrono::steady_clock::now();
        bool ok = gccore::matchIsp(raw, j.data(), j.size(), lensFocal > 0 ? lensFocal : 4.1, p);
        double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::printf("matchIsp: %s in %.3f s, neutral %.3f,1,%.3f exposure %.3f\n", ok ? "ok" : "failed", s,
                    p.neutral[0], p.neutral[2], p.exposure);
#else
        std::fprintf(stderr, "--isp needs the JPEG build\n");
        return 2;
#endif
    }
    const int aw = lensFocal > 0 ? gccore::kIspWidth : raw.right - raw.left;
    const int ah = lensFocal > 0 ? gccore::kIspHeight : raw.bottom - raw.top;

#ifdef GCCORE_WITH_JPEG
    if (endsWith(out, ".jpg") && lensFocal > 0) {
        FILE* o = std::fopen(out.c_str(), "wb");
        auto t0 = std::chrono::steady_clock::now();
        std::string error;
        bool ok = gccore::developToJpeg(raw, p, lensFocal, quality, o, &error);
        std::fclose(o);
        double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::printf("developToJpeg: %s in %.3f s %s\n", ok ? "ok" : "failed", s, error.c_str());
        return ok ? 0 : 1;
    }
#endif

    FILE* o = nullptr;
    if (!out.empty()) {
        o = std::fopen(out.c_str(), "wb");
        std::fprintf(o, "P6\n%d %d\n255\n", aw, ah);
    }
    double best = 1e9;
    for (int r = 0; r < repeat; r++) {
        auto t0 = std::chrono::steady_clock::now();
        auto sink = [&](int, int rows, const uint8_t* rgb) {
            if (o && r == 0) std::fwrite(rgb, 1, static_cast<size_t>(rows) * aw * 3, o);
            return true;
        };
        if (lensFocal > 0) {
            gccore::developCorrected(raw, p, cp, sink);
        } else {
            gccore::develop(raw, p, sink);
        }
        double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        best = std::min(best, s);
        std::printf("run %d: %.3f s\n", r + 1, s);
    }
    if (o) std::fclose(o);
    std::printf("%dx%d, %d threads, best %.3f s (%.1f Mpx/s), neutral %.3f,1,%.3f\n", aw, ah, threads, best,
                aw * static_cast<double>(ah) / best / 1e6, p.neutral[0], p.neutral[2]);
    return 0;
}
